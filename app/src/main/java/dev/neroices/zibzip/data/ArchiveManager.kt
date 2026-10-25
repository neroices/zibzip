package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ArchiveEntryItem
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ConflictPolicy
import dev.neroices.zibzip.domain.ZibzipException
import dev.neroices.zibzip.domain.ProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Listing, extraction, creation, and editing of archives.
 */
class ArchiveManager {

    suspend fun list(archive: File, password: String? = null): List<ArchiveEntryItem> =
        withContext(Dispatchers.IO) {
            requireReadable(archive)
            try {
                val entries = NativeArchiveBridge.nativeListArchiveEntries(
                    archivePath = archive.absolutePath,
                    password = password,
                )
                entries.toList()
            } catch (t: Throwable) {
                throw mapArchiveError(archive, password, t)
            }
        }

    suspend fun extract(
        archive: File,
        destDir: File,
        selection: Set<String>? = null,
        basePath: String = "",
        password: String? = null,
        conflict: ConflictPolicy = ConflictPolicy.KeepBoth,
        progress: ProgressListener = ProgressListener.None,
    ): Int = withContext(Dispatchers.IO) {
        requireReadable(archive)
        destDir.mkdirs()
        if (!destDir.isDirectory || !destDir.canWrite()) {
            throw ZibzipException.PermissionDenied(destDir.path)
        }

        val conflictInt = when (conflict) {
            ConflictPolicy.KeepBoth -> 0
            ConflictPolicy.Overwrite -> 1
            ConflictPolicy.Skip -> 2
        }

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
            val count = NativeArchiveBridge.nativeExtractArchive(
                archivePath = archive.absolutePath,
                destDirPath = destDir.absolutePath,
                selection = selection?.toTypedArray(),
                basePath = basePath,
                password = password,
                conflictPolicy = conflictInt,
                cancelToken = cancelToken,
                progressListener = { done, total, current ->
                    progress.onProgress(done, total, current)
                },
            )
            if (count < 0) {
                if (job?.isCancelled == true) {
                    throw CancellationException("Operation cancelled")
                }
                throw ZibzipException.Io("Extraction failed or was cancelled")
            }
            count
        } catch (t: Throwable) {
            if (job?.isCancelled == true || t is CancellationException || t.message?.contains("cancelled", ignoreCase = true) == true) {
                throw CancellationException("Operation cancelled", t)
            }
            throw mapArchiveError(archive, password, t)
        } finally {
            cancelHandle?.dispose()
            if (ownsToken) {
                NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
            }
        }
    }

    suspend fun create(
        sources: List<File>,
        output: File,
        format: ArchiveFormat,
        level: Int = 5,
        password: String? = null,
        progress: ProgressListener = ProgressListener.None,
    ): File = withContext(Dispatchers.IO) {
        if (!format.canCreate) {
            throw ZibzipException.Unsupported("Creating ${format.label} archives is not supported")
        }
        if (!format.multiFile && !(sources.size == 1 && sources[0].isFile)) {
            throw ZibzipException.Unsupported("${format.label} can only compress a single file")
        }
        if (!password.isNullOrEmpty() && !format.passwordOnCreate) {
            throw ZibzipException.Unsupported("${format.label} archives can't be password protected here")
        }
        val outDir = output.absoluteFile.parentFile ?: throw ZibzipException.Io("Invalid destination")
        if (!outDir.isDirectory || !outDir.canWrite()) {
            throw ZibzipException.PermissionDenied(outDir.path)
        }

        val sourcePaths = sources.map { it.absolutePath }.toTypedArray()
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
            val bytes = NativeArchiveBridge.nativeCreateArchive(
                sourcePaths = sourcePaths,
                outputPath = output.absolutePath,
                format = format.label,
                level = level,
                password = password,
                cancelToken = cancelToken,
                progressListener = { done, total, current ->
                    progress.onProgress(done, total, current)
                },
            )
            if (bytes < 0) {
                if (job?.isCancelled == true) {
                    throw CancellationException("Operation cancelled")
                }
                throw ZibzipException.Io("Archive creation failed or was cancelled")
            }
            output
        } catch (t: Throwable) {
            output.delete()
            File("${output.path}.part").delete()
            File(output.parentFile, "${output.name}.part").delete()
            if (job?.isCancelled == true || t is CancellationException || t.message?.contains("cancelled", ignoreCase = true) == true) {
                throw CancellationException("Operation cancelled", t)
            }
            throw mapArchiveError(output, password, t)
        } finally {
            cancelHandle?.dispose()
            if (ownsToken) {
                NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
            }
            if (job?.isCancelled == true) {
                output.delete()
                File("${output.path}.part").delete()
                File(output.parentFile, "${output.name}.part").delete()
            }
        }
    }

    suspend fun removeEntries(
        archive: File,
        paths: Set<String>,
        progress: ProgressListener = ProgressListener.None,
    ): Int = withContext(Dispatchers.IO) {
        requireEditableZip(archive)
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
            val removed = NativeArchiveBridge.nativeRemoveZipEntries(
                archivePath = archive.absolutePath,
                pathsToRemove = paths.toTypedArray(),
                cancelToken = cancelToken,
                progressListener = { done, total, current ->
                    progress.onProgress(done, total, current)
                },
            )
            if (removed < 0) {
                if (job?.isCancelled == true) {
                    throw CancellationException("Operation cancelled")
                }
                throw ZibzipException.Io("Removing entries failed or was cancelled")
            }
            removed
        } catch (t: Throwable) {
            if (job?.isCancelled == true || t is CancellationException || t.message?.contains("cancelled", ignoreCase = true) == true) {
                throw CancellationException("Operation cancelled", t)
            }
            throw mapArchiveError(archive, null, t)
        } finally {
            cancelHandle?.dispose()
            if (ownsToken) {
                NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
            }
        }
    }

    suspend fun addEntries(
        archive: File,
        innerDir: String,
        sources: List<File>,
        level: Int = 5,
        password: String? = null,
        progress: ProgressListener = ProgressListener.None,
    ): Int = withContext(Dispatchers.IO) {
        requireEditableZip(archive)
        val sourcePaths = sources.map { it.absolutePath }.toTypedArray()
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
            val added = NativeArchiveBridge.nativeAddZipEntries(
                archivePath = archive.absolutePath,
                innerDir = innerDir,
                sourcePaths = sourcePaths,
                level = level,
                password = password,
                cancelToken = cancelToken,
                progressListener = { done, total, current ->
                    progress.onProgress(done, total, current)
                },
            )
            if (added < 0) {
                if (job?.isCancelled == true) {
                    throw CancellationException("Operation cancelled")
                }
                throw ZibzipException.Io("Adding entries failed or was cancelled")
            }
            added
        } catch (t: Throwable) {
            if (job?.isCancelled == true || t is CancellationException || t.message?.contains("cancelled", ignoreCase = true) == true) {
                throw CancellationException("Operation cancelled", t)
            }
            throw mapArchiveError(archive, password, t)
        } finally {
            cancelHandle?.dispose()
            if (ownsToken) {
                NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
            }
        }
    }

    private fun requireReadable(archive: File) {
        if (!archive.exists()) throw ZibzipException.NotFound(archive.path)
        if (!archive.canRead()) throw ZibzipException.PermissionDenied(archive.path)
    }

    private fun requireEditableZip(archive: File) {
        val format = ArchiveFormat.detect(archive.name)
        if (format != ArchiveFormat.ZIP) {
            throw ZibzipException.Unsupported("Only ZIP archives can be edited")
        }
        requireReadable(archive)
        if (!archive.canWrite()) throw ZibzipException.PermissionDenied(archive.path)
    }

    private fun mapArchiveError(archive: File, password: String?, t: Throwable): Throwable {
        val name = archive.name
        val msg = t.message.orEmpty()
        return when {
            t is kotlinx.coroutines.CancellationException -> t
            msg.contains("cancel", ignoreCase = true) ->
                kotlinx.coroutines.CancellationException("Operation cancelled", t)
            msg.contains("password", ignoreCase = true) || msg.contains("decrypt", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true) ->
                if (password == null) ZibzipException.PasswordRequired(name) else ZibzipException.WrongPassword(name)
            msg.contains("zip-slip", ignoreCase = true) || msg.contains("traversal", ignoreCase = true) ->
                ZibzipException.Unsupported("Zip-slip path traversal is unsupported")
            msg.contains("corrupt", ignoreCase = true) || msg.contains("invalid", ignoreCase = true) || msg.contains("bad", ignoreCase = true) ->
                ZibzipException.CorruptedArchive(name, t)
            msg.contains("unsupported", ignoreCase = true) ->
                ZibzipException.Unsupported(msg.ifEmpty { "Unsupported archive format" })
            msg.contains("permission", ignoreCase = true) || msg.contains("denied", ignoreCase = true) ->
                ZibzipException.PermissionDenied(archive.path)
            msg.contains("not found", ignoreCase = true) ->
                ZibzipException.NotFound(archive.path)
            t is ZibzipException -> t
            else -> ZibzipException.CorruptedArchive(name, t)
        }
    }
}
