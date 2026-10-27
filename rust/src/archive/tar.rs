use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering as AtomicOrdering};
use walkdir::WalkDir;

use super::{BUFFER_SIZE, CountingReader};
use super::format::{ArchiveEntryData, Format, normalize_entry_path};

pub(crate) fn list_tar(
    archive_path: &Path,
    format: Format,
) -> Result<Vec<ArchiveEntryData>, Box<dyn std::error::Error + Send + Sync>> {
    let file = File::open(archive_path)?;
    let reader = BufReader::with_capacity(BUFFER_SIZE, file);

    let stream: Box<dyn Read> = match format {
        Format::Tar => Box::new(reader),
        Format::TarGz => Box::new(GzDecoder::new(reader)),
        Format::TarBz2 => Box::new(bzip2::read::BzDecoder::new(reader)),
        Format::TarXz => Box::new(lzma_rust2::XzReader::new(reader, true)),
        Format::TarZst => Box::new(zstd::Decoder::new(reader)?),
        _ => return Err("Not a TAR format".into()),
    };

    let mut tar = tar::Archive::new(stream);
    let mut out = Vec::new();

    for entry in tar.entries()? {
        let entry = entry?;
        let path = entry.path()?;
        let name = normalize_entry_path(&path.to_string_lossy());
        if name.is_empty() {
            continue;
        }

        let header = entry.header();
        let is_dir = header.entry_type().is_dir();
        let size = header.size().unwrap_or(0) as i64;
        let mtime = header.mtime().unwrap_or(0) as i64 * 1000;

        out.push(ArchiveEntryData {
            path: name,
            is_directory: is_dir,
            size,
            compressed_size: -1,
            last_modified: mtime,
            is_encrypted: false,
        });
    }

    Ok(out)
}

pub(crate) fn extract_tar(
    archive_path: &Path,
    _dest_dir: &Path,
    format: Format,
    match_fn: &impl Fn(&str) -> bool,
    rel_fn: &impl Fn(&str) -> String,
    resolve_target: &impl Fn(&str) -> Option<PathBuf>,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    let file = File::open(archive_path)?;
    let total_bytes = file.metadata()?.len() as i64;
    let reader = BufReader::with_capacity(BUFFER_SIZE, file);
    let bytes_read = Arc::new(AtomicI64::new(0));
    let counting = CountingReader {
        inner: reader,
        bytes_read: bytes_read.clone(),
    };

    let stream: Box<dyn Read> = match format {
        Format::Tar => Box::new(counting),
        Format::TarGz => Box::new(GzDecoder::new(counting)),
        Format::TarBz2 => Box::new(bzip2::read::BzDecoder::new(counting)),
        Format::TarXz => Box::new(lzma_rust2::XzReader::new(counting, true)),
        Format::TarZst => Box::new(zstd::Decoder::new(counting)?),
        _ => return Err("Not a TAR format".into()),
    };

    let mut tar = tar::Archive::new(stream);
    let mut extracted_count = 0i32;
    let mut buf = vec![0u8; BUFFER_SIZE];

    for entry in tar.entries()? {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err("Operation cancelled".into());
        }

        let mut entry = entry?;
        let path = entry.path()?;
        let path_str = path.to_string_lossy().to_string();
        if path_str.contains("..") {
            return Err("Zip-slip path traversal is unsupported".into());
        }
        let raw_name = normalize_entry_path(&path_str);
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

        let header = entry.header();
        if header.entry_type().is_dir() {
            if let Some(target) = resolve_target(&rel) {
                fs::create_dir_all(target)?;
            }
            continue;
        }

        let target = match resolve_target(&rel) {
            Some(t) => t,
            None => continue,
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
            let current_done = bytes_read.load(AtomicOrdering::Relaxed).min(total_bytes);
            if on_progress(current_done, total_bytes, &cur_name).is_err() {
                let _ = fs::remove_file(&target);
                return Err("Operation cancelled".into());
            }
        }
        writer.flush()?;
        extracted_count += 1;
    }

    Ok(extracted_count)
}

struct TempFileGuard<'a>(&'a Path, bool);
impl<'a> Drop for TempFileGuard<'a> {
    fn drop(&mut self) {
        if !self.1 {
            let _ = fs::remove_file(self.0);
        }
    }
}

pub(crate) fn create_tar(
    sources: &[PathBuf],
    output_path: &Path,
    part_path: &Path,
    format_str: &str,
    level: i32,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    if format_str.to_ascii_uppercase().as_str() == "TAR.XZ" {
        let tar_temp_path = part_path.with_extension("tar.tmp");
        let tar_guard = TempFileGuard(&tar_temp_path, false);
        let tar_file = File::create(&tar_temp_path)?;
        let writer = BufWriter::with_capacity(BUFFER_SIZE, tar_file);
        let mut tar = tar::Builder::new(writer);
        let mut written_bytes = 0i64;

        for src in sources {
            let root_parent = src.parent().unwrap_or(src);

            for entry in WalkDir::new(src) {
                if !cancel_token.is_null()
                    && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                {
                    return Err("Operation cancelled".into());
                }

                let entry = entry?;
                let entry_path = entry.path();
                if entry_path == part_path || entry_path == output_path || entry_path == tar_temp_path {
                    continue;
                }
                let rel = entry_path
                    .strip_prefix(root_parent)
                    .unwrap_or(entry_path);

                if entry.file_type().is_dir() {
                    tar.append_dir(rel, entry.path())?;
                } else if entry.file_type().is_file() {
                    let name = entry.file_name().to_string_lossy();
                    let meta = entry.metadata()?;
                    let mut header = tar::Header::new_gnu();
                    header.set_size(meta.len());
                    header.set_mode(0o644);
                    let mtime = meta
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_secs())
                        .unwrap_or(0);
                    header.set_mtime(mtime);
                    header.set_cksum();

                    let mut reader =
                        BufReader::with_capacity(BUFFER_SIZE, File::open(entry.path())?);
                    tar.append_data(&mut header, rel, &mut reader)?;
                    written_bytes += meta.len() as i64;
                    if on_progress(written_bytes, total_bytes, &name).is_err() {
                        return Err("Operation cancelled".into());
                    }
                }
            }
        }

        let mut writer = tar.into_inner()?;
        writer.flush()?;
        drop(writer);
        let mut tar_reader = BufReader::with_capacity(BUFFER_SIZE, File::open(&tar_temp_path)?);
        let out_file = File::create(part_path)?;
        let mut xz_writer = lzma_rust2::XzWriter::new(
            BufWriter::with_capacity(BUFFER_SIZE, out_file),
            lzma_rust2::XzOptions::with_preset(level.clamp(0, 9) as u32),
        )?;
        std::io::copy(&mut tar_reader, &mut xz_writer)?;
        let mut w = xz_writer.finish()?;
        w.flush()?;
        drop(tar_reader);
        drop(tar_guard);
        return Ok(written_bytes);
    }

    let file = File::create(part_path)?;
    let writer = BufWriter::with_capacity(BUFFER_SIZE, file);

    match format_str.to_ascii_uppercase().as_str() {
        "TAR" => {
            let mut tar = tar::Builder::new(writer);
            let written =
                write_tar_entries(&mut tar, sources, output_path, part_path, total_bytes, cancel_token, on_progress)?;
            let mut w = tar.into_inner()?;
            w.flush()?;
            Ok(written)
        }
        "TAR.GZ" => {
            let comp = if level == 0 {
                Compression::none()
            } else {
                Compression::new(level.clamp(1, 9) as u32)
            };
            let enc = GzEncoder::new(writer, comp);
            let mut tar = tar::Builder::new(enc);
            let written =
                write_tar_entries(&mut tar, sources, output_path, part_path, total_bytes, cancel_token, on_progress)?;
            let enc = tar.into_inner()?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        "TAR.BZ2" => {
            let enc = bzip2::write::BzEncoder::new(
                writer,
                bzip2::Compression::new(level.clamp(1, 9) as u32),
            );
            let mut tar = tar::Builder::new(enc);
            let written =
                write_tar_entries(&mut tar, sources, output_path, part_path, total_bytes, cancel_token, on_progress)?;
            let enc = tar.into_inner()?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        "TAR.ZST" => {
            let enc = zstd::Encoder::new(writer, level.clamp(1, 9))?;
            let mut tar = tar::Builder::new(enc);
            let written =
                write_tar_entries(&mut tar, sources, output_path, part_path, total_bytes, cancel_token, on_progress)?;
            let enc = tar.into_inner()?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        _ => Err("Not a TAR format".into()),
    }
}

fn write_tar_entries<W: Write>(
    tar: &mut tar::Builder<W>,
    sources: &[PathBuf],
    output_path: &Path,
    part_path: &Path,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
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

            if entry.file_type().is_dir() {
                tar.append_dir(rel, entry.path())?;
            } else if entry.file_type().is_file() {
                let name = entry.file_name().to_string_lossy();
                let meta = entry.metadata()?;
                let mut header = tar::Header::new_gnu();
                header.set_size(meta.len());
                header.set_mode(0o644);
                let mtime = meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                header.set_mtime(mtime);
                header.set_cksum();

                let mut reader = BufReader::with_capacity(BUFFER_SIZE, File::open(entry.path())?);
                tar.append_data(&mut header, rel, &mut reader)?;
                written_bytes += meta.len() as i64;
                if on_progress(written_bytes, total_bytes, &name).is_err() {
                    return Err("Operation cancelled".into());
                }
            }
        }
    }
    tar.finish()?;
    Ok(written_bytes)
}
