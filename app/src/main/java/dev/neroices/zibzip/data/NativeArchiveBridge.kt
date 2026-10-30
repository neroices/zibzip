package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ArchiveEntryItem
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.ProgressListener
import java.io.File

fun interface NativeProgressListener {
    fun onProgress(done: Long, total: Long, current: String)
}

internal fun ProgressListener.toNative(): NativeProgressListener? =
    if (this !== ProgressListener.None) {
        NativeProgressListener { done, total, current -> onProgress(done, total, current) }
    } else {
        null
    }

object NativeArchiveBridge {

    init {
        loadNativeLibrary()
    }

    private fun loadNativeLibrary() {
        try {
            System.loadLibrary("zibzip")
        } catch (e: UnsatisfiedLinkError) {
            // Fallback for JVM unit tests outside Android runtime
            val userDir = System.getProperty("user.dir") ?: "."
            val libName = System.mapLibraryName("zibzip")
            val candidates = listOf(
                File(userDir, "rust/target/release/$libName"),
                File(userDir, "rust/target/debug/$libName"),
                File(userDir, "../rust/target/release/$libName"),
                File(userDir, "../rust/target/debug/$libName"),
                File(userDir, "target/release/$libName"),
                File(userDir, "target/debug/$libName"),
            )
            var loaded = false
            for (candidate in candidates) {
                if (candidate.exists()) {
                    try {
                        System.load(candidate.absolutePath)
                        loaded = true
                        break
                    } catch (_: UnsatisfiedLinkError) {
                        // Try next candidate
                    }
                }
            }
            if (!loaded) {
                val searched = candidates.joinToString("\n") { " - ${it.absolutePath} (exists: ${it.exists()})" }
                throw UnsatisfiedLinkError(
                    "Could not load native library '$libName' for host JVM unit tests. " +
                        "Run './gradlew cargoBuildHost' to compile the host library.\n" +
                        "Searched candidates:\n$searched",
                ).apply { initCause(e) }
            }
        }
    }

    external fun nativeCreateCancelToken(): Long
    external fun nativeCancelOperation(token: Long)
    external fun nativeFreeCancelToken(token: Long)

    external fun nativeListDirectory(
        path: String,
        showHidden: Boolean,
        sortField: Int,
        ascending: Boolean,
        foldersFirst: Boolean,
    ): Array<FileItem>

    external fun nativeSearchFiles(
        rootPath: String,
        query: String,
        extensions: Array<String>?,
        showHidden: Boolean,
        limit: Int,
        cancelToken: Long,
    ): Array<FileItem>?

    external fun nativeListArchiveEntries(
        archivePath: String,
        password: String?,
    ): Array<ArchiveEntryItem>

    external fun nativeExtractArchive(
        archivePath: String,
        destDirPath: String,
        selection: Array<String>?,
        basePath: String,
        password: String?,
        conflictPolicy: Int,
        cancelToken: Long,
        progressListener: NativeProgressListener?,
    ): Int

    external fun nativeCreateArchive(
        sourcePaths: Array<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String?,
        cancelToken: Long,
        progressListener: NativeProgressListener?,
    ): Long

    external fun nativeRemoveZipEntries(
        archivePath: String,
        pathsToRemove: Array<String>,
        cancelToken: Long,
        progressListener: NativeProgressListener?,
    ): Int

    external fun nativeAddZipEntries(
        archivePath: String,
        innerDir: String,
        sourcePaths: Array<String>,
        level: Int,
        password: String?,
        cancelToken: Long,
        progressListener: NativeProgressListener?,
    ): Int

    external fun nativeCopyFiles(
        sourcePaths: Array<String>,
        targetPaths: Array<String>,
        destDirPath: String,
        cancelToken: Long,
        progressListener: NativeProgressListener?,
    ): Long

    external fun nativeDeleteFiles(
        paths: Array<String>,
        cancelToken: Long,
        progressListener: NativeProgressListener?,
    ): Long

    external fun nativeFolderStats(
        dirPath: String,
        cancelToken: Long,
    ): LongArray?
}
