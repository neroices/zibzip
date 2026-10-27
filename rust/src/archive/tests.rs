use super::*;
use std::fs;

#[test]
fn test_roundtrip_all_formats() {
    let temp_dir = std::env::temp_dir().join("zibzip_rust_test");
    let _ = fs::remove_dir_all(&temp_dir);
    let src_dir = temp_dir.join("src");
    fs::create_dir_all(&src_dir).unwrap();
    fs::write(src_dir.join("a.txt"), b"hello").unwrap();
    let sub = src_dir.join("sub");
    fs::create_dir_all(&sub).unwrap();
    fs::write(sub.join("b.bin"), vec![42u8; 300_000]).unwrap();
    fs::write(sub.join("empty.txt"), b"").unwrap();

    let formats = [
        ("out.zip", "ZIP"),
        ("out.7z", "7Z"),
        ("out.tar", "TAR"),
        ("out.tar.gz", "TAR.GZ"),
        ("out.tar.bz2", "TAR.BZ2"),
        ("out.tar.xz", "TAR.XZ"),
        ("out.tar.zst", "TAR.ZST"),
    ];

    for (fname, fmt) in formats {
        println!("Testing format: {}", fmt);
        let out_archive = temp_dir.join(fname);
        let sources = vec![src_dir.clone()];
        let res = create_archive(
            &sources,
            &out_archive,
            fmt,
            3,
            None,
            std::ptr::null(),
            |_, _, _| Ok(()),
        );
        assert!(
            res.is_ok(),
            "Failed to create format {}: {:?}",
            fmt,
            res.err()
        );

        let entries = list_entries(&out_archive, None).unwrap();
        assert!(
            entries.iter().any(|e| e.path == "src/a.txt"),
            "{}: a.txt listed (actual: {:?})",
            fmt,
            entries.iter().map(|e| &e.path).collect::<Vec<_>>()
        );

        let extract_dest = temp_dir.join(format!("dest_{}", fmt));
        let extract_res = extract_archive(
            &out_archive,
            &extract_dest,
            None,
            "",
            None,
            0,
            std::ptr::null(),
            |_, _, _| Ok(()),
        );
        assert!(
            extract_res.is_ok(),
            "Failed to extract {}: {:?}",
            fmt,
            extract_res.err()
        );
        assert_eq!(fs::read(extract_dest.join("src/a.txt")).unwrap(), b"hello");
    }
    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_roundtrip_single_formats() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_single_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let src_file = temp_dir.join("note.txt");
    fs::write(&src_file, b"single stream payload").unwrap();

    let single_formats = [
        ("note.txt.gz", "GZ"),
        ("note.txt.bz2", "BZ2"),
        ("note.txt.xz", "XZ"),
        ("note.txt.zst", "ZST"),
    ];

    for (fname, fmt) in single_formats {
        println!("Testing single format: {}", fmt);
        let out_archive = temp_dir.join(fname);
        let sources = vec![src_file.clone()];
        let res = create_archive(
            &sources,
            &out_archive,
            fmt,
            3,
            None,
            std::ptr::null(),
            |_, _, _| Ok(()),
        );
        assert!(
            res.is_ok(),
            "Failed to create single format {}: {:?}",
            fmt,
            res.err()
        );

        let entries = list_entries(&out_archive, None).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "note.txt");

        let extract_dest = temp_dir.join(format!("dest_{}", fmt));
        let extract_res = extract_archive(
            &out_archive,
            &extract_dest,
            None,
            "",
            None,
            0,
            std::ptr::null(),
            |_, _, _| Ok(()),
        );
        assert!(
            extract_res.is_ok(),
            "Failed to extract {}: {:?}",
            fmt,
            extract_res.err()
        );
        assert_eq!(
            fs::read(extract_dest.join("note.txt")).unwrap(),
            b"single stream payload"
        );
    }
    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_add_zip_entries_preserves_hierarchy() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_add_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let zip_path = temp_dir.join("test.zip");

    // Create initial zip
    let init_file = temp_dir.join("init.txt");
    fs::write(&init_file, b"initial").unwrap();
    create_archive(
        &[init_file],
        &zip_path,
        "ZIP",
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();

    // Create a folder tree to add: folder/sub/child.txt
    let folder = temp_dir.join("folder");
    let sub = folder.join("sub");
    fs::create_dir_all(&sub).unwrap();
    fs::write(sub.join("child.txt"), b"nested child").unwrap();

    // Add folder to zip root
    let added = add_zip_entries(
        &zip_path,
        "",
        &[folder],
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();
    assert!(added >= 1);

    let entries = list_entries(&zip_path, None).unwrap();
    let paths: Vec<String> = entries.into_iter().map(|e| e.path).collect();
    assert!(
        paths.iter().any(|p| p == "folder/sub/child.txt"),
        "Expected folder/sub/child.txt in zip, got: {:?}",
        paths
    );

    // Test removing entries
    let removed = remove_zip_entries(
        &zip_path,
        &["folder/sub/child.txt".to_string()],
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();
    assert_eq!(removed, 1);

    let entries_after_remove = list_entries(&zip_path, None).unwrap();
    assert!(!entries_after_remove.iter().any(|e| e.path == "folder/sub/child.txt"));
    assert!(entries_after_remove.iter().any(|e| e.path == "init.txt"));

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_tar_preserves_mtime() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_tar_mtime_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let test_file = temp_dir.join("test.txt");
    fs::write(&test_file, b"content").unwrap();
    let tar_path = temp_dir.join("test.tar");

    create_archive(
        &[test_file],
        &tar_path,
        "TAR",
        0,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();

    let entries = list_entries(&tar_path, None).unwrap();
    assert_eq!(entries.len(), 1);
    assert!(entries[0].last_modified > 0, "Tar entry must have non-zero last_modified");

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_zip_part_extension_editing() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_zip_part_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let zip_path = temp_dir.join("test.zip.part");

    let init_file = temp_dir.join("init.txt");
    fs::write(&init_file, b"initial payload").unwrap();
    create_archive(
        &[init_file],
        &zip_path,
        "ZIP",
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();

    let extra_file = temp_dir.join("extra.txt");
    fs::write(&extra_file, b"extra payload").unwrap();

    // Adding entries to an archive named .part must not truncate it to 0 bytes
    let added = add_zip_entries(
        &zip_path,
        "",
        &[extra_file],
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();
    assert_eq!(added, 1);

    {
        let file = std::fs::File::open(&zip_path).unwrap();
        let mut archive = ::zip::ZipArchive::new(file).unwrap();
        assert_eq!(archive.len(), 2);
        let names: Vec<String> = (0..archive.len())
            .map(|i| archive.by_index(i).unwrap().name().to_string())
            .collect();
        assert!(names.iter().any(|n| n == "init.txt"));
        assert!(names.iter().any(|n| n == "extra.txt"));
    }

    let removed = remove_zip_entries(
        &zip_path,
        &["extra.txt".to_string()],
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();
    assert_eq!(removed, 1);

    {
        let file = std::fs::File::open(&zip_path).unwrap();
        let mut archive = ::zip::ZipArchive::new(file).unwrap();
        assert_eq!(archive.len(), 1);
        let name = archive.by_index(0).unwrap().name().to_string();
        assert_eq!(name, "init.txt");
    }

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_archive_creation_into_self() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_self_pack_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let src_dir = temp_dir.join("src");
    fs::create_dir_all(&src_dir).unwrap();
    fs::write(src_dir.join("file.txt"), b"test content").unwrap();

    // Create archive directly inside the folder being compressed
    let out_archive = src_dir.join("out.zip");
    create_archive(
        &[src_dir.clone()],
        &out_archive,
        "ZIP",
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();

    let entries = list_entries(&out_archive, None).unwrap();
    let paths: Vec<String> = entries.into_iter().map(|e| e.path).collect();
    // Must NOT contain out.zip or out.zip.part inside the archive
    assert!(paths.iter().any(|p| p.ends_with("file.txt")));
    assert!(!paths.iter().any(|p| p.contains("out.zip")));

    let _ = fs::remove_dir_all(&temp_dir);
}
