use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering as AtomicOrdering};
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;
use zip::{ZipArchive, ZipWriter};

use super::BUFFER_SIZE;
use super::format::{ArchiveEntryData, normalize_entry_path};

pub(crate) fn list_zip(
    archive_path: &Path,
) -> Result<Vec<ArchiveEntryData>, Box<dyn std::error::Error + Send + Sync>> {
    let file = File::open(archive_path)?;
    let reader = BufReader::with_capacity(BUFFER_SIZE, file);
    let mut zip = ZipArchive::new(reader)?;
    let mut out = Vec::with_capacity(zip.len());

    for i in 0..zip.len() {
        let entry = zip.by_index_raw(i)?;
        let name = normalize_entry_path(entry.name());
        if name.is_empty() {
            continue;
        }

        let is_dir = entry.is_dir();
        let size = entry.size() as i64;
        let compressed_size = entry.compressed_size() as i64;
        let is_encrypted = entry.encrypted();
        let last_modified = entry
            .last_modified()
            .and_then(|dt| {
                let y = dt.year() as i32;
                let m = dt.month() as u32;
                let d = dt.day() as u32;
                let h = dt.hour() as u32;
                let min = dt.minute() as u32;
                let s = dt.second() as u32;
                chrono::NaiveDate::from_ymd_opt(y, m, d)
                    .and_then(|date| date.and_hms_opt(h, min, s))
                    .map(|naive| naive.and_utc().timestamp_millis())
            })
            .unwrap_or(0);

        out.push(ArchiveEntryData {
            path: name,
            is_directory: is_dir,
            size,
            compressed_size,
            last_modified,
            is_encrypted,
        });
    }

    Ok(out)
}

pub(crate) fn extract_zip(
    archive_path: &Path,
    _dest_dir: &Path,
    password: Option<&str>,
    match_fn: &impl Fn(&str) -> bool,
    rel_fn: &impl Fn(&str) -> String,
    resolve_target: &impl Fn(&str, bool) -> Option<PathBuf>,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    let file = File::open(archive_path)?;
    let reader = BufReader::with_capacity(BUFFER_SIZE, file);
    let mut zip = ZipArchive::new(reader)?;

    let mut total_bytes = 0i64;
    for i in 0..zip.len() {
        let entry = zip.by_index_raw(i)?;
        let name = normalize_entry_path(entry.name());
        if !entry.is_dir() && match_fn(&name) {
            total_bytes += entry.size() as i64;
        }
    }

    let mut done_bytes = 0i64;
    let mut extracted_count = 0i32;
    let mut buf = vec![0u8; BUFFER_SIZE];

    for i in 0..zip.len() {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err("Operation cancelled".into());
        }

        let is_encrypted = zip.by_index_raw(i)?.encrypted();
        let mut entry = if is_encrypted {
            if let Some(pw) = password {
                zip.by_index_decrypt(i, pw.as_bytes())?
            } else {
                return Err("Password required to decrypt entry".into());
            }
        } else {
            zip.by_index(i)?
        };

        let entry_name = entry.name().to_string();
        if entry_name.contains("..") {
            return Err("Zip-slip path traversal is unsupported".into());
        }
        let raw_name = normalize_entry_path(&entry_name);
        if raw_name.contains("..") {
            return Err("Zip-slip path traversal is unsupported".into());
        }
        if raw_name.is_empty() || !match_fn(&raw_name) {
            continue;
        }

        let rel = rel_fn(&raw_name);
        if rel.is_empty() {
            continue;
        }

        if entry.is_dir() {
            if let Some(target) = resolve_target(&rel, true) {
                fs::create_dir_all(target)?;
            }
            continue;
        }

        let target = match resolve_target(&rel, false) {
            Some(t) => t,
            None => {
                done_bytes += entry.size() as i64;
                continue;
            }
        };

        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }

        let cur_name = target
            .file_name()
            .map(|n| n.to_string_lossy())
            .unwrap_or_default();
        let mut writer = BufWriter::with_capacity(BUFFER_SIZE, File::create(&target)?);

        loop {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                let _ = fs::remove_file(&target);
                return Err("Operation cancelled".into());
            }

            let n = entry.read(&mut buf)?;
            if n == 0 {
                break;
            }
            writer.write_all(&buf[..n])?;
            done_bytes += n as i64;
            if on_progress(done_bytes, total_bytes, &cur_name).is_err() {
                let _ = fs::remove_file(&target);
                return Err("Operation cancelled".into());
            }
        }
        writer.flush()?;
        extracted_count += 1;
    }

    Ok(extracted_count)
}

pub(crate) fn create_zip(
    sources: &[PathBuf],
    output_path: &Path,
    part_path: &Path,
    level: i32,
    password: Option<&str>,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    let file = File::create(part_path)?;
    let writer = BufWriter::with_capacity(BUFFER_SIZE, file);
    let mut zip = ZipWriter::new(writer);

    let (method, comp_level) = if level == 0 {
        (zip::CompressionMethod::Stored, None)
    } else {
        (
            zip::CompressionMethod::Deflated,
            Some(level.clamp(1, 9) as i64),
        )
    };

    let mut options = SimpleFileOptions::default()
        .compression_method(method)
        .compression_level(comp_level);

    if let Some(pw) = password
        && !pw.is_empty() {
            options = options.with_aes_encryption(zip::AesMode::Aes256, pw);
        }

    let mut written_bytes = 0i64;
    let mut buf = vec![0u8; BUFFER_SIZE];

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

            if entry.file_type().is_dir() {
                zip.add_directory(format!("{}/", rel_str), options)?;
            } else if entry.file_type().is_file() {
                let name = entry.file_name().to_string_lossy();
                zip.start_file(rel_str, options)?;
                let mut reader = BufReader::with_capacity(BUFFER_SIZE, File::open(entry.path())?);

                loop {
                    if !cancel_token.is_null()
                        && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                    {
                        return Err("Operation cancelled".into());
                    }

                    let n = reader.read(&mut buf)?;
                    if n == 0 {
                        break;
                    }
                    zip.write_all(&buf[..n])?;
                    written_bytes += n as i64;
                    if on_progress(written_bytes, total_bytes, &name).is_err() {
                        return Err("Operation cancelled".into());
                    }
                }
            }
        }
    }

    zip.finish()?;
    Ok(written_bytes)
}

struct TempFileGuard<'a>(&'a Path, bool);
impl<'a> Drop for TempFileGuard<'a> {
    fn drop(&mut self) {
        if !self.1 {
            let _ = fs::remove_file(self.0);
        }
    }
}

pub fn remove_zip_entries(
    archive_path: &Path,
    paths_to_remove: &[String],
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    let part_name = format!(
        "{}.tmp_edit",
        archive_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("archive")
    );
    let part_path = archive_path.with_file_name(part_name);
    let mut guard = TempFileGuard(&part_path, false);
    let file = File::open(archive_path)?;
    let mut zip_in = ZipArchive::new(BufReader::with_capacity(BUFFER_SIZE, file))?;
    let out_file = File::create(&part_path)?;
    let mut zip_out = ZipWriter::new(BufWriter::with_capacity(BUFFER_SIZE, out_file));

    let total = zip_in.len();
    let mut removed_count = 0i32;

    for i in 0..total {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err("Operation cancelled".into());
        }

        let entry = zip_in.by_index_raw(i)?;
        let name = normalize_entry_path(entry.name());

        let remove = paths_to_remove
            .iter()
            .any(|p| name == *p || name.starts_with(&format!("{}/", p)));

        if remove {
            removed_count += 1;
            let _ = on_progress(i as i64 + 1, total as i64, &name);
            continue;
        }

        zip_out.raw_copy_file(entry)?;
        let _ = on_progress(i as i64 + 1, total as i64, &name);
    }

    zip_out.finish()?;
    fs::rename(&part_path, archive_path)?;
    guard.1 = true;
    Ok(removed_count)
}

pub fn add_zip_entries(
    archive_path: &Path,
    inner_dir: &str,
    source_paths: &[PathBuf],
    level: i32,
    password: Option<&str>,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    let part_name = format!(
        "{}.tmp_edit",
        archive_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("archive")
    );
    let part_path = archive_path.with_file_name(part_name);
    let mut guard = TempFileGuard(&part_path, false);
    let file = File::open(archive_path)?;
    let mut zip_in = ZipArchive::new(BufReader::with_capacity(BUFFER_SIZE, file))?;
    let out_file = File::create(&part_path)?;
    let mut zip_out = ZipWriter::new(BufWriter::with_capacity(BUFFER_SIZE, out_file));

    let mut buf = vec![0u8; BUFFER_SIZE];
    let inner_clean = inner_dir.trim_matches('/');

    // Collect paths to be added so we don't write duplicate entries
    let mut new_entry_paths = std::collections::HashSet::new();
    for src in source_paths {
        let root_parent = src.parent().unwrap_or(src);
        for entry in WalkDir::new(src).into_iter().flatten() {
            let entry_path = entry.path();
            if entry_path == part_path || entry_path == archive_path {
                continue;
            }
            let rel_from_root = entry_path
                .strip_prefix(root_parent)
                .unwrap_or(entry_path);
            let rel_str = rel_from_root.to_string_lossy().replace('\\', "/");
            let rel = if inner_clean.is_empty() {
                rel_str
            } else {
                format!("{}/{}", inner_clean, rel_str.trim_start_matches('/'))
            };
            new_entry_paths.insert(normalize_entry_path(&rel));
        }
    }

    // First copy existing entries with raw copying (preserves encryption/compression)
    for i in 0..zip_in.len() {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err("Operation cancelled".into());
        }

        let entry = zip_in.by_index_raw(i)?;
        let name = normalize_entry_path(entry.name());
        if new_entry_paths.contains(&name) {
            continue;
        }
        zip_out.raw_copy_file(entry)?;
    }

    // Now append new sources
    let (method, comp_level) = if level == 0 {
        (zip::CompressionMethod::Stored, None)
    } else {
        (
            zip::CompressionMethod::Deflated,
            Some(level.clamp(1, 9) as i64),
        )
    };

    let mut options = SimpleFileOptions::default()
        .compression_method(method)
        .compression_level(comp_level);

    if let Some(pw) = password
        && !pw.is_empty() {
            options = options.with_aes_encryption(zip::AesMode::Aes256, pw);
        }

    let mut added_count = 0i32;

    for src in source_paths {
        let root_parent = src.parent().unwrap_or(src);

        for entry in WalkDir::new(src) {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err("Operation cancelled".into());
            }

            let entry = entry?;
            let entry_path = entry.path();
            if entry_path == part_path || entry_path == archive_path {
                continue;
            }
            let rel_from_root = entry_path
                .strip_prefix(root_parent)
                .unwrap_or(entry_path);
            let rel_str = rel_from_root.to_string_lossy().replace('\\', "/");
            let rel = if inner_clean.is_empty() {
                rel_str
            } else {
                format!("{}/{}", inner_clean, rel_str.trim_start_matches('/'))
            };

            let name = entry.file_name().to_string_lossy();
            if entry.file_type().is_dir() {
                zip_out.add_directory(format!("{}/", rel), options)?;
            } else if entry.file_type().is_file() {
                zip_out.start_file(rel, options)?;
                let mut reader = BufReader::with_capacity(BUFFER_SIZE, File::open(entry.path())?);
                loop {
                    let n = reader.read(&mut buf)?;
                    if n == 0 {
                        break;
                    }
                    zip_out.write_all(&buf[..n])?;
                }
                added_count += 1;
                let _ = on_progress(added_count as i64, source_paths.len() as i64, &name);
            }
        }
    }

    zip_out.finish()?;
    fs::rename(&part_path, archive_path)?;
    guard.1 = true;
    Ok(added_count)
}
