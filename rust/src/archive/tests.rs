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

#[test]
fn test_zip_extract_with_password_on_directories_and_mixed_entries() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_zip_pw_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let src_dir = temp_dir.join("src");
    let sub_dir = src_dir.join("subdir");
    fs::create_dir_all(&sub_dir).unwrap();
    fs::write(sub_dir.join("nested.txt"), b"nested content").unwrap();
    fs::write(src_dir.join("root.txt"), b"root content").unwrap();

    let out_archive = temp_dir.join("pw.zip");
    create_archive(
        &[src_dir.clone()],
        &out_archive,
        "ZIP",
        3,
        Some("secret123"),
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .unwrap();

    let dest_dir = temp_dir.join("dest");
    let extract_res = extract_archive(
        &out_archive,
        &dest_dir,
        None,
        "",
        Some("secret123"),
        0,
        std::ptr::null(),
        |_, _, _| Ok(()),
    );
    assert!(
        extract_res.is_ok(),
        "Failed to extract password protected zip: {:?}",
        extract_res.err()
    );
    assert_eq!(
        fs::read(dest_dir.join("src/subdir/nested.txt")).unwrap(),
        b"nested content"
    );

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_tar_symlink_traversal_rejected() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_tar_slip_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let tar_path = temp_dir.join("slip.tar");

    {
        let tar_file = fs::File::create(&tar_path).unwrap();
        let mut builder = ::tar::Builder::new(tar_file);

        let mut header = ::tar::Header::new_gnu();
        header.set_entry_type(::tar::EntryType::Symlink);
        header.set_size(0);
        header.set_cksum();
        builder
            .append_link(&mut header, "evil_link", "../../outside_target")
            .unwrap();
        builder.finish().unwrap();
    }

    let dest_dir = temp_dir.join("dest");
    fs::create_dir_all(&dest_dir).unwrap();
    let res = extract_archive(
        &tar_path,
        &dest_dir,
        None,
        "",
        None,
        0,
        std::ptr::null(),
        |_, _, _| Ok(()),
    );
    assert!(res.is_err(), "Extraction of traversal symlink should fail");

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_sevenz_extraction_cancellation_from_progress() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_sevenz_cancel_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let src_dir = temp_dir.join("src");
    fs::create_dir_all(&src_dir).unwrap();
    let file_path = src_dir.join("data.txt");
    fs::write(&file_path, b"some non-trivial payload data to extract").unwrap();

    let archive_path = temp_dir.join("test.7z");
    create_archive(
        &[file_path],
        &archive_path,
        "7z",
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .expect("Should create test 7z archive");

    let dest_dir = temp_dir.join("dest");
    fs::create_dir_all(&dest_dir).unwrap();

    let res = extract_archive(
        &archive_path,
        &dest_dir,
        None,
        "",
        None,
        0,
        std::ptr::null(),
        |_, _, _| Err(()), // Trigger cancellation immediately on progress
    );

    assert!(res.is_err(), "Extraction must fail when cancelled via progress");
    let err_str = res.err().unwrap().to_string();
    assert!(
        err_str.to_lowercase().contains("cancelled"),
        "Error message should mention cancellation: {}",
        err_str
    );

    let extracted_target = dest_dir.join("data.txt");
    assert!(
        !extracted_target.exists(),
        "Partial extracted target file must be cleaned up on cancellation"
    );

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_extract_file_collides_with_existing_dir() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_collision_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let src_dir = temp_dir.join("src");
    fs::create_dir_all(&src_dir).unwrap();
    let file_path = src_dir.join("conflict_item");
    fs::write(&file_path, b"file payload").unwrap();

    let archive_path = temp_dir.join("test.zip");
    create_archive(
        &[file_path],
        &archive_path,
        "zip",
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .expect("Should create test zip archive");

    // Case 1: KeepBoth (policy 0) when dest has a directory named conflict_item
    let dest_dir = temp_dir.join("dest_keepboth");
    fs::create_dir_all(&dest_dir).unwrap();
    fs::create_dir_all(dest_dir.join("conflict_item")).unwrap();

    let res = extract_archive(
        &archive_path,
        &dest_dir,
        None,
        "",
        None,
        0,
        std::ptr::null(),
        |_, _, _| Ok(()),
    );
    assert!(
        res.is_ok(),
        "KeepBoth extraction should succeed despite collision with dir"
    );
    assert!(dest_dir.join("conflict_item").is_dir());
    assert_eq!(
        fs::read(dest_dir.join("conflict_item (1)")).unwrap(),
        b"file payload"
    );

    // Case 2: Overwrite (policy 1) when dest has a directory named conflict_item
    let dest_dir_overwrite = temp_dir.join("dest_overwrite");
    fs::create_dir_all(&dest_dir_overwrite).unwrap();
    fs::create_dir_all(dest_dir_overwrite.join("conflict_item")).unwrap();

    let res = extract_archive(
        &archive_path,
        &dest_dir_overwrite,
        None,
        "",
        None,
        1,
        std::ptr::null(),
        |_, _, _| Ok(()),
    );
    assert!(
        res.is_ok(),
        "Overwrite extraction should succeed by replacing dir with file"
    );
    assert!(dest_dir_overwrite.join("conflict_item").is_file());
    assert_eq!(
        fs::read(dest_dir_overwrite.join("conflict_item")).unwrap(),
        b"file payload"
    );

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_sevenz_list_encrypted_entry() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_test_sevenz_enc_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let src_file = temp_dir.join("secret.txt");
    fs::write(&src_file, b"top secret data").unwrap();

    let archive_path = temp_dir.join("encrypted.7z");
    sevenz_rust::compress_to_path_encrypted(&src_file, &archive_path, "password123".into())
        .expect("Should compress with password");

    // Case 1: When header is encrypted, listing with password reveals entries with is_encrypted = true
    let entries = list_entries(&archive_path, Some("password123"))
        .expect("Should list entries with password");
    assert!(!entries.is_empty(), "Entries list should not be empty");
    let entry = entries
        .iter()
        .find(|e| e.path.contains("secret.txt"))
        .unwrap();
    assert!(
        entry.is_encrypted,
        "7z entry in password-protected archive should be flagged as is_encrypted: true"
    );

    // Case 2: 7z archive with unencrypted header but encrypted content
    let unencrypted_header_path = temp_dir.join("enc_content_plain_header.7z");
    {
        let file = fs::File::create(&unencrypted_header_path).unwrap();
        let mut sz = sevenz_rust::ArchiveWriter::new(file).unwrap();
        sz.set_encrypt_header(false);
        sz.set_content_methods(vec![
            sevenz_rust::encoder_options::AesEncoderOptions::new("password123".into()).into(),
            sevenz_rust::EncoderMethod::LZMA2.into(),
        ]);
        sz.push_archive_entry(
            sevenz_rust::ArchiveEntry::from_path(&src_file, "secret.txt".to_string()),
            Some(fs::File::open(&src_file).unwrap()),
        )
        .unwrap();
        sz.finish().unwrap();
    }

    let entries_plain_hdr = list_entries(&unencrypted_header_path, None)
        .expect("Should list entries without password when header is unencrypted");
    assert!(!entries_plain_hdr.is_empty());
    let entry = entries_plain_hdr
        .iter()
        .find(|e| e.path.contains("secret.txt"))
        .unwrap();
    assert!(
        entry.is_encrypted,
        "7z entry with encrypted content should have is_encrypted: true without password"
    );

    let _ = fs::remove_dir_all(&temp_dir);
}

#[test]
fn test_extract_compound_extension_collision_keep_both() {
    let temp_dir = std::env::temp_dir().join(format!(
        "zibzip_compound_col_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&temp_dir).unwrap();
    let src_dir = temp_dir.join("src");
    fs::create_dir_all(&src_dir).unwrap();
    let file_path = src_dir.join("backup.tar.gz");
    fs::write(&file_path, b"tar gz payload").unwrap();

    let archive_path = temp_dir.join("test.zip");
    create_archive(
        &[file_path],
        &archive_path,
        "zip",
        3,
        None,
        std::ptr::null(),
        |_, _, _| Ok(()),
    )
    .expect("Should create test zip archive");

    let dest_dir = temp_dir.join("dest");
    fs::create_dir_all(&dest_dir).unwrap();
    fs::write(dest_dir.join("backup.tar.gz"), b"existing file").unwrap();

    let res = extract_archive(
        &archive_path,
        &dest_dir,
        None,
        "",
        None,
        0,
        std::ptr::null(),
        |_, _, _| Ok(()),
    );
    assert!(
        res.is_ok(),
        "KeepBoth extraction should succeed on compound extension conflict"
    );
    assert_eq!(
        fs::read(dest_dir.join("backup (1).tar.gz")).unwrap(),
        b"tar gz payload"
    );

    let _ = fs::remove_dir_all(&temp_dir);
}
