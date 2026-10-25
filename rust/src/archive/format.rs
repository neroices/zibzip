#[derive(Clone, Debug)]
pub struct ArchiveEntryData {
    pub path: String,
    pub is_directory: bool,
    pub size: i64,
    pub compressed_size: i64,
    pub last_modified: i64,
    pub is_encrypted: bool,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Format {
    Zip,
    SevenZ,
    Tar,
    TarGz,
    TarBz2,
    TarXz,
    TarZst,
    Gz,
    Bz2,
    Xz,
    Zst,
    Rar,
    Unknown,
}

pub fn detect_format(file_name: &str) -> Format {
    let lower = file_name.to_ascii_lowercase();
    if lower.ends_with(".tar.gz") || lower.ends_with(".tgz") {
        Format::TarGz
    } else if lower.ends_with(".tar.bz2") || lower.ends_with(".tbz2") || lower.ends_with(".tbz") {
        Format::TarBz2
    } else if lower.ends_with(".tar.xz") || lower.ends_with(".txz") {
        Format::TarXz
    } else if lower.ends_with(".tar.zst") {
        Format::TarZst
    } else if lower.ends_with(".zip") || lower.ends_with(".jar") || lower.ends_with(".cbz") {
        Format::Zip
    } else if lower.ends_with(".7z") {
        Format::SevenZ
    } else if lower.ends_with(".tar") {
        Format::Tar
    } else if lower.ends_with(".gz") {
        Format::Gz
    } else if lower.ends_with(".bz2") {
        Format::Bz2
    } else if lower.ends_with(".xz") {
        Format::Xz
    } else if lower.ends_with(".zst") {
        Format::Zst
    } else if lower.ends_with(".rar") {
        Format::Rar
    } else {
        Format::Unknown
    }
}

pub fn strip_format_suffix(name: &str, format: Format) -> &str {
    let lower = name.to_ascii_lowercase();
    let suffix = match format {
        Format::TarGz => {
            if lower.ends_with(".tar.gz") {
                ".tar.gz"
            } else {
                ".tgz"
            }
        }
        Format::TarBz2 => {
            if lower.ends_with(".tar.bz2") {
                ".tar.bz2"
            } else if lower.ends_with(".tbz2") {
                ".tbz2"
            } else {
                ".tbz"
            }
        }
        Format::TarXz => {
            if lower.ends_with(".tar.xz") {
                ".tar.xz"
            } else {
                ".txz"
            }
        }
        Format::TarZst => ".tar.zst",
        Format::Zip => {
            if lower.ends_with(".zip") {
                ".zip"
            } else if lower.ends_with(".jar") {
                ".jar"
            } else {
                ".cbz"
            }
        }
        Format::SevenZ => ".7z",
        Format::Tar => ".tar",
        Format::Gz => ".gz",
        Format::Bz2 => ".bz2",
        Format::Xz => ".xz",
        Format::Zst => ".zst",
        Format::Rar => ".rar",
        Format::Unknown => "",
    };
    if suffix.is_empty() {
        name
    } else {
        &name[..name.len().saturating_sub(suffix.len())]
    }
}

pub(crate) fn normalize_entry_path(raw: &str) -> String {
    let mut clean = raw.replace('\\', "/");
    while clean.starts_with("./") {
        clean = clean[2..].to_string();
    }
    clean.trim_matches('/').to_string()
}
