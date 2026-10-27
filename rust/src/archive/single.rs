use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering as AtomicOrdering};

use super::{BUFFER_SIZE, CountingReader};
use super::format::{Format, strip_format_suffix};

pub(crate) fn extract_single(
    archive_path: &Path,
    _dest_dir: &Path,
    format: Format,
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

    let mut stream: Box<dyn Read> = match format {
        Format::Gz => Box::new(GzDecoder::new(counting)),
        Format::Bz2 => Box::new(bzip2::read::BzDecoder::new(counting)),
        Format::Xz => Box::new(lzma_rust2::XzReader::new(counting, true)),
        Format::Zst => Box::new(zstd::Decoder::new(counting)?),
        _ => return Err("Not a single-file format".into()),
    };

    let file_name = archive_path
        .file_name()
        .map(|n| n.to_string_lossy())
        .unwrap_or_default();
    let stem = strip_format_suffix(&file_name, format);
    let name = if stem.is_empty() { "output" } else { stem };
    let rel = rel_fn(name);
    let target = match resolve_target(&rel) {
        Some(t) => t,
        None => return Ok(0),
    };

    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }

    let mut writer = BufWriter::with_capacity(BUFFER_SIZE, File::create(&target)?);
    let mut buf = vec![0u8; BUFFER_SIZE];

    loop {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            let _ = fs::remove_file(&target);
            return Err("Operation cancelled".into());
        }

        let n = stream.read(&mut buf)?;
        if n == 0 {
            break;
        }
        writer.write_all(&buf[..n])?;
        let current_done = bytes_read.load(AtomicOrdering::Relaxed).min(total_bytes);
        if on_progress(current_done, total_bytes, name).is_err() {
            let _ = fs::remove_file(&target);
            return Err("Operation cancelled".into());
        }
    }
    writer.flush()?;
    let _ = on_progress(total_bytes, total_bytes, name);
    Ok(1)
}

fn copy_single_payload<W: Write>(
    reader: &mut impl Read,
    writer: &mut W,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    name: &str,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    let mut buf = vec![0u8; BUFFER_SIZE];
    let mut written_bytes = 0i64;
    loop {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err("Operation cancelled".into());
        }
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        writer.write_all(&buf[..n])?;
        written_bytes += n as i64;
        let _ = on_progress(written_bytes, total_bytes, name);
    }
    Ok(written_bytes)
}

pub(crate) fn create_single(
    sources: &[PathBuf],
    part_path: &Path,
    format_str: &str,
    level: i32,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    let src = sources.first().ok_or("No source file provided")?;
    if !src.is_file() {
        return Err("Single-file formats only accept a single file".into());
    }

    let file = File::create(part_path)?;
    let writer = BufWriter::with_capacity(BUFFER_SIZE, file);
    let name = src
        .file_name()
        .map(|n| n.to_string_lossy())
        .unwrap_or_default();
    let mut reader = BufReader::with_capacity(BUFFER_SIZE, File::open(src)?);

    match format_str.to_ascii_uppercase().as_str() {
        "GZ" => {
            let comp = if level == 0 {
                Compression::none()
            } else {
                Compression::new(level.clamp(1, 9) as u32)
            };
            let mut enc = GzEncoder::new(writer, comp);
            let written = copy_single_payload(
                &mut reader,
                &mut enc,
                total_bytes,
                cancel_token,
                &name,
                on_progress,
            )?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        "BZ2" => {
            let mut enc = bzip2::write::BzEncoder::new(
                writer,
                bzip2::Compression::new(level.clamp(1, 9) as u32),
            );
            let written = copy_single_payload(
                &mut reader,
                &mut enc,
                total_bytes,
                cancel_token,
                &name,
                on_progress,
            )?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        "ZST" => {
            let mut enc = zstd::Encoder::new(writer, level.clamp(1, 9))?;
            let written = copy_single_payload(
                &mut reader,
                &mut enc,
                total_bytes,
                cancel_token,
                &name,
                on_progress,
            )?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        "XZ" => {
            let mut enc = lzma_rust2::XzWriter::new(
                writer,
                lzma_rust2::XzOptions::with_preset(level.clamp(0, 9) as u32),
            )?;
            let written = copy_single_payload(
                &mut reader,
                &mut enc,
                total_bytes,
                cancel_token,
                &name,
                on_progress,
            )?;
            let mut w = enc.finish()?;
            w.flush()?;
            Ok(written)
        }
        _ => Err("Not a single-file format".into()),
    }
}
