package dev.neroices.zibzip

import android.app.Application
import android.content.Context
import dev.neroices.zibzip.data.ArchiveManager
import dev.neroices.zibzip.data.CrashReportManager
import dev.neroices.zibzip.data.FileLister
import dev.neroices.zibzip.data.FileOperations
import dev.neroices.zibzip.data.OperationRunner
import dev.neroices.zibzip.data.SettingsStore
import dev.neroices.zibzip.data.StorageProvider
import dev.neroices.zibzip.data.TrashManager
import dev.neroices.zibzip.domain.FileClipboard
import dev.neroices.zibzip.domain.OpState
import dev.neroices.zibzip.service.OperationService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application object doubling as a tiny service locator. Everything here is cheap to create and
 * either stateless or intentionally process-wide (the operation runner, settings, messages).
 */
class ZibzipApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val storage: StorageProvider by lazy { StorageProvider(this) }
    val lister = FileLister()
    val fileOps = FileOperations()
    val trash: TrashManager by lazy { TrashManager(storage, fileOps) }
    val archives = ArchiveManager()
    val runner = OperationRunner(appScope)
    val audioPlayer by lazy { dev.neroices.zibzip.audio.AudioPlayerController(this) }

    /** Copy / Move clipboard, shared by the Files tab and the archive viewer ("add to archive"). */
    val clipboard = MutableStateFlow<FileClipboard?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** One-shot user messages (shown as snackbars by the root UI). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReportManager.init(this)
        // Files extracted only to be opened externally live in the cache; drop leftovers from last run.
        appScope.launch(Dispatchers.IO) { File(cacheDir, OPEN_CACHE_DIR).deleteRecursively() }
        // Run a foreground service for as long as any operation is active.
        appScope.launch {
            runner.tasks.map { it.isNotEmpty() }.distinctUntilChanged().collect { running ->
                if (running) OperationService.start(this@ZibzipApp)
            }
        }
    }

    companion object {
        const val OPEN_CACHE_DIR = "open"
    }
}

val Context.zibzipApp: ZibzipApp get() = applicationContext as ZibzipApp
