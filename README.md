# zibzip

An archive and file manager for Android, built with Jetpack Compose, Material 3, and a native Rust core.

Completely offline, with no ads, analytics, or network permissions. Requires Android 11+ (API 30+).

## Features

- **Archive Management**
  - Browse archives directly without full disk extraction
  - In-place ZIP editing (add, modify, or remove files without re-packing)
  - Create and extract ZIP, 7z, TAR, and RAR 5.0 archives
  - AES-256 encrypted creation (ZIP only) and password decryption for ZIP, 7z, and RAR
  - Compression levels from Store (no compression) to Ultra
  - Direct compression and extraction for single files (`.gz`, `.bz2`, `.xz`, `.zst`)

- **File Management**
  - Full access to internal storage, SD cards, and USB OTG
  - Trash support for safe file deletion
  - Background operation manager with progress notifications, speed metrics, and cancellation
  - Cached thumbnails for media, album art, and APKs
  - Material 3 design and Dynamic Color

- **Built-in Utilities**
  - Video player powered by AndroidX Media3 with gesture controls and playback speed options
  - Audio player with background playback support and synchronized LRC lyrics display
  - PDF viewer with pinch-to-zoom, page thumbnails, and inverted dark mode
  - Image viewer with pinch-to-zoom, rotation, and EXIF metadata inspector
  - Code and text editor powered by Sora Editor with asynchronous syntax highlighting, 2D scrolling, word wrap, in-document search, and Markdown preview

## Supported formats

- **Archives**:
  - ZIP (`.zip`, `.cbz`, `.jar`): In-place editing, AES-256 encryption/decryption, levels 0 to 9
  - 7z (`.7z`): Browse, extract, create, password decryption, levels 0 to 9
  - RAR (`.rar`): Reads v1.5 through v7.0, creates v5.0, password decryption, levels 0 to 9
  - TAR (`.tar`, `.tar.gz`, `.tgz`, `.tar.bz2`, `.tbz2`, `.tbz`, `.tar.xz`, `.txz`, `.tar.zst`): Standard uncompressed `.tar`, and compressed tarballs with levels 0 to 9
  - Single compressed files (`.gz`, `.bz2`, `.xz`, `.zst`): Direct compression and extraction, levels 0 to 9
- **Documents & Images**: PDF, JPG, PNG, GIF, WebP, BMP, HEIC/HEIF, AVIF, SVG
- **Video playback**: MP4, MKV, WebM, AVI, MOV, 3GP, M4V, FLV
- **Audio playback**: MP3, FLAC, WAV, OGG, M4A, AAC, OPUS, AMR
- **Syntax highlighting**: Kotlin, Java, Rust, Python, Go, C/C++, TypeScript, JavaScript, HTML, CSS, JSON, YAML, TOML, Markdown, Shell, SQL, and more

## Building

You'll need JDK 21, the Android NDK, and Rust with [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

1. Set up the Rust targets and `cargo-ndk`:
```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

2. Build the debug APK:
```bash
./gradlew assembleDebug
```

To run tests:
```bash
./gradlew test
```

## License

GNU General Public License v3.0. See [LICENSE](LICENSE) for details.