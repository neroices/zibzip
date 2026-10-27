mod format;
mod rar;
mod sevenz;
mod single;
mod tar;
mod zip;

#[cfg(test)]
mod tests;

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering as AtomicOrdering};
use walkdir::WalkDir;

pub use format::{ArchiveEntryData, Format, detect_format, strip_format_suffix};
pub use zip::{add_zip_entries, remove_zip_entries};

pub const BUFFER_SIZE: usize = 256 * 1024;

pub(crate) struct CountingReader<R> {
    pub(crate) inner: R,
    pub(crate) bytes_read: std::sync::Arc<std::sync::atomic::AtomicI64>,
}

impl<R: std::io::Read> std::io::Read for CountingReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        self.bytes_read.fetch_add(n as i64, std::sync::atomic::Ordering::Relaxed);
        Ok(n)
    }
}

pub fn list_entries(
    archive_path: &Path,
    password: Option<&str>,
) -> Result<Vec<ArchiveEntryData>, Box<dyn std::error::Error + Send + Sync>> {
    let file_name = archive_path
        .file_name()
        .map(|n| n.to_string_lossy())
        .unwrap_or_default();
    let format = detect_format(&file_name);

    match format {
        Format::Zip => zip::list_zip(archive_path),
        Format::SevenZ => sevenz::list_sevenz(archive_path, password),
        Format::Tar => tar::list_tar(archive_path, format),
        Format::TarGz | Format::TarBz2 | Format::TarXz | Format::TarZst => {
            tar::list_tar(archive_path, format)
        }
        Format::Gz | Format::Bz2 | Format::Xz | Format::Zst => {
            let meta = fs::metadata(archive_path)?;
            let stem = strip_format_suffix(&file_name, format);
            let name = if stem.is_empty() { "output" } else { stem };
            let mod_time = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);

            Ok(vec![ArchiveEntryData {
                path: name.to_string(),
                is_directory: false,
                size: -1,
                compressed_size: meta.len() as i64,
                last_modified: mod_time,
                is_encrypted: false,
            }])
        }
        Format::Rar => rar::list_rar(archive_path),
        Format::Unknown => Err(format!("Unsupported archive format: {}", file_name).into()),
    }
}

pub fn extract_archive(
    archive_path: &Path,
    dest_dir: &Path,
    selection: Option<&[String]>,
    base_path: &str,
    password: Option<&str>,
    conflict_policy: i32, // 0: KeepBoth, 1: Overwrite, 2: Skip
    cancel_token: *const AtomicBool,
    on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    fs::create_dir_all(dest_dir)?;
    let file_name = archive_path
        .file_name()
        .map(|n| n.to_string_lossy())
        .unwrap_or_default();
    let format = detect_format(&file_name);

    let base = base_path.trim_matches('/');

    let match_fn = move |path: &str| -> bool {
        if let Some(sel) = selection {
            for s in sel {
                let s_clean = s.trim_matches('/');
                if path == s_clean || path.starts_with(&format!("{}/", s_clean)) {
                    return true;
                }
            }
            false
        } else {
            true
        }
    };

    let rel_fn = move |path: &str| -> String {
        if base.is_empty() {
            path.to_string()
        } else if let Some(stripped) = path.strip_prefix(base) {
            stripped.trim_matches('/').to_string()
        } else {
            path.to_string()
        }
    };

    let resolve_target = |rel_path: &str| -> Option<PathBuf> {
        if rel_path.is_empty() {
            return None;
        }
        let rel = Path::new(rel_path);
        // Prevent zip-slip traversal by rejecting parent, root, or prefix components
        for comp in rel.components() {
            match comp {
                std::path::Component::Normal(_) | std::path::Component::CurDir => {}
                _ => return None,
            }
        }
        let target = dest_dir.join(rel);

        if !target.exists() {
            return Some(target);
        }
        if target.is_dir() {
            return Some(target);
        }

        match conflict_policy {
            1 => Some(target), // Overwrite
            2 => None,         // Skip
            _ => {
                // KeepBoth
                let parent = target.parent().unwrap_or(dest_dir);
                let stem = target
                    .file_stem()
                    .map(|s| s.to_string_lossy())
                    .unwrap_or_default();
                let ext = target
                    .extension()
                    .map(|e| format!(".{}", e.to_string_lossy()))
                    .unwrap_or_default();
                let mut idx = 1;
                loop {
                    let candidate = parent.join(format!("{} ({}){}", stem, idx, ext));
                    if !candidate.exists() {
                        return Some(candidate);
                    }
                    idx += 1;
                }
            }
        }
    };

    match format {
        Format::Zip => zip::extract_zip(
            archive_path,
            dest_dir,
            password,
            &match_fn,
            &rel_fn,
            &resolve_target,
            cancel_token,
            on_progress,
        ),
        Format::SevenZ => sevenz::extract_sevenz(
            archive_path,
            dest_dir,
            password,
            &match_fn,
            &rel_fn,
            &resolve_target,
            cancel_token,
            on_progress,
        ),
        Format::Tar | Format::TarGz | Format::TarBz2 | Format::TarXz | Format::TarZst => {
            tar::extract_tar(
                archive_path,
                dest_dir,
                format,
                &match_fn,
                &rel_fn,
                &resolve_target,
                cancel_token,
                on_progress,
            )
        }
        Format::Gz | Format::Bz2 | Format::Xz | Format::Zst => single::extract_single(
            archive_path,
            dest_dir,
            format,
            &rel_fn,
            &resolve_target,
            cancel_token,
            on_progress,
        ),
        Format::Rar => rar::extract_rar(
            archive_path,
            dest_dir,
            password,
            &match_fn,
            &rel_fn,
            &resolve_target,
            cancel_token,
            on_progress,
        ),
        Format::Unknown => Err(format!("Unsupported format: {}", file_name).into()),
    }
}

pub fn create_archive(
    sources: &[PathBuf],
    output_path: &Path,
    format_str: &str,
    level: i32,
    password: Option<&str>,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    let parent = output_path
        .parent()
        .ok_or("Invalid destination directory")?;
    fs::create_dir_all(parent)?;

    let part_path = output_path.with_extension(format!(
        "{}.part",
        output_path
            .extension()
            .map(|e| e.to_string_lossy())
            .unwrap_or_default()
    ));

    let mut total_bytes = 0i64;
    for src in sources {
        for entry in WalkDir::new(src) {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err("Operation cancelled".into());
            }
            if let Ok(entry) = entry {
                let p = entry.path();
                if p == output_path || p == part_path {
                    continue;
                }
                if entry.file_type().is_file()
                    && let Ok(meta) = entry.metadata() {
                        total_bytes += meta.len() as i64;
                    }
            }
        }
    }

    let result = match format_str.to_ascii_uppercase().as_str() {
        "ZIP" => zip::create_zip(
            sources,
            output_path,
            &part_path,
            level,
            password,
            total_bytes,
            cancel_token,
            &mut on_progress,
        ),
        "7Z" => sevenz::create_sevenz(
            sources,
            output_path,
            &part_path,
            total_bytes,
            cancel_token,
            &mut on_progress,
        ),
        "TAR" | "TAR.GZ" | "TAR.BZ2" | "TAR.XZ" | "TAR.ZST" => tar::create_tar(
            sources,
            output_path,
            &part_path,
            format_str,
            level,
            total_bytes,
            cancel_token,
            &mut on_progress,
        ),
        "GZ" | "BZ2" | "XZ" | "ZST" => single::create_single(
            sources,
            &part_path,
            format_str,
            level,
            total_bytes,
            cancel_token,
            &mut on_progress,
        ),
        "RAR" => rar::create_rar(
            sources,
            output_path,
            &part_path,
            level,
            password,
            total_bytes,
            cancel_token,
            &mut on_progress,
        ),
        _ => Err(format!("Unsupported format: {}", format_str).into()),
    };

    match result {
        Ok(bytes) => {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                let _ = fs::remove_file(&part_path);
                return Err("Operation cancelled".into());
            }
            fs::rename(&part_path, output_path)?;
            Ok(bytes)
        }
        Err(e) => {
            let _ = fs::remove_file(&part_path);
            Err(e)
        }
    }
}
