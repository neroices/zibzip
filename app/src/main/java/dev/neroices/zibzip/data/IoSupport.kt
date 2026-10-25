package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ZibzipException
import dev.neroices.zibzip.domain.ProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

internal const val BUFFER_SIZE = 128 * 1024

/** Throttled bridge from byte counters to a [ProgressListener]. */
class ProgressTracker(private val listener: ProgressListener, var total: Long) {
    var done: Long = 0
        private set
    var current: String = ""
    private var lastEmit = 0L

    fun add(n: Long) {
        done += n
        maybeEmit()
    }

    fun set(value: Long) {
        done = value
        maybeEmit()
    }

    fun flush() = listener.onProgress(done, total, current)

    private fun maybeEmit() {
        val now = System.nanoTime()
        if (now - lastEmit >= 100_000_000L) {
            lastEmit = now
            flush()
        }
    }
}

/** Counts the bytes consumed from the underlying stream (used for compressed-input progress). */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) count += s
        return s
    }
}

internal object IoSupport {

    fun checkSpace(dir: File, needed: Long) {
        if (needed <= 0) return
        val free = dir.usableSpace
        if (needed > free) throw ZibzipException.InsufficientSpace(needed, free)
    }

    fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

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

    /** Resolves an archive entry path under [destDir], rejecting "zip-slip" traversal. */
    fun safeResolve(destDir: File, relative: String): File {
        val clean = relative.replace('\\', '/').trimStart('/')
        val target = File(destDir, clean)
        val base = destDir.canonicalPath
        val canonical = target.canonicalPath
        if (canonical != base && !canonical.startsWith(base + File.separator)) {
            throw ZibzipException.Unsupported("Archive contains an unsafe path: $relative")
        }
        return target
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

    /** Streams [input] into [target], honouring coroutine cancellation and removing partial output on failure. */
    suspend fun copyToFile(input: InputStream, target: File, onBytes: (Int) -> Unit) {
        val ctx = currentCoroutineContext()
        target.parentFile?.mkdirs()
        try {
            FileOutputStream(target).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    ctx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    suspend fun copyFile(src: File, dst: File, onBytes: (Int) -> Unit) {
        FileInputStream(src).use { copyToFile(it, dst, onBytes) }
        dst.setLastModified(src.lastModified())
    }
}
