use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering as AtomicOrdering};
use walkdir::WalkDir;

use super::BUFFER_SIZE;
use super::format::{ArchiveEntryData, normalize_entry_path};

pub(crate) fn list_sevenz(
    archive_path: &Path,
    password: Option<&str>,
) -> Result<Vec<ArchiveEntryData>, Box<dyn std::error::Error + Send + Sync>> {
    let pw = password
        .map(sevenz_rust::Password::from)
        .unwrap_or_default();
    let sz = sevenz_rust::ArchiveReader::open(archive_path, pw)?;
    let mut out = Vec::new();
    let archive = sz.archive();

    for (file_idx, entry) in archive.files.iter().enumerate() {
        if entry.is_anti_item {
            continue;
        }
        let name = normalize_entry_path(entry.name());
        if name.is_empty() {
            continue;
        }

        let is_dir = entry.is_directory;
        let size = entry.size as i64;
        let last_modified = if entry.has_last_modified_date {
            std::time::SystemTime::from(entry.last_modified_date)
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0)
        } else {
            0
        };

        let is_encrypted = if entry.has_stream {
            archive
                .stream_map
                .file_block_index
                .get(file_idx)
                .copied()
                .flatten()
                .and_then(|idx| archive.blocks.get(idx))
                .map(|b| {
                    b.coders.iter().any(|c| {
                        c.encoder_method_id() == sevenz_rust::EncoderMethod::ID_AES256_SHA256
                    })
                })
                .unwrap_or(false)
        } else {
            false
        };

        out.push(ArchiveEntryData {
            path: name,
            is_directory: is_dir,
            size,
            compressed_size: -1,
            last_modified,
            is_encrypted,
        });
    }

    Ok(out)
}

pub(crate) fn extract_sevenz(
    archive_path: &Path,
    dest_dir: &Path,
    password: Option<&str>,
    match_fn: &impl Fn(&str) -> bool,
    rel_fn: &impl Fn(&str) -> String,
    resolve_target: &impl Fn(&str, bool) -> Option<PathBuf>,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    let mut total_bytes = 0i64;
    let pw = password
        .map(sevenz_rust::Password::from)
        .unwrap_or_default();
    {
        let sz = sevenz_rust::ArchiveReader::open(archive_path, pw.clone())?;
        for entry in &sz.archive().files {
            if !entry.is_anti_item && !entry.is_directory {
                let name = normalize_entry_path(entry.name());
                if match_fn(&name) {
                    total_bytes += entry.size as i64;
                }
            }
        }
    }

    let mut done_bytes = 0i64;
    let mut extracted_count = 0i32;
    let mut buf = vec![0u8; BUFFER_SIZE];

    let file = File::open(archive_path)?;
    sevenz_rust::decompress_with_extract_fn_and_password(
        file,
        dest_dir,
        pw,
        |entry, reader, _dest| {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err(sevenz_rust::Error::Other("Operation cancelled".into()));
            }

            let entry_name = entry.name().to_string();
            if entry_name.contains("..") {
                return Err(sevenz_rust::Error::Other(
                    "Zip-slip path traversal is unsupported".into(),
                ));
            }
            let raw_name = normalize_entry_path(&entry_name);
            if raw_name.contains("..") {
                return Err(sevenz_rust::Error::Other(
                    "Zip-slip path traversal is unsupported".into(),
                ));
            }
            if raw_name.is_empty() || !match_fn(&raw_name) {
                return Ok(true);
            }

            let rel = rel_fn(&raw_name);
            if rel.is_empty() {
                return Ok(true);
            }

            if entry.is_directory {
                if let Some(target) = resolve_target(&rel, true) {
                    let _ = fs::create_dir_all(target);
                }
                return Ok(true);
            }

            let target = match resolve_target(&rel, false) {
                Some(t) => t,
                None => return Ok(true),
            };

            if let Some(parent) = target.parent() {
                let _ = fs::create_dir_all(parent);
            }

            let cur_name = target
                .file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default();
            let mut writer = match File::create(&target) {
                Ok(f) => f,
                Err(e) => return Err(sevenz_rust::Error::from(e)),
            };

            loop {
                if !cancel_token.is_null()
                    && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                {
                    let _ = fs::remove_file(&target);
                    return Err(sevenz_rust::Error::Other("Operation cancelled".into()));
                }

                let n = match reader.read(&mut buf) {
                    Ok(n) => n,
                    Err(e) => return Err(sevenz_rust::Error::from(e)),
                };
                if n == 0 {
                    break;
                }
                if let Err(e) = writer.write_all(&buf[..n]) {
                    return Err(sevenz_rust::Error::from(e));
                }
                done_bytes += n as i64;
                if on_progress(done_bytes, total_bytes, &cur_name).is_err() {
                    let _ = fs::remove_file(&target);
                    return Err(sevenz_rust::Error::Other("Operation cancelled".into()));
                }
            }
            let _ = writer.flush();
            extracted_count += 1;
            Ok(true)
        },
    )?;

    Ok(extracted_count)
}

pub(crate) fn create_sevenz(
    sources: &[PathBuf],
    output_path: &Path,
    part_path: &Path,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    let mut writer = sevenz_rust::ArchiveWriter::create(part_path)?;
    let mut written_bytes = 0i64;

    for src in sources {
        let root_parent = src.parent().unwrap_or(src);

        for entry in WalkDir::new(src) {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err("Operation cancelled".into());
            }

            let entry = entry?;
            let entry_path = entry.path();
            if entry_path == part_path || entry_path == output_path {
                continue;
            }
            let rel = entry_path
                .strip_prefix(root_parent)
                .unwrap_or(entry_path);
            let rel_str = rel.to_string_lossy().replace('\\', "/");
            if rel_str.is_empty() {
                continue;
            }

            if entry.file_type().is_dir() {
                let archive_entry = sevenz_rust::ArchiveEntry::from_path(entry.path(), rel_str);
                writer.push_archive_entry::<&[u8]>(archive_entry, None)?;
            } else if entry.file_type().is_file() {
                let name = entry.file_name().to_string_lossy();
                let meta = entry.metadata()?;
                let archive_entry = sevenz_rust::ArchiveEntry::from_path(entry.path(), rel_str);
                let file = File::open(entry.path())?;
                writer.push_archive_entry(archive_entry, Some(file))?;
                written_bytes += meta.len() as i64;
                if on_progress(written_bytes, total_bytes, &name).is_err() {
                    return Err("Operation cancelled".into());
                }
            }
        }
    }

    writer.finish()?;
    Ok(written_bytes)
}
