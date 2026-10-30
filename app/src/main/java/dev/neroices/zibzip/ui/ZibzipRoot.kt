package dev.neroices.zibzip.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.neroices.zibzip.data.CrashReportManager
import dev.neroices.zibzip.data.OpResult
import dev.neroices.zibzip.domain.OpState
import dev.neroices.zibzip.zibzipApp
import dev.neroices.zibzip.ui.archive.ArchiveViewerScreen
import dev.neroices.zibzip.ui.archive.ArchivesScreen
import dev.neroices.zibzip.ui.archive.ArchivesViewModel
import dev.neroices.zibzip.ui.archive.ViewerDialog
import dev.neroices.zibzip.ui.components.ArchiveSelectionBar
import dev.neroices.zibzip.ui.components.CrashReportDialog
import dev.neroices.zibzip.ui.components.FloatingNavBar
import dev.neroices.zibzip.ui.components.OperationCard
import dev.neroices.zibzip.ui.components.OperationsContainer
import dev.neroices.zibzip.ui.components.PasteBar
import dev.neroices.zibzip.ui.components.Tab
import dev.neroices.zibzip.ui.files.FilesScreen
import dev.neroices.zibzip.ui.files.FilesSelectionBar
import dev.neroices.zibzip.ui.files.FilesViewModel
import dev.neroices.zibzip.ui.editor.TextEditorArgs
import dev.neroices.zibzip.ui.editor.TextEditorScreen
import dev.neroices.zibzip.ui.image.ImageViewerArgs
import dev.neroices.zibzip.ui.image.ImageViewerScreen
import dev.neroices.zibzip.ui.pdf.PdfViewerArgs
import dev.neroices.zibzip.ui.pdf.PdfViewerScreen
import dev.neroices.zibzip.ui.video.VideoPlayerArgs
import dev.neroices.zibzip.ui.video.VideoPlayerScreen
import dev.neroices.zibzip.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Root composable: permission gate, the three tabs, the archive viewer overlay and the floating
 * chrome (navigation pill, selection / paste bars, operation progress, snackbars).
 */
@Composable
fun ZibzipRoot() {
    val context = LocalContext.current
    val app = context.zibzipApp
    val filesVm: FilesViewModel = viewModel()
    val archivesVm: ArchivesViewModel = viewModel()

    val filesUi by filesVm.ui.collectAsStateWithLifecycle()
    val viewerState by archivesVm.viewer.collectAsStateWithLifecycle()
    val runningTasks by app.runner.tasks.collectAsStateWithLifecycle()
    val clipboardState by filesVm.clipboard.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val isAudioActive by remember(app.audioPlayer) {
        app.audioPlayer.state.map { it != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = app.audioPlayer.state.value != null)

    val density = LocalDensity.current
    var floatingChromeHeightPx by remember { mutableIntStateOf(0) }
    val hasTasks = runningTasks.isNotEmpty()

    val fallbackChromeHeight = 80.dp + (if (isAudioActive) 130.dp else 0.dp) + (if (hasTasks) 64.dp else 0.dp)
    val effectiveChromeHeight = if (floatingChromeHeightPx > 0) with(density) { floatingChromeHeightPx.toDp() } else fallbackChromeHeight
    val targetClearance = effectiveChromeHeight + 76.dp
    val bottomClearance by animateDpAsState(
        targetValue = targetClearance,
        label = "floatingBarClearance",
    )

    val shouldKeepScreenOn = runningTasks.any { it.keepScreenOn }
    val view = LocalView.current
    DisposableEffect(shouldKeepScreenOn) {
        view.keepScreenOn = shouldKeepScreenOn
        val window = (context as? Activity)?.window
        if (shouldKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Files) }
    var imageViewerArgs by remember { mutableStateOf<ImageViewerArgs?>(null) }
    var videoPlayerArgs by remember { mutableStateOf<VideoPlayerArgs?>(null) }
    var textEditorArgs by remember { mutableStateOf<TextEditorArgs?>(null) }
    var pdfViewerArgs by remember { mutableStateOf<PdfViewerArgs?>(null) }
    var pendingCrashReport by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val saveCrashLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val report = pendingCrashReport
        if (uri != null && report != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(report.toByteArray(Charsets.UTF_8))
                }
                app.toast("Crash log saved successfully")
                CrashReportManager.clearPendingCrashReport(context)
                pendingCrashReport = null
            }.onFailure {
                app.toast("Failed to save crash log")
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingCrashReport = CrashReportManager.getPendingCrashReport(context)
    }

    // Back from the system "All files access" page (or any other app) lands here.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { filesVm.onResume() }

    // User-facing messages: one-shot toasts and the outcome of every long operation.
    LaunchedEffect(Unit) {
        app.messages.collect { message -> scope.launch { snackbar.showSnackbar(message) } }
    }
    LaunchedEffect(Unit) {
        app.runner.results.collect { result ->
            val message = when (result) {
                is OpResult.Success -> result.message
                is OpResult.Failure -> result.message
                OpResult.Cancelled -> "Cancelled"
            }
            if (message.isNotBlank()) scope.launch { snackbar.showSnackbar(message) }
        }
    }

    // Ask for notification permission (Android 13+) the first time an operation runs, so its
    // progress can show while the app is in the background. Declining is fine: the work still runs.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(runningTasks.isNotEmpty()) {
        if (runningTasks.isNotEmpty() && !askedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askedNotifications = true
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val viewer = viewerState
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    BackHandler(
        enabled = true,
    ) {
        when {
            videoPlayerArgs != null -> videoPlayerArgs = null
            pdfViewerArgs != null -> pdfViewerArgs = null
            textEditorArgs != null -> textEditorArgs = null
            imageViewerArgs != null -> imageViewerArgs = null
            viewer != null -> archivesVm.back()
            tab != Tab.Files -> tab = Tab.Files
            filesUi.selection.isNotEmpty() -> filesVm.clearSelection()
            filesUi.searchOpen -> filesVm.closeSearch()
            !filesUi.atRoot -> filesVm.back()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    (context as? androidx.activity.ComponentActivity)?.finish()
                } else {
                    lastBackPressTime = now
                    scope.launch { snackbar.showSnackbar("Press back again to exit") }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (!filesUi.hasAccess) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                PermissionScreen(onPermissionResult = filesVm::onResume)
            }
            return@Box
        }

        val clipboard = clipboardState

        CompositionLocalProvider(
            dev.neroices.zibzip.ui.files.LocalFloatingBarClearance provides bottomClearance,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                when (tab) {
                    Tab.Files -> FilesScreen(
                        vm = filesVm,
                        onOpenArchive = archivesVm::openArchive,
                        showFab = clipboard == null,
                        hasActiveOperations = runningTasks.isNotEmpty(),
                        onOpenImage = { imgs, idx -> imageViewerArgs = ImageViewerArgs(imgs, idx) },
                        onOpenVideo = { path -> videoPlayerArgs = VideoPlayerArgs(path) },
                        onOpenText = { path, isReadOnly -> textEditorArgs = TextEditorArgs(path, isReadOnly) },
                        onOpenPdf = { path -> pdfViewerArgs = PdfViewerArgs(path) },
                    )

                    Tab.Archives -> ArchivesScreen(archivesVm)
                    Tab.Settings -> SettingsScreen(settings, onUpdate = { transform -> app.settings.update(transform) })
                }
                if (viewer != null) {
                    ArchiveViewerScreen(
                        vm = archivesVm,
                        state = viewer,
                        onOpenImage = { path -> imageViewerArgs = ImageViewerArgs(listOf(path), 0) },
                        onOpenVideo = { path -> videoPlayerArgs = VideoPlayerArgs(path) },
                        onOpenText = { path -> textEditorArgs = TextEditorArgs(path, isReadOnly = true) },
                        onOpenPdf = { path -> pdfViewerArgs = PdfViewerArgs(path) },
                    )
                }
            }
        }

        if (imageViewerArgs == null && videoPlayerArgs == null && textEditorArgs == null && pdfViewerArgs == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 500.dp)
                    .onSizeChanged { floatingChromeHeightPx = it.height },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                SnackbarHost(snackbar)
                OperationsContainer(
                    tasks = runningTasks,
                    onCancelTask = { id -> app.runner.cancel(id) },
                    onCancelAll = app.runner::cancelAll,
                    modifier = Modifier.fillMaxWidth(),
                )
                dev.neroices.zibzip.ui.audio.AudioMiniPlayer(
                    controller = app.audioPlayer,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (viewer == null && tab == Tab.Files && clipboard != null) {
                    PasteBar(
                        count = clipboard.paths.size,
                        cut = clipboard.cut,
                        onPaste = filesVm::paste,
                        onCancel = filesVm::cancelClipboard,
                    )
                }
                when {
                    viewer != null -> if (viewer.selection.isNotEmpty()) {
                        ArchiveSelectionBar(
                            onExtract = { archivesVm.showDialog(ViewerDialog.Extract(selectionOnly = true)) },
                            onClear = archivesVm::clearSelection,
                        )
                    }

                    tab == Tab.Files && filesUi.selection.isNotEmpty() -> FilesSelectionBar(filesVm)
                    else -> FloatingNavBar(selected = tab, onSelect = { tab = it })
                }
            }
        }

        imageViewerArgs?.let { args ->
            ImageViewerScreen(
                images = args.images,
                initialIndex = args.initialIndex,
                onClose = { imageViewerArgs = null },
            )
        }

        textEditorArgs?.let { args ->
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                TextEditorScreen(
                    path = args.path,
                    isReadOnly = args.isReadOnly,
                    onClose = { textEditorArgs = null },
                )
            }
        }

        videoPlayerArgs?.let { args ->
            VideoPlayerScreen(
                path = args.path,
                onClose = { videoPlayerArgs = null },
            )
        }

        pdfViewerArgs?.let { args ->
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                PdfViewerScreen(
                    path = args.path,
                    onClose = { pdfViewerArgs = null },
                )
            }
        }

        pendingCrashReport?.let { report ->
            CrashReportDialog(
                report = report,
                onDismiss = {
                    CrashReportManager.clearPendingCrashReport(context)
                    pendingCrashReport = null
                },
                onCopy = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("zibzip Crash Report", report))
                    app.toast("Crash report copied to clipboard")
                    CrashReportManager.clearPendingCrashReport(context)
                    pendingCrashReport = null
                },
                onSaveToFile = {
                    saveCrashLogLauncher.launch(CrashReportManager.getLogFileName())
                },
                onShare = {
                    context.shareTextFile(report, CrashReportManager.getLogFileName())
                },
                onReportOnGithub = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("zibzip Crash Report", report))
                    app.toast("Crash report copied to clipboard")
                    runCatching { uriHandler.openUri("https://github.com/neroices/zibzip/issues/new") }
                    CrashReportManager.clearPendingCrashReport(context)
                    pendingCrashReport = null
                },
            )
        }
    }
}
