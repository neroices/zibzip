use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering as AtomicOrdering};
use walkdir::WalkDir;

use super::format::{ArchiveEntryData, normalize_entry_path};

pub(crate) fn list_rar(
    archive_path: &Path,
) -> Result<Vec<ArchiveEntryData>, Box<dyn std::error::Error + Send + Sync>> {
    let archive = rars::ArchiveReader::read_path(archive_path)
        .map_err(|e| format!("Corrupted archive: {}", e))?;
    let mut out = Vec::new();

    for member in archive.members() {
        let name = String::from_utf8_lossy(&member.meta.name).replace('\\', "/");
        let norm = normalize_entry_path(&name);
        if norm.is_empty() {
            continue;
        }
        let is_dir = member.meta.is_directory;
        let path = if is_dir && !norm.ends_with('/') {
            format!("{}/", norm)
        } else {
            norm
        };
        let last_modified = member.meta.file_time.unwrap_or(0) as i64 * 1000;
        out.push(ArchiveEntryData {
            path,
            is_directory: is_dir,
            size: member.meta.unpacked_size as i64,
            compressed_size: member.meta.packed_size as i64,
            last_modified,
            is_encrypted: member.meta.is_encrypted,
        });
    }

    Ok(out)
}

pub(crate) fn extract_rar(
    archive_path: &Path,
    _dest_dir: &Path,
    password: Option<&str>,
    match_fn: &impl Fn(&str) -> bool,
    rel_fn: &impl Fn(&str) -> String,
    resolve_target: &impl Fn(&str) -> Option<PathBuf>,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i32, Box<dyn std::error::Error + Send + Sync>> {
    let archive = rars::ArchiveReader::read_path(archive_path)
        .map_err(|e| format!("Failed to open RAR archive: {}", e))?;

    let mut total_bytes = 0i64;
    let mut member_sizes = std::collections::HashMap::new();
    for member in archive.members() {
        let name = String::from_utf8_lossy(&member.meta.name).replace('\\', "/");
        let norm = normalize_entry_path(&name);
        if !member.meta.is_directory && match_fn(&norm) {
            let sz = member.meta.unpacked_size as i64;
            total_bytes += sz;
            member_sizes.insert(norm, sz);
        }
    }

    let mut done_bytes = 0i64;
    let mut extracted_count = 0i32;
    let pwd_bytes = password.map(|p| p.as_bytes());

    archive
        .extract_to(
            pwd_bytes,
            |meta| -> Result<Box<dyn Write>, rars::error::Error> {
                if !cancel_token.is_null()
                    && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                {
                    return Err(rars::error::Error::from(std::io::Error::new(
                        std::io::ErrorKind::Interrupted,
                        "Operation cancelled",
                    )));
                }

                let raw_name = String::from_utf8_lossy(&meta.name).replace('\\', "/");
                if raw_name.contains("..") {
                    return Err(rars::error::Error::from(std::io::Error::new(
                        std::io::ErrorKind::InvalidInput,
                        "Zip-slip path traversal is unsupported",
                    )));
                }
                let norm = normalize_entry_path(&raw_name);
                if norm.contains("..") {
                    return Err(rars::error::Error::from(std::io::Error::new(
                        std::io::ErrorKind::InvalidInput,
                        "Zip-slip path traversal is unsupported",
                    )));
                }

                if !match_fn(&norm) {
                    return Ok(Box::new(std::io::sink()));
                }

                let rel = rel_fn(&norm);
                if let Some(target) = resolve_target(&rel) {
                    if meta.is_directory {
                        let _ = fs::create_dir_all(&target);
                        Ok(Box::new(std::io::sink()))
                    } else {
                        if let Some(parent) = target.parent() {
                            let _ = fs::create_dir_all(parent);
                        }
                        match File::create(&target) {
                            Ok(f) => {
                                extracted_count += 1;
                                if let Some(sz) = member_sizes.get(&norm) {
                                    done_bytes += *sz;
                                }
                                let _ = on_progress(done_bytes, total_bytes, &rel);
                                Ok(Box::new(f))
                            }
                            Err(e) => Err(rars::error::Error::from(e)),
                        }
                    }
                } else {
                    Ok(Box::new(std::io::sink()))
                }
            },
        )
        .map_err(|e| format!("RAR extraction error: {}", e))?;

    Ok(extracted_count)
}

pub(crate) fn create_rar(
    sources: &[PathBuf],
    output_path: &Path,
    level: i32,
    password: Option<&str>,
    total_bytes: i64,
    cancel_token: *const AtomicBool,
    on_progress: &mut impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, Box<dyn std::error::Error + Send + Sync>> {
    let mut builder = rars::Builder::new(rars::version::ArchiveVersion::Rar50);
    if let Some(pwd) = password
        && !pwd.is_empty() {
            builder = builder.password(Some(pwd.as_bytes().to_vec()));
        }
    if level == 0 {
        builder = builder.store(true);
    } else {
        let rlevel = match level {
            1..=2 => 1,
            3..=4 => 2,
            5..=6 => 3,
            7..=8 => 4,
            _ => 5,
        };
        builder = builder.compression_level(Some(rlevel));
    }

    let mut seen_entries = std::collections::HashSet::new();

    for src in sources {
        let parent = src.parent().unwrap_or(Path::new(""));
        for entry in WalkDir::new(src) {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err("Operation cancelled".into());
            }
            let entry = entry?;
            let path = entry.path();
            if path == output_path {
                continue;
            }
            let rel = path.strip_prefix(parent).unwrap_or(path);
            let name_str = rel.to_string_lossy().replace('\\', "/");
            let clean_name = name_str.trim_matches('/').to_string();
            if clean_name.is_empty() || clean_name == "." {
                continue;
            }
            let name_bytes = clean_name.as_bytes().to_vec();

            if !seen_entries.insert(name_bytes.clone()) {
                continue;
            }

            let meta = entry.metadata().ok();
            let mtime = meta.as_ref().and_then(|m| m.modified().ok()).and_then(|t| {
                t.duration_since(std::time::UNIX_EPOCH)
                    .ok()
                    .map(|d| d.as_secs() as u32)
            });

            if entry.file_type().is_dir() {
                builder
                    .add_directory(name_bytes, mtime, None)
                    .map_err(|e| format!("Failed to add directory to RAR: {}", e))?;
            } else if entry.file_type().is_file() {
                builder
                    .add_source(name_bytes, rars::EntrySource::from_path(path), mtime, None)
                    .map_err(|e| format!("Failed to add file to RAR: {}", e))?;
            }
        }
    }

    if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
        return Err("Operation cancelled".into());
    }

    struct RarWriteProgress<'a, F> {
        cancel_token: *const AtomicBool,
        cancelled_by_cb: AtomicBool,
        main_thread_id: std::thread::ThreadId,
        total_bytes: i64,
        latest_done: AtomicI64,
        latest_total: AtomicI64,
        current_name: std::sync::Mutex<String>,
        on_progress: std::sync::Mutex<&'a mut F>,
    }

    unsafe impl<'a, F> Send for RarWriteProgress<'a, F> {}
    unsafe impl<'a, F> Sync for RarWriteProgress<'a, F> {}

    impl<'a, F> RarWriteProgress<'a, F>
    where
        F: FnMut(i64, i64, &str) -> Result<(), ()>,
    {
        fn flush_on_main(&self) {
            if std::thread::current().id() == self.main_thread_id {
                let done = self.latest_done.load(AtomicOrdering::Relaxed);
                let total = self.latest_total.load(AtomicOrdering::Relaxed);
                let cur = self
                    .current_name
                    .lock()
                    .map(|s| s.clone())
                    .unwrap_or_default();
                if let Ok(mut cb) = self.on_progress.lock()
                    && (*cb)(done, total, &cur).is_err() {
                        self.cancelled_by_cb.store(true, AtomicOrdering::Relaxed);
                    }
            }
        }
    }

    impl<'a, F> rars::WriteProgress for RarWriteProgress<'a, F>
    where
        F: FnMut(i64, i64, &str) -> Result<(), ()>,
    {
        fn report(&self, event: rars::WriteProgressEvent<'_>) {
            match event {
                rars::WriteProgressEvent::EntryStarted { name, .. } => {
                    let name_str = String::from_utf8_lossy(name);
                    let simple = name_str
                        .rsplit(['/', '\\'])
                        .next()
                        .unwrap_or(&name_str)
                        .to_string();
                    if let Ok(mut cur) = self.current_name.lock() {
                        *cur = simple;
                    }
                    self.flush_on_main();
                }
                rars::WriteProgressEvent::Advanced {
                    completed_bytes,
                    total_bytes: adv_total,
                    ..
                } => {
                    let tot = if adv_total > 0 {
                        adv_total as i64
                    } else {
                        self.total_bytes
                    };
                    self.latest_done
                        .store(completed_bytes as i64, AtomicOrdering::Relaxed);
                    self.latest_total.store(tot, AtomicOrdering::Relaxed);
                    self.flush_on_main();
                }
                rars::WriteProgressEvent::BytesWritten { completed_bytes } => {
                    self.latest_done
                        .store(completed_bytes as i64, AtomicOrdering::Relaxed);
                    self.flush_on_main();
                }
                _ => {}
            }
        }

        fn is_cancelled(&self) -> bool {
            if self.cancelled_by_cb.load(AtomicOrdering::Relaxed) {
                return true;
            }
            if !self.cancel_token.is_null()
                && unsafe { (*self.cancel_token).load(AtomicOrdering::Relaxed) }
            {
                return true;
            }
            self.flush_on_main();
            self.cancelled_by_cb.load(AtomicOrdering::Relaxed)
        }
    }

    let progress_reporter = RarWriteProgress {
        cancel_token,
        cancelled_by_cb: AtomicBool::new(false),
        main_thread_id: std::thread::current().id(),
        total_bytes,
        latest_done: AtomicI64::new(0),
        latest_total: AtomicI64::new(total_bytes),
        current_name: std::sync::Mutex::new(String::new()),
        on_progress: std::sync::Mutex::new(on_progress),
    };

    let write_res = builder.write_to_path(output_path, Some(&progress_reporter));

    if progress_reporter
        .cancelled_by_cb
        .load(AtomicOrdering::Relaxed)
        || (!cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) })
    {
        return Err("Operation cancelled".into());
    }

    match write_res {
        Ok(_) => {
            let out_meta = fs::metadata(output_path)?;
            Ok(out_meta.len() as i64)
        }
        Err(e) => {
            if matches!(e, rars::Error::Cancelled) {
                Err("Operation cancelled".into())
            } else {
                Err(format!("Failed to write RAR archive: {}", e).into())
            }
        }
    }
}
