use rayon::slice::ParallelSliceMut;
use std::cmp::Ordering;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering as AtomicOrdering};
use std::time::UNIX_EPOCH;
use walkdir::WalkDir;

const BUFFER_SIZE: usize = 256 * 1024;

#[derive(Clone, Debug)]
pub struct DirEntryItem {
    pub path: String,
    pub name: String,
    pub is_directory: bool,
    pub size: i64,
    pub last_modified: i64,
    pub is_hidden: bool,
}

pub fn natural_cmp(a: &str, b: &str) -> Ordering {
    let a_bytes = a.as_bytes();
    let b_bytes = b.as_bytes();
    let mut i = 0;
    let mut j = 0;

    while i < a_bytes.len() && j < b_bytes.len() {
        let ca = a_bytes[i];
        let cb = b_bytes[j];

        if ca.is_ascii_digit() && cb.is_ascii_digit() {
            let mut si = i;
            while si < a_bytes.len() && a_bytes[si] == b'0' {
                si += 1;
            }
            let mut sj = j;
            while sj < b_bytes.len() && b_bytes[sj] == b'0' {
                sj += 1;
            }

            let mut ei = si;
            while ei < a_bytes.len() && a_bytes[ei].is_ascii_digit() {
                ei += 1;
            }
            let mut ej = sj;
            while ej < b_bytes.len() && b_bytes[ej].is_ascii_digit() {
                ej += 1;
            }

            let len_a = ei - si;
            let len_b = ej - sj;

            if len_a != len_b {
                return len_a.cmp(&len_b);
            }

            for k in 0..len_a {
                let digit_a = a_bytes[si + k];
                let digit_b = b_bytes[sj + k];
                if digit_a != digit_b {
                    return digit_a.cmp(&digit_b);
                }
            }

            let orig_len_a = ei - i;
            let orig_len_b = ej - j;
            if orig_len_a != orig_len_b {
                return orig_len_a.cmp(&orig_len_b);
            }

            i = ei;
            j = ej;
        } else {
            let low_a = ca.to_ascii_lowercase();
            let low_b = cb.to_ascii_lowercase();
            if low_a != low_b {
                return low_a.cmp(&low_b);
            }
            i += 1;
            j += 1;
        }
    }

    (a_bytes.len() - i).cmp(&(b_bytes.len() - j))
}

pub fn sort_entries(
    entries: &mut [DirEntryItem],
    sort_field: i32,
    ascending: bool,
    folders_first: bool,
) {
    entries.par_sort_unstable_by(|a, b| {
        if folders_first && a.is_directory != b.is_directory {
            return if a.is_directory {
                Ordering::Less
            } else {
                Ordering::Greater
            };
        }

        let base_order = match sort_field {
            1 => a
                .size
                .cmp(&b.size)
                .then_with(|| natural_cmp(&a.name, &b.name)),
            2 => a
                .last_modified
                .cmp(&b.last_modified)
                .then_with(|| natural_cmp(&a.name, &b.name)),
            3 => {
                let ext_a = a.name.rfind('.').map(|idx| &a.name[idx + 1..]).unwrap_or("").to_ascii_lowercase();
                let ext_b = b.name.rfind('.').map(|idx| &b.name[idx + 1..]).unwrap_or("").to_ascii_lowercase();
                ext_a
                    .cmp(&ext_b)
                    .then_with(|| natural_cmp(&a.name, &b.name))
            }
            _ => natural_cmp(&a.name, &b.name),
        };

        if ascending {
            base_order
        } else {
            base_order.reverse()
        }
    });
}

pub fn list_directory(
    dir_path: &Path,
    show_hidden: bool,
    sort_field: i32,
    ascending: bool,
    folders_first: bool,
) -> Result<Vec<DirEntryItem>, std::io::Error> {
    let read_dir = fs::read_dir(dir_path)?;
    let mut items = Vec::with_capacity(128);

    for entry in read_dir {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let file_name = entry.file_name();
        let name_str = file_name.to_string_lossy();
        let is_hidden = name_str.starts_with('.');

        if is_hidden && !show_hidden {
            continue;
        }

        let (is_dir, size, modified) = match entry.metadata() {
            Ok(metadata) => {
                let is_dir = metadata.is_dir();
                let size = if is_dir { 0 } else { metadata.len() as i64 };
                let modified = metadata
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as i64)
                    .unwrap_or(0);
                (is_dir, size, modified)
            }
            Err(_) => {
                if let Ok(symlink_meta) = fs::symlink_metadata(entry.path()) {
                    let modified = symlink_meta
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                        .map(|d| d.as_millis() as i64)
                        .unwrap_or(0);
                    (false, 0, modified)
                } else {
                    continue;
                }
            }
        };

        items.push(DirEntryItem {
            path: entry.path().to_string_lossy().into_owned(),
            name: name_str.into_owned(),
            is_directory: is_dir,
            size,
            last_modified: modified,
            is_hidden,
        });
    }

    if sort_field >= 0 {
        sort_entries(&mut items, sort_field, ascending, folders_first);
    }
    Ok(items)
}

pub fn search_files(
    root: &Path,
    query: &str,
    extensions: &[String],
    show_hidden: bool,
    limit: usize,
    cancel_token: *const AtomicBool,
) -> Result<Vec<DirEntryItem>, std::io::Error> {
    let query_trimmed = query.trim();
    let query_lower = query_trimmed.to_ascii_lowercase();
    let exts_lower: Vec<String> = extensions
        .iter()
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty())
        .collect();
    let has_query = !query_trimmed.is_empty();
    let has_exts = !exts_lower.is_empty();
    let mut results = Vec::new();

    let walker = WalkDir::new(root).into_iter();
    for entry in walker.filter_entry(|e| {
        let file_name = e.file_name().to_string_lossy();
        if !show_hidden && file_name.starts_with('.') {
            return false;
        }
        if file_name == ".zibzip_trash"
            || file_name == ".Trash"
            || file_name == ".trash"
            || file_name.starts_with(".Trash-")
        {
            return false;
        }
        if e.file_type().is_dir() {
            let path_str = e.path().to_string_lossy();
            if path_str.ends_with("/Android/data") || path_str.ends_with("/Android/obb") {
                return false;
            }
        }
        true
    }) {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err(std::io::Error::new(
                std::io::ErrorKind::Interrupted,
                "Operation cancelled",
            ));
        }

        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };

        if entry.path() == root {
            continue;
        }

        let name = entry.file_name().to_string_lossy();
        let name_lower = name.to_ascii_lowercase();

        if has_query && !name_lower.contains(&query_lower) {
            continue;
        }

        if has_exts {
            let matches_ext = exts_lower.iter().any(|ext| {
                if ext.starts_with('.') {
                    name_lower.ends_with(ext)
                } else {
                    name_lower.ends_with(&format!(".{}", ext))
                }
            });
            if !matches_ext {
                continue;
            }
        }

        let metadata = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let is_dir = metadata.is_dir();
        if has_exts && is_dir {
            continue;
        }

        let size = if is_dir { 0 } else { metadata.len() as i64 };
        let modified = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);

        results.push(DirEntryItem {
            path: entry.path().to_string_lossy().into_owned(),
            name: name.into_owned(),
            is_directory: is_dir,
            size,
            last_modified: modified,
            is_hidden: entry
                .file_name()
                .to_str()
                .map(|s| s.starts_with('.'))
                .unwrap_or(false),
        });

        if results.len() >= limit {
            break;
        }
    }

    Ok(results)
}

pub fn folder_stats(
    dir: &Path,
    cancel_token: *const AtomicBool,
) -> Result<[i64; 3], std::io::Error> {
    let mut total_bytes = 0i64;
    let mut file_count = 0i64;
    let mut folder_count = 0i64;

    for entry in WalkDir::new(dir).min_depth(1) {
        if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
            return Err(std::io::Error::new(
                std::io::ErrorKind::Interrupted,
                "Operation cancelled",
            ));
        }
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        if meta.is_dir() {
            folder_count += 1;
        } else {
            file_count += 1;
            total_bytes += meta.len() as i64;
        }
    }

    Ok([total_bytes, file_count, folder_count])
}

fn unique_child_path(dest_dir: &Path, name: &std::ffi::OsStr) -> PathBuf {
    let base = dest_dir.join(name);
    if base.symlink_metadata().is_err() {
        return base;
    }
    let name_str = name.to_string_lossy();
    let lower = name_str.to_ascii_lowercase();
    let compound_exts = [
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
        ".tgz", ".tbz2", ".tbz", ".txz",
    ];
    let (stem, ext) = if let Some(&sfx) = compound_exts.iter().find(|&&ext| lower.ends_with(ext)) {
        let stem_len = name_str.len() - sfx.len();
        (&name_str[..stem_len], &name_str[stem_len..])
    } else {
        match name_str.rfind('.') {
            Some(idx) if idx > 0 => (&name_str[..idx], &name_str[idx..]),
            _ => (name_str.as_ref(), ""),
        }
    };
    for i in 1..10000 {
        let candidate = dest_dir.join(format!("{} ({}){}", stem, i, ext));
        if candidate.symlink_metadata().is_err() {
            return candidate;
        }
    }
    base
}

pub fn copy_files(
    sources: &[PathBuf],
    dest_dir: &Path,
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, std::io::Error> {
    fs::create_dir_all(dest_dir)?;

    let mut total_bytes = 0i64;
    for src in sources {
        for entry in WalkDir::new(src) {
            if let Ok(entry) = entry
                && entry.file_type().is_file()
                    && let Ok(meta) = entry.metadata() {
                        total_bytes += meta.len() as i64;
                    }
        }
    }

    let mut copied_bytes = 0i64;
    let mut buf = vec![0u8; BUFFER_SIZE];

    for src in sources {
        let src_name = match src.file_name() {
            Some(n) => n,
            None => continue,
        };

        if src.is_dir() {
            let target_root = unique_child_path(dest_dir, src_name);
            fs::create_dir_all(&target_root)?;

            for entry in WalkDir::new(src).min_depth(1) {
                if !cancel_token.is_null()
                    && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Interrupted,
                        "Operation cancelled",
                    ));
                }

                let entry = entry?;
                let rel = entry.path().strip_prefix(src).unwrap_or(entry.path());
                let target_path = target_root.join(rel);

                if entry.file_type().is_dir() {
                    fs::create_dir_all(&target_path)?;
                } else if entry.file_type().is_symlink() {
                    if let Some(parent) = target_path.parent() {
                        fs::create_dir_all(parent)?;
                    }
                    #[cfg(unix)]
                    if let Ok(link_dest) = fs::read_link(entry.path()) {
                        let _ = fs::remove_file(&target_path);
                        let _ = std::os::unix::fs::symlink(&link_dest, &target_path);
                    }
                } else if entry.file_type().is_file() {
                    if let Some(parent) = target_path.parent() {
                        fs::create_dir_all(parent)?;
                    }

                    let file_name_str = entry.file_name().to_string_lossy();
                    let mut reader = File::open(entry.path())?;
                    let mut writer = File::create(&target_path)?;

                    loop {
                        if !cancel_token.is_null()
                            && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                        {
                            let _ = fs::remove_file(&target_path);
                            return Err(std::io::Error::new(
                                std::io::ErrorKind::Interrupted,
                                "Operation cancelled",
                            ));
                        }
                        let n = reader.read(&mut buf)?;
                        if n == 0 {
                            break;
                        }
                        writer.write_all(&buf[..n])?;
                        copied_bytes += n as i64;
                        if on_progress(copied_bytes, total_bytes, &file_name_str).is_err() {
                            let _ = fs::remove_file(&target_path);
                            return Err(std::io::Error::new(
                                std::io::ErrorKind::Interrupted,
                                "Operation cancelled",
                            ));
                        }
                    }
                }
            }
        } else if src.is_file() {
            let target_path = unique_child_path(dest_dir, src_name);
            let file_name_str = src_name.to_string_lossy();
            let mut reader = File::open(src)?;
            let mut writer = File::create(&target_path)?;

            loop {
                if !cancel_token.is_null()
                    && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                {
                    let _ = fs::remove_file(&target_path);
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Interrupted,
                        "Operation cancelled",
                    ));
                }
                let n = reader.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                writer.write_all(&buf[..n])?;
                copied_bytes += n as i64;
                if on_progress(copied_bytes, total_bytes, &file_name_str).is_err() {
                    let _ = fs::remove_file(&target_path);
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Interrupted,
                        "Operation cancelled",
                    ));
                }
            }
        }
    }

    Ok(copied_bytes)
}

pub fn delete_files(
    paths: &[PathBuf],
    cancel_token: *const AtomicBool,
    mut on_progress: impl FnMut(i64, i64, &str) -> Result<(), ()>,
) -> Result<i64, std::io::Error> {
    let mut total_items = 0i64;
    for p in paths {
        if let Ok(meta) = p.symlink_metadata() {
            if meta.file_type().is_symlink() {
                total_items += 1;
            } else {
                for _ in WalkDir::new(p) {
                    total_items += 1;
                }
            }
        }
    }

    let mut deleted = 0i64;
    for p in paths {
        let meta = match p.symlink_metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        if meta.file_type().is_symlink() {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
                    "Operation cancelled",
                ));
            }
            let name = p
                .file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default();
            fs::remove_file(p)?;
            deleted += 1;
            if on_progress(deleted, total_items, &name).is_err() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
                    "Operation cancelled",
                ));
            }
        } else if meta.is_dir() {
            for entry in WalkDir::new(p).contents_first(true) {
                if !cancel_token.is_null()
                    && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) }
                {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Interrupted,
                        "Operation cancelled",
                    ));
                }
                let entry = entry.map_err(std::io::Error::other)?;
                let path = entry.path();
                let name = path
                    .file_name()
                    .map(|n| n.to_string_lossy())
                    .unwrap_or_default();
                if entry.file_type().is_dir() {
                    fs::remove_dir(path)?;
                } else {
                    fs::remove_file(path)?;
                }
                deleted += 1;
                if on_progress(deleted, total_items, &name).is_err() {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Interrupted,
                        "Operation cancelled",
                    ));
                }
            }
        } else {
            if !cancel_token.is_null() && unsafe { (*cancel_token).load(AtomicOrdering::Relaxed) } {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
                    "Operation cancelled",
                ));
            }
            let name = p
                .file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default();
            fs::remove_file(p)?;
            deleted += 1;
            if on_progress(deleted, total_items, &name).is_err() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
                    "Operation cancelled",
                ));
            }
        }
    }

    Ok(deleted)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[cfg(unix)]
    fn test_copy_files_preserves_symlinks() {
        let temp_dir = std::env::temp_dir().join(format!(
            "zibzip_test_copy_symlink_{}",
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let src_dir = temp_dir.join("src");
        fs::create_dir_all(&src_dir).unwrap();
        let target_file = src_dir.join("real.txt");
        fs::write(&target_file, b"content").unwrap();
        let symlink_path = src_dir.join("link.txt");
        std::os::unix::fs::symlink("real.txt", &symlink_path).unwrap();

        let dest_dir = temp_dir.join("dest");
        fs::create_dir_all(&dest_dir).unwrap();

        let res = copy_files(
            &[src_dir],
            &dest_dir,
            std::ptr::null(),
            |_, _, _| Ok(()),
        );
        assert!(res.is_ok());

        let copied_link = dest_dir.join("src/link.txt");
        let meta = fs::symlink_metadata(&copied_link).expect("symlink should exist at dest");
        assert!(meta.file_type().is_symlink());
        assert_eq!(fs::read_link(&copied_link).unwrap(), PathBuf::from("real.txt"));

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    #[cfg(unix)]
    fn test_list_directory_with_broken_symlink() {
        let temp_dir = std::env::temp_dir().join(format!(
            "zibzip_test_list_symlink_{}",
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&temp_dir).unwrap();
        let broken_link = temp_dir.join("broken.txt");
        std::os::unix::fs::symlink("nonexistent_target.txt", &broken_link).unwrap();

        let entries = list_directory(&temp_dir, true, 0, true, true)
            .expect("list_directory should succeed in presence of broken symlinks");
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "broken.txt");
        assert!(!entries[0].is_directory);

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    #[cfg(unix)]
    fn test_unique_child_path_with_broken_symlink() {
        let temp_dir = std::env::temp_dir().join(format!(
            "zibzip_test_unique_symlink_{}",
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&temp_dir).unwrap();
        let broken_link = temp_dir.join("report.txt");
        std::os::unix::fs::symlink("missing.txt", &broken_link).unwrap();

        let unique = unique_child_path(&temp_dir, std::ffi::OsStr::new("report.txt"));
        assert_eq!(unique, temp_dir.join("report (1).txt"));

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    #[cfg(unix)]
    fn test_delete_files_removes_broken_symlink_and_preserves_target_dir() {
        let temp_dir = std::env::temp_dir().join(format!(
            "zibzip_test_delete_symlink_{}",
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&temp_dir).unwrap();

        // 1. Broken symlink deletion
        let broken_link = temp_dir.join("broken.txt");
        std::os::unix::fs::symlink("missing.txt", &broken_link).unwrap();
        assert!(broken_link.symlink_metadata().is_ok());

        let deleted = delete_files(&[broken_link.clone()], std::ptr::null(), |_, _, _| Ok(()))
            .expect("delete_files should succeed for broken symlink");
        assert_eq!(deleted, 1);
        assert!(broken_link.symlink_metadata().is_err());

        // 2. Symlink to directory: delete symlink should not delete target directory contents
        let real_dir = temp_dir.join("real_dir");
        fs::create_dir_all(&real_dir).unwrap();
        let target_file = real_dir.join("inner.txt");
        fs::write(&target_file, b"keep me").unwrap();

        let dir_symlink = temp_dir.join("dir_link");
        std::os::unix::fs::symlink(&real_dir, &dir_symlink).unwrap();
        assert!(dir_symlink.symlink_metadata().is_ok());

        let deleted_link = delete_files(&[dir_symlink.clone()], std::ptr::null(), |_, _, _| Ok(()))
            .expect("delete_files should delete directory symlink");
        assert_eq!(deleted_link, 1);
        assert!(dir_symlink.symlink_metadata().is_err());
        assert!(target_file.exists(), "Target file in real_dir must not be deleted");
        assert_eq!(fs::read(&target_file).unwrap(), b"keep me");

        let _ = fs::remove_dir_all(&temp_dir);
    }
}
