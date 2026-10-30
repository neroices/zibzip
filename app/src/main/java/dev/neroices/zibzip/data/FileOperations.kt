package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ZibzipException
import dev.neroices.zibzip.domain.ProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

data class FolderStats(val bytes: Long, val files: Int, val folders: Int)

/**
 * File operations (create, rename, copy, move, delete).
 */
class FileOperations {

    fun createFolder(parent: File, name: String): File {
        val clean = name.trim()
        IoSupport.validateName(clean)
        val target = File(parent, clean)
        if (target.exists()) throw ZibzipException.AlreadyExists(clean)
        if (!parent.canWrite()) throw ZibzipException.PermissionDenied(parent.path)
        if (!target.mkdir()) throw ZibzipException.Io("Could not create \"$clean\"")
        return target
    }

    fun createFile(parent: File, name: String): File {
        val clean = name.trim()
        IoSupport.validateName(clean)
        val target = File(parent, clean)
        if (target.exists()) throw ZibzipException.AlreadyExists(clean)
        if (!parent.canWrite()) throw ZibzipException.PermissionDenied(parent.path)
        try {
            if (!target.createNewFile()) throw ZibzipException.AlreadyExists(clean)
        } catch (e: IOException) {
            throw IoSupport.mapIo(e, target.path)
        }
        return target
    }

    fun rename(file: File, newName: String): File {
        val clean = newName.trim()
        IoSupport.validateName(clean)
        if (clean == file.name) return file
        val parent = file.parentFile ?: throw ZibzipException.Io("Cannot rename this item")
        val target = File(parent, clean)
        if (target.exists() && !clean.equals(file.name, ignoreCase = true)) {
            throw ZibzipException.AlreadyExists(clean)
        }
        if (!file.renameTo(target)) {
            throw if (!parent.canWrite()) ZibzipException.PermissionDenied(file.path)
            else ZibzipException.Io("Could not rename \"${file.name}\"")
        }
        return target
    }

    /** Copies [sources] into [destDir] using native 256KB streaming I/O. Returns created files. */
    suspend fun copy(
        sources: List<File>,
        destDir: File,
        progress: ProgressListener = ProgressListener.None,
    ): List<File> = withContext(Dispatchers.IO) {
        validateDestination(destDir)
        sources.forEach { guardNotIntoSelf(it, destDir) }

        val contextToken = currentCoroutineContext()[CancelTokenContext]?.token
        val cancelToken = contextToken ?: NativeArchiveBridge.nativeCreateCancelToken()
        val ownsToken = contextToken == null
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) {
                NativeArchiveBridge.nativeCancelOperation(cancelToken)
            }
        }
        val created = ArrayList<File>()
        val allocated = HashSet<String>(sources.size)
        for (src in sources) {
            val child = IoSupport.uniqueChild(destDir, src.name, allocated)
            allocated += child.name
            created.add(child)
        }

        try {
            if (job?.isCancelled == true) {
                throw CancellationException("Operation cancelled")
            }
            val sourcePaths = sources.map { it.absolutePath }.toTypedArray()
            val targetPaths = created.map { it.absolutePath }.toTypedArray()
            val bytesCopied = NativeArchiveBridge.nativeCopyFiles(
                sourcePaths = sourcePaths,
                targetPaths = targetPaths,
                destDirPath = destDir.absolutePath,
                cancelToken = cancelToken,
                progressListener = progress.toNative(),
            )
            if (bytesCopied < 0) {
                if (job?.isCancelled == true) {
                    throw CancellationException("Operation cancelled")
                }
                throw ZibzipException.Io("Copy operation failed or was cancelled")
            }
            created
        } catch (t: Throwable) {
            if (job?.isCancelled == true || t is CancellationException || t.message?.contains("cancelled", ignoreCase = true) == true) {
                throw CancellationException("Operation cancelled", t)
            }
            throw IoSupport.mapIo(t, destDir.path)
        } finally {
            cancelHandle?.dispose()
            if (ownsToken) {
                NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
            }
        }
    }

    /** Moves [sources] into [destDir]; uses an instant rename when possible, native copy+delete otherwise. */
    suspend fun move(
        sources: List<File>,
        destDir: File,
        progress: ProgressListener = ProgressListener.None,
    ): Int = withContext(Dispatchers.IO) {
        validateDestination(destDir)
        sources.forEach { guardNotIntoSelf(it, destDir) }
        val destCanonical = destDir.canonicalPath
        val pending = ArrayList<File>()
        var moved = 0

        for (src in sources) {
            currentCoroutineContext().ensureActive()
            if (src.parentFile?.canonicalPath == destCanonical) {
                moved++
                continue
            }
            val target = IoSupport.uniqueChild(destDir, src.name)
            if (src.renameTo(target)) {
                moved++
            } else {
                pending += src
            }
        }

        if (pending.isNotEmpty()) {
            for (src in pending) {
                currentCoroutineContext().ensureActive()
                val copied = copy(listOf(src), destDir, progress)
                val targetFile = copied.firstOrNull()
                val targetExists = targetFile != null && Files.exists(
                    targetFile.toPath(),
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (targetExists) {
                    delete(listOf(src), progress)
                    moved++
                }
            }
        }

        moved
    }

    suspend fun delete(
        files: List<File>,
        progress: ProgressListener = ProgressListener.None,
    ): Int = withContext(Dispatchers.IO) {
        val contextToken = currentCoroutineContext()[CancelTokenContext]?.token
        val cancelToken = contextToken ?: NativeArchiveBridge.nativeCreateCancelToken()
        val ownsToken = contextToken == null
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) {
                NativeArchiveBridge.nativeCancelOperation(cancelToken)
            }
        }
        try {
            if (job?.isCancelled == true) {
                throw CancellationException("Operation cancelled")
            }
            val paths = files.map { it.absolutePath }.toTypedArray()
            val deleted = NativeArchiveBridge.nativeDeleteFiles(
                paths = paths,
                cancelToken = cancelToken,
                progressListener = progress.toNative(),
            )
            if (deleted < 0) {
                if (job?.isCancelled == true) {
                    throw CancellationException("Operation cancelled")
                }
                throw ZibzipException.Io("Delete operation failed")
            }
            deleted.toInt()
        } catch (t: Throwable) {
            if (job?.isCancelled == true || t is CancellationException || t.message?.contains("cancelled", ignoreCase = true) == true) {
                throw CancellationException("Operation cancelled", t)
            }
            throw IoSupport.mapIo(t, files.firstOrNull()?.path.orEmpty())
        } finally {
            cancelHandle?.dispose()
            if (ownsToken) {
                NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
            }
        }
    }

    suspend fun folderStats(dir: File): FolderStats = withContext(Dispatchers.IO) {
        val cancelToken = NativeArchiveBridge.nativeCreateCancelToken()
        val job = coroutineContext[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) {
                NativeArchiveBridge.nativeCancelOperation(cancelToken)
            }
        }
        try {
            val stats = NativeArchiveBridge.nativeFolderStats(dir.absolutePath, cancelToken)
            if (stats != null && stats.size >= 3) {
                FolderStats(bytes = stats[0], files = stats[1].toInt(), folders = stats[2].toInt())
            } else {
                FolderStats(0, 0, 0)
            }
        } catch (t: Throwable) {
            if (job?.isCancelled == true || t is CancellationException) {
                throw CancellationException("Folder stats cancelled", t)
            }
            FolderStats(0, 0, 0)
        } finally {
            cancelHandle?.dispose()
            NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
        }
    }

    /** Computes the SHA-256 checksum for the specified file in 64-char hex format. */
    suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().buffered().use { stream ->
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                currentCoroutineContext().ensureActive()
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateDestination(destDir: File) {
        if (!destDir.isDirectory) throw ZibzipException.NotFound(destDir.path)
        if (!destDir.canWrite()) throw ZibzipException.PermissionDenied(destDir.path)
    }

    private fun guardNotIntoSelf(src: File, destDir: File) {
        if (!src.isDirectory) return
        val s = src.canonicalPath
        val d = destDir.canonicalPath
        if (d == s || d.startsWith(s + File.separator)) {
            throw ZibzipException.Unsupported("A folder can't be copied or moved into itself")
        }
    }
}
