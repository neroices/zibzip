package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.ZibzipException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Directory listing and search helper.
 */
class FileLister {

    suspend fun list(
        dir: File,
        showHidden: Boolean,
        sortField: Int = -1,
        ascending: Boolean = true,
        foldersFirst: Boolean = true,
    ): List<FileItem> = withContext(Dispatchers.IO) {
        if (!dir.exists()) throw ZibzipException.NotFound(dir.path)
        if (!dir.isDirectory) throw ZibzipException.Unsupported("${dir.name} is not a folder")

        try {
            val items = NativeArchiveBridge.nativeListDirectory(
                path = dir.absolutePath,
                showHidden = showHidden,
                sortField = sortField,
                ascending = ascending,
                foldersFirst = foldersFirst,
            )
            items.filterNot { it.name == TrashManager.TRASH_DIR_NAME }
        } catch (e: IOException) {
            throw IoSupport.mapIo(e, dir.path)
        } catch (t: Throwable) {
            throw IoSupport.mapIo(t, dir.path)
        }
    }

    /**
     * Walks [root] and emits matches in batches.
     */
    fun search(
        root: File,
        showHidden: Boolean,
        limit: Int = 5000,
        query: String = "",
        extensions: List<String>? = null,
        accept: (name: String, isDirectory: Boolean) -> Boolean = { _, _ -> true },
    ): Flow<List<FileItem>> = flow {
        if (!root.exists() || !root.isDirectory) return@flow

        val cancelToken = NativeArchiveBridge.nativeCreateCancelToken()
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) {
                NativeArchiveBridge.nativeCancelOperation(cancelToken)
            }
        }
        try {
            val results = NativeArchiveBridge.nativeSearchFiles(
                rootPath = root.absolutePath,
                query = query,
                extensions = extensions?.toTypedArray(),
                showHidden = showHidden,
                limit = limit,
                cancelToken = cancelToken,
            ) ?: return@flow

            val filtered = results.filter {
                it.name != TrashManager.TRASH_DIR_NAME &&
                    !it.path.contains("/${TrashManager.TRASH_DIR_NAME}/") &&
                    accept(it.name, it.isDirectory)
            }
            val chunkSize = 40
            for (chunk in filtered.chunked(chunkSize)) {
                currentCoroutineContext().ensureActive()
                emit(chunk)
            }
        } finally {
            cancelHandle?.dispose()
            NativeArchiveBridge.nativeFreeCancelToken(cancelToken)
        }
    }.flowOn(Dispatchers.IO)
}
