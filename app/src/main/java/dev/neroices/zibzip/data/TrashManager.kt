package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ProgressListener
import dev.neroices.zibzip.domain.ZibzipException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class TrashedItem(
    val id: String,
    val originalPath: String,
    val originalName: String,
    val deletedAt: Long,
    val size: Long,
    val isDirectory: Boolean,
) {
    @Transient var trashFile: File = File("")
    @Transient var infoFile: File = File("")
}

data class TrashStats(
    val count: Int,
    val totalBytes: Long,
)

class TrashManager(
    private val getRoots: () -> List<File>,
    private val fileOps: FileOperations,
) {
    constructor(storageProvider: StorageProvider, fileOps: FileOperations) : this(
        getRoots = {
            val list = mutableListOf(storageProvider.primaryRoot)
            for (vol in storageProvider.volumes(withStats = false)) {
                val f = File(vol.path)
                if (f !in list) list.add(f)
            }
            list
        },
        fileOps = fileOps,
    )

    @Volatile
    private var cachedStats: TrashStats? = null

    companion object {
        const val TRASH_DIR_NAME = ".zibzip_trash"
        const val NOMEDIA_FILE_NAME = ".nomedia"
        const val FILES_DIR_NAME = "files"
        const val INFO_DIR_NAME = "info"
        private val SIZE_REGEX = Regex("\"size\":\\s*(\\d+)")
    }

    /** Returns all valid trash directories across all mounted storage roots. */
    fun getAllTrashDirs(): List<File> {
        return getRoots().map { File(it, TRASH_DIR_NAME) }
    }

    /** Checks if [file] is contained within [root] using both canonical and absolute paths. */
    private fun matchesRoot(file: File, root: File): Boolean {
        val filePath = file.absolutePath
        val fileCanonical = runCatching { file.canonicalPath }.getOrDefault(filePath)
        val rootPath = root.absolutePath
        val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(rootPath)

        return fileCanonical == rootCanonical ||
            fileCanonical.startsWith(rootCanonical + File.separator) ||
            filePath == rootPath ||
            filePath.startsWith(rootPath + File.separator)
    }

    /** Finds the best storage root directory for [file]. */
    fun getRootFor(file: File): File {
        val roots = getRoots()
        return roots.filter { root ->
            matchesRoot(file, root)
        }.maxByOrNull { it.path.length } ?: roots.firstOrNull() ?: file.parentFile ?: file
    }

    /** Returns the trash folder for the storage volume containing [file]. */
    fun getTrashDirFor(file: File): File {
        val root = getRootFor(file)
        return File(root, TRASH_DIR_NAME)
    }

    /** Checks whether [file] is inside any trash directory. */
    fun isInsideTrash(file: File): Boolean {
        return getAllTrashDirs().any { td ->
            matchesRoot(file, td)
        }
    }

    /**
     * Moves [files] to trash.
     * Returns the count of items moved.
     */
    suspend fun moveToTrash(
        files: List<File>,
        progress: ProgressListener = ProgressListener.None,
    ): Int = withContext(Dispatchers.IO) {
        var moved = 0
        val total = files.size
        for ((index, file) in files.withIndex()) {
            if (!file.exists()) continue
            progress.onProgress(index.toLong(), total.toLong(), file.name)

            // If already in trash, delete permanently directly
            if (isInsideTrash(file)) {
                fileOps.delete(listOf(file), progress)
                moved++
                continue
            }

            val trashDir = getTrashDirFor(file)
            val filesDir = File(trashDir, FILES_DIR_NAME).apply { mkdirs() }
            val infoDir = File(trashDir, INFO_DIR_NAME).apply { mkdirs() }

            // Ensure .nomedia exists so gallery/media scanner won't index deleted items
            val nomedia = File(trashDir, NOMEDIA_FILE_NAME)
            if (!nomedia.exists()) {
                runCatching { nomedia.createNewFile() }
            }

            val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            val trashFile = File(filesDir, id)
            val infoFile = File(infoDir, "$id.json")

            val size = if (file.isDirectory) {
                runCatching {
                    fileOps.folderStats(file).bytes
                }.getOrDefault(0L)
            } else {
                file.length()
            }

            val isDir = file.isDirectory
            val originalPath = file.absolutePath
            val originalName = file.name

            // 1. Try Java renameTo (fastest on same filesystem)
            var success = file.renameTo(trashFile)

            // 2. Try java.nio.file.Files.move (atomic / FUSE on Android API 26+)
            if (!success) {
                try {
                    java.nio.file.Files.move(
                        file.toPath(),
                        trashFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    success = true
                } catch (_: Throwable) {
                }
            }

            // 3. Fallback: Copy via native streaming, rename to id, then delete original
            if (!success) {
                try {
                    val copied = fileOps.copy(listOf(file), filesDir, progress)
                    val copiedFile = copied.firstOrNull()
                    if (copiedFile != null && copiedFile.exists()) {
                        if (copiedFile != trashFile) {
                            copiedFile.renameTo(trashFile)
                        }
                        if (trashFile.exists()) {
                            fileOps.delete(listOf(file), progress)
                            success = true
                        } else {
                            fileOps.delete(listOf(copiedFile), ProgressListener.None)
                        }
                    }
                } catch (_: Throwable) {
                    success = false
                    trashFile.deleteRecursively()
                }
            }

            // Only record metadata if the move or copy succeeded
            if (success) {
                cachedStats = null
                val metadata = TrashedItem(
                    id = id,
                    originalPath = originalPath,
                    originalName = originalName,
                    deletedAt = System.currentTimeMillis(),
                    size = size,
                    isDirectory = isDir,
                ).apply {
                    this.trashFile = trashFile
                    this.infoFile = infoFile
                }
                try {
                    if (!infoDir.exists()) infoDir.mkdirs()
                    val tempInfo = File(infoDir, "$id.tmp")
                    tempInfo.writeText(serializeMetadata(metadata))
                    if (!tempInfo.renameTo(infoFile)) {
                        infoFile.writeText(serializeMetadata(metadata))
                        tempInfo.delete()
                    }
                    moved++
                } catch (_: Throwable) {
                    runCatching { infoFile.writeText(serializeMetadata(metadata)) }
                    moved++
                }
            }
        }
        progress.onProgress(total.toLong(), total.toLong(), "")
        moved
    }

    /** Lists all items currently in trash across all storage roots. Includes self-healing for missing metadata. */
    suspend fun listTrash(): List<TrashedItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TrashedItem>()
        val seenFileIds = mutableSetOf<String>()

        for (td in getAllTrashDirs()) {
            val infoDir = File(td, INFO_DIR_NAME)
            val filesDir = File(td, FILES_DIR_NAME)
            if (!td.exists() || !td.isDirectory) continue

            // 1. Process all existing info metadata files
            if (infoDir.exists() && infoDir.isDirectory) {
                infoDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }
                val infoFiles = infoDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
                for (infoFile in infoFiles) {
                    try {
                        val text = infoFile.readText()
                        if (text.isBlank()) {
                            infoFile.delete()
                            continue
                        }
                        val item = runCatching { Json.decodeFromString<TrashedItem>(text) }.getOrNull()
                        val id = item?.id?.ifEmpty { null } ?: infoFile.nameWithoutExtension
                        val origPath = item?.originalPath ?: ""
                        val origName = item?.originalName?.ifEmpty { null } ?: File(origPath).name.ifEmpty { "Recovered file" }
                        val deletedAt = item?.deletedAt ?: infoFile.lastModified()
                        val size = item?.size ?: 0L
                        val isDir = item?.isDirectory ?: false
                        val trashFile = File(filesDir, id)

                        if (trashFile.exists()) {
                            seenFileIds.add(id)
                            list.add(
                                TrashedItem(
                                    id = id,
                                    originalPath = origPath.ifEmpty { trashFile.absolutePath },
                                    originalName = origName,
                                    deletedAt = deletedAt,
                                    size = if (size > 0) size else trashFile.length(),
                                    isDirectory = isDir || trashFile.isDirectory,
                                ).apply {
                                    this.trashFile = trashFile
                                    this.infoFile = infoFile
                                }
                            )
                        } else {
                            infoFile.delete()
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            // 2. Self-healing: Discover any files in filesDir that have no JSON metadata!
            if (filesDir.exists() && filesDir.isDirectory) {
                val files = filesDir.listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.name in seenFileIds) continue
                    val id = f.name
                    val infoFile = File(infoDir, "$id.json")
                    val isDir = f.isDirectory
                    val size = if (isDir) {
                        runCatching { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
                    } else {
                        f.length()
                    }
                    val originalName = if (id.contains('_')) "Recovered item (${id.take(18)})" else id
                    val item = TrashedItem(
                        id = id,
                        originalPath = f.absolutePath,
                        originalName = originalName,
                        deletedAt = id.substringBefore('_').toLongOrNull() ?: f.lastModified(),
                        size = size,
                        isDirectory = isDir,
                    ).apply {
                        this.trashFile = f
                        this.infoFile = infoFile
                    }
                    runCatching {
                        if (!infoDir.exists()) infoDir.mkdirs()
                        infoFile.writeText(serializeMetadata(item))
                    }
                    seenFileIds.add(id)
                    list.add(item)
                }
            }
        }
        val stats = TrashStats(count = list.size, totalBytes = list.sumOf { it.size })
        cachedStats = stats
        list.sortedByDescending { it.deletedAt }
    }

    /** Restores [item] to its original location (handling collisions by appending counter if needed). */
    suspend fun restore(item: TrashedItem): File = withContext(Dispatchers.IO) {
        val orig = File(item.originalPath)
        val parent = orig.parentFile ?: getRootFor(item.trashFile)
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val target = if (orig.exists()) {
            IoSupport.uniqueChild(parent, item.originalName)
        } else {
            orig
        }

        var restored = item.trashFile.renameTo(target)
        if (!restored) {
            try {
                java.nio.file.Files.move(
                    item.trashFile.toPath(),
                    target.toPath(),
                )
                restored = true
            } catch (_: Throwable) {
            }
        }
        if (!restored) {
            try {
                val copied = fileOps.copy(listOf(item.trashFile), parent, ProgressListener.None)
                val copiedFile = copied.firstOrNull()
                if (copiedFile != null && copiedFile.exists()) {
                    if (copiedFile != target) {
                        copiedFile.renameTo(target)
                    }
                    if (target.exists()) {
                        fileOps.delete(listOf(item.trashFile), ProgressListener.None)
                        restored = true
                    } else {
                        fileOps.delete(listOf(copiedFile), ProgressListener.None)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (restored) {
            cachedStats = null
            item.infoFile.delete()
            target
        } else {
            throw ZibzipException.Io("Could not restore \"${item.originalName}\"")
        }
    }

    /** Permanently deletes [item] from trash. */
    suspend fun deletePermanently(
        item: TrashedItem,
        progress: ProgressListener = ProgressListener.None,
    ): Boolean = withContext(Dispatchers.IO) {
        if (item.trashFile.exists()) {
            fileOps.delete(listOf(item.trashFile), progress)
        }
        val isGone = !item.trashFile.exists()
        if (isGone) {
            cachedStats = null
            item.infoFile.delete()
        }
        isGone
    }

    /** Empties all trash directories across all volumes. */
    suspend fun emptyTrash(progress: ProgressListener = ProgressListener.None): Int = withContext(Dispatchers.IO) {
        cachedStats = null
        var count = 0
        for (td in getAllTrashDirs()) {
            val filesDir = File(td, FILES_DIR_NAME)
            val infoDir = File(td, INFO_DIR_NAME)
            val files = filesDir.listFiles()?.toList() ?: emptyList()
            if (files.isNotEmpty()) {
                fileOps.delete(files, progress)
                count += files.size
            }
            infoDir.listFiles()?.forEach {
                it.delete()
            }
            val nomedia = File(td, NOMEDIA_FILE_NAME)
            if (!nomedia.exists()) {
                runCatching { nomedia.createNewFile() }
            }
        }
        count
    }

    /** Returns count of items and total size across all trash folders. */
    suspend fun getTrashStats(): TrashStats = withContext(Dispatchers.IO) {
        cachedStats?.let { return@withContext it }
        var totalCount = 0
        var totalBytes = 0L

        for (td in getAllTrashDirs()) {
            val filesDir = File(td, FILES_DIR_NAME)
            val infoDir = File(td, INFO_DIR_NAME)
            if (!infoDir.exists() && !filesDir.exists()) continue

            if (infoDir.exists() && infoDir.isDirectory) {
                infoDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }
            }
            val infoFiles = infoDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
            val seenIds = HashSet<String>(infoFiles.size)

            for (infoFile in infoFiles) {
                val id = infoFile.nameWithoutExtension
                val trashFile = File(filesDir, id)
                if (trashFile.exists()) {
                    seenIds.add(id)
                    totalCount++
                    val text = runCatching { infoFile.readText() }.getOrNull() ?: ""
                    val sizeMatch = SIZE_REGEX.find(text)
                    val size = sizeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    totalBytes += if (size > 0) size else trashFile.length()
                } else {
                    infoFile.delete()
                }
            }

            val orphaned = filesDir.listFiles()?.filter { it.name !in seenIds } ?: emptyList()
            for (f in orphaned) {
                totalCount++
                totalBytes += if (f.isDirectory) {
                    runCatching { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
                } else {
                    f.length()
                }
            }
        }
        val stats = TrashStats(count = totalCount, totalBytes = totalBytes)
        cachedStats = stats
        stats
    }

    private fun serializeMetadata(item: TrashedItem): String {
        return Json.encodeToString(item)
    }
}
