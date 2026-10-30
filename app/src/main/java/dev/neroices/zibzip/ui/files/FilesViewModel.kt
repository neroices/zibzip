package dev.neroices.zibzip.ui.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.neroices.zibzip.ZibzipApp
import dev.neroices.zibzip.data.AppSettings
import dev.neroices.zibzip.data.FolderStats
import dev.neroices.zibzip.data.IoSupport
import dev.neroices.zibzip.data.TrashedItem
import dev.neroices.zibzip.data.TrashStats
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ConflictPolicy
import dev.neroices.zibzip.domain.FileClipboard
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.ProgressListener
import dev.neroices.zibzip.domain.StorageVolumeInfo
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.domain.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Modal dialogs the Files tab can show. */
sealed interface FileDialog {
    data object NewFolder : FileDialog
    data object NewFile : FileDialog
    data class Rename(val item: FileItem) : FileDialog
    data class Delete(val items: List<FileItem>) : FileDialog
    data class Details(val items: List<FileItem>) : FileDialog
    data class Compress(val items: List<FileItem>) : FileDialog
    data class Extract(val archive: FileItem) : FileDialog
    data object Trash : FileDialog
}

/** Where extracted files go. */
sealed interface ExtractTarget {
    data object SameFolder : ExtractTarget
    data object NamedFolder : ExtractTarget
    data class CustomFolder(val path: String, val createSubfolder: Boolean = false) : ExtractTarget
}

data class FilesUiState(
    val hasAccess: Boolean = true,
    val volumes: List<StorageVolumeInfo> = emptyList(),
    /** Open folder, or `null` for the "all storage volumes" screen. */
    val path: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val searchOpen: Boolean = false,
    val query: String = "",
    val searchRunning: Boolean = false,
    val dialog: FileDialog? = null,
) {
    val isSearching: Boolean get() = searchOpen && query.isNotBlank()

    /** True when "back" has nowhere further to go inside the app (a volume root or the overview). */
    val atRoot: Boolean get() = path == null || volumes.any { it.path == path }
    val canNavigateUp: Boolean get() = !atRoot && path != null
}

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ZibzipApp get() = getApplication()

    private val _ui = MutableStateFlow(FilesUiState(hasAccess = app.storage.hasAccess()))
    val ui: StateFlow<FilesUiState> = _ui.asStateFlow()

    /** Unsorted entries of the open folder (or the current search results). */
    private val raw = MutableStateFlow<List<FileItem>>(emptyList())

    /** The open folder's own listing, kept so closing a search can restore it instantly. */
    private var dirItems: List<FileItem> = emptyList()

    /** Sorted off the main thread; re-sorts when the sort option changes. */
    val items: StateFlow<List<FileItem>> = combine(
        raw,
        app.settings.settings.map { it.sort }.distinctUntilChanged(),
    ) { list, sort -> list.sortedWith(sort.comparator()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> = app.settings.settings
    val clipboard: StateFlow<FileClipboard?> = app.clipboard

    private val _trashItems = MutableStateFlow<List<TrashedItem>>(emptyList())
    val trashItems: StateFlow<List<TrashedItem>> = _trashItems.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        if (_ui.value.hasAccess) start()
        viewModelScope.launch {
            app.settings.settings.map { it.showHidden }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        // Whatever an operation did to the disk, show it.
        viewModelScope.launch { app.runner.results.collect { refresh() } }
    }

    /** Called whenever the app returns to the foreground (e.g. back from the permission screen). */
    fun onResume() {
        val has = app.storage.hasAccess()
        if (has != _ui.value.hasAccess) {
            _ui.update { it.copy(hasAccess = has) }
            if (has) start()
        } else if (has) {
            refresh(silent = true)
        }
    }

    private fun start() {
        loadVolumes()
        if (_ui.value.path == null) open(app.storage.primaryRoot.path)
    }

    private fun loadVolumes() {
        viewModelScope.launch {
            val volumes = withContext(Dispatchers.IO) { app.storage.volumes() }
            _ui.update { it.copy(volumes = volumes) }
        }
    }

    /** Opens [path]; `null` shows the storage volumes overview. */
    fun open(path: String?) {
        searchJob?.cancel()
        loadJob?.cancel()
        _ui.update {
            it.copy(
                path = path, selection = emptySet(), searchOpen = false, query = "",
                searchRunning = false, error = null,
            )
        }
        if (path == null) {
            dirItems = emptyList()
            raw.value = emptyList()
            loadVolumes()
        } else {
            raw.value = emptyList()
            load(path, silent = false)
        }
    }

    /** Handles the system back button. Returns false when there is nothing left to go back to. */
    fun back(): Boolean {
        val s = _ui.value
        val path = s.path
        when {
            s.selection.isNotEmpty() -> clearSelection()
            s.searchOpen -> closeSearch()
            path == null -> return false
            else -> open(parentOf(path) ?: return false)
        }
        return true
    }

    /** Navigates up to the parent directory if possible. Returns true if navigated. */
    fun navigateUp(): Boolean {
        val path = _ui.value.path ?: return false
        val parent = parentOf(path) ?: return false
        open(parent)
        return true
    }

    fun canNavigateUp(): Boolean {
        val path = _ui.value.path ?: return false
        return parentOf(path) != null
    }

    internal fun parentOf(path: String): String? {
        val roots = _ui.value.volumes.map { it.path } + app.storage.primaryRoot.path
        if (path in roots) return null
        val parent = File(path).parent ?: return null
        return if (roots.any { parent == it || parent.startsWith("$it/") }) parent else null
    }

    private fun load(path: String, silent: Boolean) {
        loadJob?.cancel()
        if (!silent) _ui.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val list = app.lister.list(File(path), settings.value.showHidden)
                dirItems = list
                if (!_ui.value.isSearching) raw.value = list
                _ui.update { it.copy(loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                dirItems = emptyList()
                if (!_ui.value.isSearching) raw.value = emptyList()
                _ui.update { it.copy(loading = false, error = t.userMessage()) }
            }
        }
    }

    fun refresh(silent: Boolean = true) {
        val s = _ui.value
        val path = s.path ?: return
        if (s.isSearching) runSearch(s.query, immediately = true) else load(path, silent)
    }

    fun openSearch() {
        _ui.update { it.copy(searchOpen = true, selection = emptySet()) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchOpen = false, query = "", searchRunning = false) }
        raw.value = dirItems
    }

    fun setQuery(query: String) {
        _ui.update { it.copy(query = query) }
        if (query.isBlank()) {
            searchJob?.cancel()
            _ui.update { it.copy(searchRunning = false) }
            raw.value = dirItems
        } else {
            runSearch(query, immediately = false)
        }
    }

    private fun runSearch(query: String, immediately: Boolean) {
        val path = _ui.value.path ?: return
        val needle = query.trim()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediately) delay(250) // debounce typing
            _ui.update { it.copy(searchRunning = true) }
            raw.value = emptyList()
            val found = ArrayList<FileItem>()
            try {
                app.lister.search(
                    root = File(path),
                    showHidden = settings.value.showHidden,
                    query = needle,
                ).collect { batch ->
                    found += batch
                    raw.value = found.toList()
                }
            } finally {
                _ui.update { it.copy(searchRunning = false) }
            }
        }
    }

    fun toggleSelection(path: String) {
        _ui.update { s ->
            s.copy(selection = if (path in s.selection) s.selection - path else s.selection + path)
        }
    }

    fun selectAll() {
        _ui.update { s -> s.copy(selection = items.value.mapTo(HashSet(items.value.size)) { it.path }) }
    }

    fun clearSelection() {
        _ui.update { it.copy(selection = emptySet()) }
    }

    fun selectedItems(): List<FileItem> {
        val sel = _ui.value.selection
        return items.value.filter { it.path in sel }
    }

    fun showDialog(dialog: FileDialog) = _ui.update { it.copy(dialog = dialog) }
    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    private fun currentDir(): File? = _ui.value.path?.let(::File)

    private fun quick(block: () -> String?) {
        dismissDialog()
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { block() }
                if (message != null) app.toast(message)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                app.toast(t.userMessage())
            }
            clearSelection()
            refresh()
        }
    }

    fun createFolder(name: String) {
        val dir = currentDir() ?: return
        quick { app.fileOps.createFolder(dir, name); null }
    }

    fun createFile(name: String) {
        val dir = currentDir() ?: return
        quick { app.fileOps.createFile(dir, name); null }
    }

    fun rename(item: FileItem, newName: String) = quick { app.fileOps.rename(File(item.path), newName); null }

    private fun startOp(
        title: String,
        keepScreenOn: Boolean = false,
        trackSpeed: Boolean = true,
        block: suspend (ProgressListener) -> String,
    ): Boolean {
        val started = app.runner.start(title, keepScreenOn, trackSpeed, block)
        if (!started) app.toast("Too many operations running")
        return started
    }

    fun isInsideTrash(file: File): Boolean = app.trash.isInsideTrash(file)

    fun delete(items: List<FileItem>) {
        dismissDialog()
        val files = items.map { File(it.path) }
        val useTrash = settings.value.useTrash && !files.any { app.trash.isInsideTrash(it) }
        val actionTitle = if (useTrash) "Moving to trash" else "Deleting ${itemsLabel(files.size)}"
        if (startOp(actionTitle, trackSpeed = false) { p ->
                if (useTrash) {
                    val count = app.trash.moveToTrash(files, p)
                    "Moved ${itemsLabel(count)} to trash"
                } else {
                    "Deleted ${itemsLabel(app.fileOps.delete(files, p))}"
                }
            }
        ) clearSelection()
    }

    fun refreshTrash() {
        viewModelScope.launch {
            _trashItems.value = app.trash.listTrash()
        }
    }

    fun restoreFromTrash(item: TrashedItem) {
        viewModelScope.launch {
            try {
                val restored = app.trash.restore(item)
                app.toast("Restored \"${restored.name}\"")
                refreshTrash()
                refresh()
            } catch (t: Throwable) {
                app.toast(t.userMessage())
            }
        }
    }

    fun restoreAllFromTrash() {
        viewModelScope.launch {
            try {
                val items = _trashItems.value
                var count = 0
                for (item in items) {
                    runCatching { app.trash.restore(item) }.onSuccess { count++ }
                }
                app.toast("Restored ${itemsLabel(count)}")
                refreshTrash()
                refresh()
            } catch (t: Throwable) {
                app.toast(t.userMessage())
            }
        }
    }

    fun deletePermanentlyFromTrash(item: TrashedItem) {
        startOp("Deleting permanently", trackSpeed = false) { p ->
            val deleted = app.trash.deletePermanently(item, p)
            refreshTrash()
            if (deleted) {
                "Permanently deleted \"${item.originalName}\""
            } else {
                "Failed to delete \"${item.originalName}\""
            }
        }
    }

    fun emptyTrash() {
        startOp("Emptying trash", trackSpeed = false) { p ->
            val count = app.trash.emptyTrash(p)
            refreshTrash()
            if (count > 0) "Emptied ${itemsLabel(count)} from trash" else "Trash is empty"
        }
    }

    fun copySelection(cut: Boolean) {
        val paths = selectedItems().map { it.path }
        if (paths.isEmpty()) return
        app.clipboard.value = FileClipboard(paths, cut)
        clearSelection()
    }

    fun cancelClipboard() {
        app.clipboard.value = null
    }

    fun paste() {
        val clip = app.clipboard.value ?: return
        val dest = currentDir() ?: return
        val files = clip.paths.map(::File).filter { it.exists() }
        if (files.isEmpty()) {
            app.clipboard.value = null
            app.toast("The copied items no longer exist")
            return
        }
        val started = if (clip.cut) {
            startOp("Moving ${itemsLabel(files.size)}") { p -> "Moved ${itemsLabel(app.fileOps.move(files, dest, p))}" }
        } else {
            startOp("Copying ${itemsLabel(files.size)}") { p ->
                "Copied ${itemsLabel(app.fileOps.copy(files, dest, p).size)}"
            }
        }
        if (started && clip.cut) app.clipboard.value = null
    }

    fun compress(items: List<FileItem>, baseName: String, format: ArchiveFormat, level: Int, password: String?, keepScreenOn: Boolean = false) {
        dismissDialog()
        updateSettings { it.copy(keepScreenOn = keepScreenOn) }
        val dir = currentDir() ?: return
        val clean = baseName.trim().ifEmpty { "Archive" }
        val fileName = if (clean.lowercase().endsWith(format.primarySuffix)) clean else clean + format.primarySuffix
        val files = items.map { File(it.path) }
        val started = startOp("Compressing to $fileName", keepScreenOn = keepScreenOn) { p ->
            val out = IoSupport.uniqueChild(dir, fileName)
            app.archives.create(files, out, format, level, password?.takeIf { it.isNotEmpty() }, p)
            "Created ${out.name}"
        }
        if (started) clearSelection()
    }

    fun extract(archive: FileItem, target: ExtractTarget, conflict: ConflictPolicy, password: String?, keepScreenOn: Boolean = false) {
        dismissDialog()
        updateSettings { it.copy(keepScreenOn = keepScreenOn) }
        val file = File(archive.path)
        val parent = file.parentFile ?: return
        val format = ArchiveFormat.detect(file.name)
        val defaultFolderName = (format?.stripSuffix(file.name) ?: file.nameWithoutExtension).ifBlank { "Extracted" }
        val dest = when (target) {
            ExtractTarget.SameFolder -> parent
            ExtractTarget.NamedFolder -> File(parent, defaultFolderName)
            is ExtractTarget.CustomFolder -> {
                val base = File(target.path)
                if (target.createSubfolder) File(base, defaultFolderName) else base
            }
        }
        val started = startOp("Extracting ${file.name}", keepScreenOn = keepScreenOn) { p ->
            val n = app.archives.extract(
                archive = file, destDir = dest, password = password?.takeIf { it.isNotEmpty() },
                conflict = conflict, progress = p,
            )
            "Extracted $n ${if (n == 1) "file" else "files"} to ${dest.name}"
        }
        if (started) clearSelection()
    }

    suspend fun folderStats(path: String): FolderStats = app.fileOps.folderStats(File(path))

    suspend fun calculateSha256(path: String): String = app.fileOps.sha256(File(path))

    fun updateSettings(transform: (AppSettings) -> AppSettings) = app.settings.update(transform)

    /** Name suggested in the Compress dialog. */
    fun suggestedArchiveName(items: List<FileItem>): String = when {
        items.size == 1 && items[0].isDirectory -> items[0].name
        items.size == 1 -> items[0].name.substringBeforeLast('.', items[0].name)
        else -> currentDir()?.name?.ifBlank { null } ?: "Archive"
    }
}
