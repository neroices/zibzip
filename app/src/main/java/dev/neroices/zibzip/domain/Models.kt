package dev.neroices.zibzip.domain

import androidx.compose.runtime.Immutable
import java.io.File

/** Broad categories used to pick an icon and tint for a file row. */
enum class FileKind { Folder, Archive, Image, Video, Audio, Document, Code, Apk, Other }

object FileTypes {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv")
    private val audio = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "amr", "mid")
    private val document = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "txt", "md", "rtf", "epub", "csv", "log", "lrc",
    )
    private val code = setOf(
        "kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "c", "cpp", "h",
        "rs", "go", "yml", "yaml", "toml", "gradle", "kts", "sql",
        "jsx", "tsx", "mjs", "cjs", "htm", "xhtml", "scss", "sass", "less",
        "hpp", "hh", "hxx", "cc", "cxx", "bash", "zsh", "fish", "lua", "rb", "php",
        "dart", "swift", "zig", "diff", "patch", "proto", "graphql", "gql",
    )
    private val textDocuments = setOf("txt", "md", "csv", "log", "rtf", "tsv", "ini", "conf", "cfg", "properties", "env", "lrc")
    private val exactTextNames = setOf(
        "dockerfile", "makefile", "gnumakefile", "license", "readme", ".gitignore",
        ".gitattributes", ".gitmodules", ".env", ".bashrc", ".zshrc", ".profile",
        "gemfile", "rakefile", "cmakelists.txt", "dmesg", "kmsg", "hosts",
        "fstab", "resolv.conf", "cmdline", "syslog", "messages",
    )
    private val textPrefixes = listOf(
        "console-ramoops",
        "pmsg-ramoops",
        "dmesg-ramoops",
        "ftrace-ramoops",
        "last_kmsg",
    )
    private val knownBinaryExtensions = setOf(
        "pdf", "apk", "xapk", "apks", "zip", "tar", "gz", "bz2", "xz", "7z", "zst", "rar",
        "exe", "so", "dylib", "dll", "bin", "dat", "iso", "img", "class", "dex", "o", "a",
        "jar", "war", "ear", "db", "sqlite", "sqlite3",
    )

    fun isTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in code || ext in textDocuments) return true
        val lower = name.lowercase()
        if (lower in exactTextNames) return true
        return textPrefixes.any { lower.startsWith(it) }
    }

    fun isTextFile(file: File): Boolean {
        if (isTextFile(file.name)) return true
        if (!file.exists() || file.isDirectory) return false
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext in image || ext in video || ext in audio || ext in knownBinaryExtensions || ArchiveFormat.detect(file.name) != null) {
            return false
        }
        return probeIsTextContent(file)
    }

    fun probeIsTextContent(file: File): Boolean {
        return try {
            if (!file.exists() || file.isDirectory) return false
            val buffer = ByteArray(1024)
            val bytesRead = file.inputStream().use { it.read(buffer) }
            if (bytesRead <= 0) return true
            var controlCharCount = 0
            for (i in 0 until bytesRead) {
                val b = buffer[i].toInt() and 0xFF
                if (b == 0) return false
                if (b < 0x20 && b != 9 && b != 10 && b != 13 && b != 12) {
                    controlCharCount++
                }
            }
            (controlCharCount.toDouble() / bytesRead) <= 0.02
        } catch (_: Throwable) {
            false
        }
    }

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audio
    }

    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in video
    }

    fun kindOf(name: String, isDirectory: Boolean): FileKind {
        if (isDirectory) return FileKind.Folder
        if (ArchiveFormat.detect(name) != null) return FileKind.Archive
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in image -> FileKind.Image
            in video -> FileKind.Video
            in audio -> FileKind.Audio
            in document -> FileKind.Document
            in code -> FileKind.Code
            "apk", "xapk", "apks" -> FileKind.Apk
            else -> FileKind.Other
        }
    }
}

@Immutable
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
) {
    val kind: FileKind = FileTypes.kindOf(name, isDirectory)

    /** Short badge text shown for files ("ZIP", "PDF", ...). Empty for folders. */
    val typeLabel: String = when {
        isDirectory -> ""
        else -> (ArchiveFormat.detect(name)?.label ?: name.substringAfterLast('.', "")).uppercase().take(7)
    }

    val parentPath: String get() = path.substringBeforeLast('/', "")
}

enum class SortField { Name, Size, Date, Type }

@Immutable
data class SortOption(
    val field: SortField = SortField.Name,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileItem> {
        val base: Comparator<FileItem> = when (field) {
            SortField.Name -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.Size -> compareBy<FileItem> { it.size }.thenComparing { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.Date -> compareBy<FileItem> { it.lastModified }.thenComparing { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.Type -> compareBy<FileItem> { it.typeLabel }.thenComparing { a, b -> NaturalOrder.compare(a.name, b.name) }
        }
        val directed = if (ascending) base else base.reversed()
        return if (foldersFirst) {
            Comparator<FileItem> { a, b -> b.isDirectory.compareTo(a.isDirectory) }.thenComparing(directed)
        } else {
            directed
        }
    }
}

/** Case-insensitive "natural" comparison: file2 < file10. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                while (si < a.length && a[si] == '0') si++
                var sj = j
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = sj
                while (ej < b.length && b[ej].isDigit()) ej++
                val lenA = ei - si
                val lenB = ej - sj
                if (lenA != lenB) return lenA.compareTo(lenB)
                for (k in 0 until lenA) {
                    val d = a[si + k].compareTo(b[sj + k])
                    if (d != 0) return d
                }
                val origLenA = ei - i
                val origLenB = ej - j
                if (origLenA != origLenB) return origLenA.compareTo(origLenB)
                i = ei
                j = ej
            } else {
                val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (d != 0) return d
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

@Immutable
data class StorageVolumeInfo(
    val label: String,
    val path: String,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
)

/** One entry inside an archive, as reported by the archive's own index. */
@Immutable
data class ArchiveEntryItem(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val lastModified: Long,
    val isEncrypted: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

fun interface ProgressListener {
    fun onProgress(done: Long, total: Long, current: String)

    companion object {
        val None = ProgressListener { _, _, _ -> }
    }
}

/** State of the single long-running operation the app allows at a time. */
sealed interface OpState {
    data object Idle : OpState

    @Immutable
    data class Running(
        val title: String,
        val done: Long = 0,
        val total: Long = 0,
        val current: String = "",
        val speedBytesPerSec: Long = 0,
        val id: String = java.util.UUID.randomUUID().toString(),
        val keepScreenOn: Boolean = false,
    ) : OpState {
        val fraction: Float? get() = if (total > 0) (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else null
        val percentage: Int get() = fraction?.let { (it * 100).toInt() } ?: 0
    }
}
