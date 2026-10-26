package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ZibzipException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal object IoSupport {

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** "name (1).ext", "name (2).ext", ... until a free name is found. */
    fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!exists(candidate)) return candidate
        val base: String
        val ext: String
        val format = ArchiveFormat.detect(name)
        if (format != null) {
            val lower = name.lowercase()
            val suffix = format.suffixes.filter { lower.endsWith(it) }.maxBy { it.length }
            base = name.dropLast(suffix.length)
            ext = name.takeLast(suffix.length)
        } else {
            val dot = name.lastIndexOf('.')
            if (dot > 0) {
                base = name.substring(0, dot)
                ext = name.substring(dot)
            } else {
                base = name
                ext = ""
            }
        }
        var i = 1
        while (true) {
            candidate = File(dir, "$base ($i)$ext")
            if (!exists(candidate)) return candidate
            i++
        }
    }

    fun validateName(name: String) {
        val bad = name.isBlank() || name == "." || name == ".." || name.contains('/') ||
            name.contains('\u0000') || name.toByteArray().size > 255
        if (bad) throw ZibzipException.InvalidName(name)
    }

    /** Maps low-level failures to typed [ZibzipException]s. Cancellation passes through untouched. */
    fun mapIo(e: Throwable, path: String): Throwable {
        if (e is CancellationException || e is ZibzipException) return e
        if (e.message?.contains("cancel", ignoreCase = true) == true) {
            return CancellationException("Operation cancelled", e)
        }
        return when (e) {
            is SecurityException -> ZibzipException.PermissionDenied(path)
            is java.nio.file.AccessDeniedException -> ZibzipException.PermissionDenied(path)
            is java.nio.file.NoSuchFileException -> ZibzipException.NotFound(path)
            is FileNotFoundException ->
                if (isPermissionMessage(e.message)) ZibzipException.PermissionDenied(path) else ZibzipException.NotFound(path)
            is IOException -> {
                val m = e.message.orEmpty()
                when {
                    "ENOSPC" in m || "No space left" in m -> ZibzipException.InsufficientSpace(-1, 0)
                    isPermissionMessage(m) -> ZibzipException.PermissionDenied(path)
                    "EROFS" in m || "Read-only file system" in m -> ZibzipException.Io("The destination is read-only", e)
                    else -> ZibzipException.Io(m.ifBlank { "I/O error" }, e)
                }
            }
            else -> e
        }
    }

    private fun isPermissionMessage(m: String?): Boolean {
        val s = m.orEmpty()
        return "EACCES" in s || "Permission denied" in s || "EPERM" in s || "Operation not permitted" in s
    }
}
