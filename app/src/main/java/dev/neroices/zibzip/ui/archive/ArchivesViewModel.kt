package dev.neroices.zibzip.ui.archive

import android.app.Application
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.neroices.zibzip.ZibzipApp
import dev.neroices.zibzip.data.AppSettings
import dev.neroices.zibzip.data.TrashManager
import dev.neroices.zibzip.domain.ArchiveEntryItem
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ArchiveTree
import dev.neroices.zibzip.domain.ConflictPolicy
import dev.neroices.zibzip.domain.FileClipboard
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.FileKind
import dev.neroices.zibzip.domain.FileTypes
import dev.neroices.zibzip.domain.ZibzipException
import dev.neroices.zibzip.domain.ProgressListener
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.domain.userMessage
import dev.neroices.zibzip.ui.files.ExtractTarget
import dev.neroices.zibzip.ui.openFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Archives found on the device (the "Archives" tab). */
data class ArchiveListState(
    val items: List<FileItem> = emptyList(),
    val scanning: Boolean = false,
    val scanned: Boolean = false,
)

sealed interface ViewerDialog {
    data class Extract(val selectionOnly: Boolean) : ViewerDialog
    data class Password(val wrong: Boolean) : ViewerDialog
    data object DeleteEntries : ViewerDialog
}

/** The archive currently open in the viewer. */
data class ViewerState(
    val path: String,
    val name: String,
    val format: ArchiveFormat?,
    val loading: Boolean = true,
    val error: String? = null,
    /** Folder inside the archive being shown ("" = root). */
    val dir: String = "",
    val visible: List<ArchiveEntryItem> = emptyList(),
    val fileCount: Int = 0,
    val totalSize: Long = 0,
    val hasEncrypted: Boolean = false,
    val password: String? = null,
    val selection: Set<String> = emptySet(),
    val dialog: ViewerDialog? = null,
) {
    /** Only ZIP archives can be modified in place. */
    val canEdit: Boolean get() = format == ArchiveFormat.ZIP
}

class ArchivesViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ZibzipApp get() = getApplication()

    private val _list = MutableStateFlow(ArchiveListState())
    val list: StateFlow<ArchiveListState> = _list.asStateFlow()

    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    val clipboard: StateFlow<FileClipboard?> = app.clipboard
    val settings: StateFlow<AppSettings> = app.settings.settings

    private var tree: ArchiveTree? = null
    private var scanJob: Job? = null
    private var loadJob: Job? = null
    private var reloadAfterOp = false

    init {
        // After add / remove finished, show the archive's new contents.
        viewModelScope.launch {
            app.runner.results.collect {
                if (reloadAfterOp) {
                    reloadAfterOp = false
                    if (_viewer.value != null) load(_viewer.value?.password, keepDir = true)
                }
            }
        }
    }

    private fun queryMediaStoreArchives(): List<FileItem> {
        val context = app.applicationContext
        val items = ArrayList<FileItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val extConditions = ArchiveFormat.entries.flatMap { it.suffixes }
            .map { it.removePrefix(".") }
            .distinct()
            .joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE '%.$it'" }

        try {
            context.contentResolver.query(
                uri,
                projection,
                extConditions,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = (if (dataCol >= 0) cursor.getString(dataCol) else null) ?: continue
                    if (path.contains("/${TrashManager.TRASH_DIR_NAME}/")) continue
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: file.name else file.name
                    if (ArchiveFormat.detect(name) == null) continue
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else file.length()
                    val dateSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val lastModified = if (dateSec > 0) dateSec * 1000L else file.lastModified()
                    items.add(
                        FileItem(
                            path = path,
                            name = name,
                            isDirectory = false,
                            size = size,
                            lastModified = lastModified,
                            isHidden = name.startsWith("."),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Ignore MediaStore errors on ROMs with restricted content providers
        }
        return items
    }

    fun scan(force: Boolean = false) {
        val s = _list.value
        if (!force && (s.scanned || s.scanning)) return
        scanJob?.cancel()
        _list.value = ArchiveListState(scanning = true)
        scanJob = viewModelScope.launch {
            val foundMap = LinkedHashMap<String, FileItem>()
            try {
                // 1. Fast MediaStore query (instantly discovers indexed archives across all storage)
                val mediaStoreItems = withContext(Dispatchers.IO) { queryMediaStoreArchives() }
                for (item in mediaStoreItems) {
                    foundMap[item.path] = item
                }
                if (foundMap.isNotEmpty()) {
                    _list.update { it.copy(items = foundMap.values.sortedByDescending { f -> f.lastModified }) }
                }

                // 2. Direct filesystem scan across all mounted storage volumes (finds unindexed files, SD cards, USB OTG)
                val volumes = withContext(Dispatchers.IO) { app.storage.volumes(withStats = false) }
                val roots = volumes.map { File(it.path) }.ifEmpty { listOf(app.storage.primaryRoot) }
                val archiveExtensions = ArchiveFormat.entries.flatMap { it.suffixes }.distinct()

                for (root in roots) {
                    if (!root.exists() || !root.isDirectory) continue
                    app.lister.search(
                        root = root,
                        showHidden = app.settings.settings.value.showHidden,
                        limit = 5000,
                        extensions = archiveExtensions,
                    ) { name, isDirectory -> !isDirectory && ArchiveFormat.detect(name) != null }
                        .collect { batch ->
                            var changed = false
                            for (item in batch) {
                                if (foundMap.put(item.path, item) == null) {
                                    changed = true
                                }
                            }
                            if (changed) {
                                _list.update { it.copy(items = foundMap.values.sortedByDescending { f -> f.lastModified }) }
                            }
                        }
                }
            } finally {
                _list.update { it.copy(scanning = false, scanned = true) }
            }
        }
    }

    fun openArchive(path: String) {
        val file = File(path)
        tree = null
        _viewer.value = ViewerState(path = path, name = file.name, format = ArchiveFormat.detect(file.name))
        load(password = null, keepDir = false)
    }

    fun closeViewer() {
        loadJob?.cancel()
        tree = null
        _viewer.value = null
    }

    private fun load(password: String?, keepDir: Boolean) {
        val v = _viewer.value ?: return
        loadJob?.cancel()
        _viewer.update { it?.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val entries = app.archives.list(File(v.path), password)
                val t = withContext(Dispatchers.Default) { ArchiveTree(entries) }
                tree = t
                val dir = if (keepDir && t.hasFolder(v.dir)) v.dir else ""
                _viewer.update { s ->
                    s?.copy(
                        loading = false, error = null, password = password, dir = dir,
                        visible = t.childrenOf(dir), fileCount = t.fileCount, totalSize = t.totalSize,
                        hasEncrypted = entries.any { it.isEncrypted }, selection = emptySet(), dialog = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ZibzipException.PasswordRequired) {
                _viewer.update { it?.copy(loading = false, dialog = ViewerDialog.Password(wrong = false)) }
            } catch (e: ZibzipException.WrongPassword) {
                _viewer.update { it?.copy(loading = false, dialog = ViewerDialog.Password(wrong = true)) }
            } catch (t: Throwable) {
                _viewer.update { it?.copy(loading = false, error = t.userMessage()) }
            }
        }
    }

    fun setPassword(password: String) = load(password, keepDir = true)

    fun enter(dir: String) {
        val t = tree ?: return
        _viewer.update { it?.copy(dir = dir.trim('/'), visible = t.childrenOf(dir), selection = emptySet()) }
    }

    /** Back button inside the viewer: clear selection, go up a folder, then close. */
    fun back() {
        val v = _viewer.value ?: return
        when {
            v.selection.isNotEmpty() -> clearSelection()
            v.dir.isNotEmpty() -> enter(ArchiveTree.parentOf(v.dir))
            else -> closeViewer()
        }
    }

    /** Navigates up to the parent directory inside the archive. */
    fun navigateUp() {
        val v = _viewer.value ?: return
        if (v.dir.isNotEmpty()) {
            enter(ArchiveTree.parentOf(v.dir))
        }
    }

    fun toggleSelection(path: String) {
        _viewer.update { v -> v?.copy(selection = if (path in v.selection) v.selection - path else v.selection + path) }
    }

    fun selectAll() {
        _viewer.update { v -> v?.copy(selection = v.visible.mapTo(HashSet()) { it.path }) }
    }

    fun clearSelection() = _viewer.update { it?.copy(selection = emptySet()) }

    fun showDialog(dialog: ViewerDialog) = _viewer.update { it?.copy(dialog = dialog) }

    fun dismissDialog() {
        val v = _viewer.value ?: return
        // Dismissing the password prompt of an archive that could not even be listed leaves nothing to show.
        if (v.dialog is ViewerDialog.Password && tree == null) closeViewer()
        else _viewer.update { it?.copy(dialog = null) }
    }

    private fun startOp(title: String, keepScreenOn: Boolean = false, block: suspend (ProgressListener) -> String): Boolean {
        val started = app.runner.start(title, keepScreenOn, block)
        if (!started) app.toast("Too many operations running")
        return started
    }

    fun folderNameFor(name: String): String =
        (ArchiveFormat.detect(name)?.stripSuffix(name) ?: File(name).nameWithoutExtension).ifBlank { "Extracted" }

    fun extract(selectionOnly: Boolean, target: ExtractTarget, conflict: ConflictPolicy, password: String?, keepScreenOn: Boolean = false) {
        val v = _viewer.value ?: return
        dismissDialog()
        app.settings.update { it.copy(keepScreenOn = keepScreenOn) }
        val file = File(v.path)
        val parent = file.parentFile ?: return
        val defaultFolderName = folderNameFor(file.name)
        val dest = when (target) {
            ExtractTarget.SameFolder -> parent
            ExtractTarget.NamedFolder -> File(parent, defaultFolderName)
            is ExtractTarget.CustomFolder -> {
                val base = File(target.path)
                if (target.createSubfolder) File(base, defaultFolderName) else base
            }
        }
        val selection = if (selectionOnly) v.selection else null
        val base = if (selectionOnly) v.dir else ""
        val pw = (password ?: v.password)?.takeIf { it.isNotEmpty() }
        val started = startOp("Extracting ${file.name}", keepScreenOn = keepScreenOn) { p ->
            val n = app.archives.extract(
                archive = file, destDir = dest, selection = selection, basePath = base,
                password = pw, conflict = conflict, progress = p,
            )
            "Extracted $n ${if (n == 1) "file" else "files"} to ${dest.name}"
        }
        if (started) clearSelection()
    }

    /** Extracts one entry to the cache and opens it with the in-app viewer or system handler. */
    fun preview(
        entry: ArchiveEntryItem,
        onOpenImage: ((String) -> Unit)? = null,
        onOpenVideo: ((String) -> Unit)? = null,
        onOpenText: ((String) -> Unit)? = null,
        onOpenPdf: ((String) -> Unit)? = null,
    ) {
        val v = _viewer.value ?: return
        if (entry.isEncrypted && v.password == null) {
            showDialog(ViewerDialog.Password(wrong = false))
            return
        }
        val out = File(app.cacheDir, "${ZibzipApp.OPEN_CACHE_DIR}/${System.nanoTime()}")
        startOp("Opening ${entry.name}") { p ->
            app.archives.extract(
                archive = File(v.path), destDir = out, selection = setOf(entry.path),
                basePath = ArchiveTree.parentOf(entry.path), password = v.password,
                conflict = ConflictPolicy.Overwrite, progress = p,
            )
            val extractedFile = File(out, entry.name)
            val extractedPath = extractedFile.path
            val isPdf = entry.name.endsWith(".pdf", ignoreCase = true) && onOpenPdf != null
            val isText = (FileTypes.isTextFile(entry.name) || FileTypes.isTextFile(extractedFile)) && onOpenText != null
            val isAudio = FileTypes.isAudioFile(entry.name)
            val isImage = FileTypes.kindOf(entry.name, false) == FileKind.Image && onOpenImage != null
            val isVideo = (FileTypes.kindOf(entry.name, false) == FileKind.Video || FileTypes.isVideoFile(entry.name)) && onOpenVideo != null
            withContext(Dispatchers.Main) {
                if (isImage) {
                    onOpenImage(extractedPath)
                } else if (isVideo) {
                    onOpenVideo(extractedPath)
                } else if (isPdf) {
                    onOpenPdf(extractedPath)
                } else if (isText) {
                    onOpenText(extractedPath)
                } else if (isAudio) {
                    app.audioPlayer.playFile(extractedPath)
                } else {
                    app.openFile(extractedPath)
                }
            }
            "" // nothing to announce: the viewer app opening is the feedback
        }
    }

    fun deleteSelectedEntries() {
        val v = _viewer.value ?: return
        dismissDialog()
        val file = File(v.path)
        val paths = v.selection
        reloadAfterOp = true
        if (!startOp("Removing from ${file.name}") { p -> "Removed ${itemsLabel(app.archives.removeEntries(file, paths, p))}" }) {
            reloadAfterOp = false
        }
    }

    /** Adds whatever is on the Copy / Move clipboard into the open folder of the (ZIP) archive. */
    fun addClipboardItems() {
        val v = _viewer.value ?: return
        val clip = app.clipboard.value ?: return
        val files = clip.paths.map(::File).filter { it.exists() }
        if (files.isEmpty()) {
            app.toast("The copied items no longer exist")
            return
        }
        val file = File(v.path)
        reloadAfterOp = true
        if (!startOp("Adding to ${file.name}") { p ->
                "Added ${itemsLabel(app.archives.addEntries(file, v.dir, files, 5, v.password, p))}"
            }
        ) reloadAfterOp = false
    }
}
