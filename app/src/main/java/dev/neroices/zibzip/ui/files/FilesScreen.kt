package dev.neroices.zibzip.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neroices.zibzip.ui.installApk
import dev.neroices.zibzip.data.AppSettings
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.FileKind
import dev.neroices.zibzip.domain.FileTypes
import dev.neroices.zibzip.domain.SortField
import dev.neroices.zibzip.domain.StorageVolumeInfo
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.ui.components.mouseDragScroll
import dev.neroices.zibzip.zibzipApp
import dev.neroices.zibzip.ui.archive.CompressDialog
import dev.neroices.zibzip.ui.components.ConfirmDialog
import dev.neroices.zibzip.ui.components.EmptyState
import dev.neroices.zibzip.ui.archive.ExtractDialog
import dev.neroices.zibzip.ui.components.FileRow
import dev.neroices.zibzip.ui.components.LoadingState
import dev.neroices.zibzip.ui.components.NameDialog
import dev.neroices.zibzip.ui.components.NewItemFab
import dev.neroices.zibzip.ui.components.SelectionBar
import dev.neroices.zibzip.ui.components.formatDate
import dev.neroices.zibzip.ui.openFile
import dev.neroices.zibzip.ui.shareFiles

/** Space kept free at the bottom of lists so the floating bars never cover the last rows. */
val FloatingBarClearance = 150.dp
val LocalFloatingBarClearance = androidx.compose.runtime.compositionLocalOf { FloatingBarClearance }

@Composable
fun FilesScreen(
    vm: FilesViewModel,
    onOpenArchive: (String) -> Unit,
    showFab: Boolean,
    modifier: Modifier = Modifier,
    hasActiveOperations: Boolean = false,
    onOpenImage: (images: List<String>, initialIndex: Int) -> Unit = { _, _ -> },
    onOpenVideo: (path: String) -> Unit = {},
    onOpenText: (path: String, isReadOnly: Boolean) -> Unit = { _, _ -> },
    onOpenPdf: (path: String) -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showStorageSheet by rememberSaveable { mutableStateOf(false) }

    val clearance = LocalFloatingBarClearance.current
    val fabBottomOffset by animateDpAsState(
        targetValue = (clearance - 58.dp).coerceAtLeast(16.dp),
        label = "fabBottomOffset",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp || (maxWidth > maxHeight && maxWidth >= 500.dp)

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            FilesTopBar(
                ui = ui,
                count = items.size,
                settings = settings,
                vm = vm,
                onOpenStorageSheet = { if (!isWide) showStorageSheet = true },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (isWide) {
                    Surface(
                        modifier = Modifier
                            .width(260.dp)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        StorageNavigationContent(
                            volumes = ui.volumes,
                            useTrash = settings.useTrash,
                            onOpen = { targetPath -> vm.open(targetPath) },
                            onOpenTrash = { vm.showDialog(FileDialog.Trash) },
                            includeBottomClearance = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val path = ui.path
                    if (path == null) {
                        LaunchedEffect(Unit) {
                            vm.open(context.zibzipApp.storage.primaryRoot.path)
                        }
                    } else {
                        FileList(
                            items = items,
                            ui = ui,
                            settings = settings,
                            canNavigateUp = vm.canNavigateUp(),
                            onNavigateUp = { vm.navigateUp() },
                            onClick = { item ->
                                when {
                                    ui.selection.isNotEmpty() -> vm.toggleSelection(item.path)
                                    item.isDirectory -> vm.open(item.path)
                                    item.kind == FileKind.Apk || item.name.endsWith(".apk", ignoreCase = true) -> context.installApk(item.path)
                                    item.kind == FileKind.Archive -> onOpenArchive(item.path)
                                    item.name.endsWith(".pdf", ignoreCase = true) -> onOpenPdf(item.path)
                                    item.kind == FileKind.Image -> {
                                        val imagePaths = items.filter { it.kind == FileKind.Image && !it.isDirectory }.map { it.path }
                                        val initialIndex = imagePaths.indexOf(item.path).coerceAtLeast(0)
                                        onOpenImage(imagePaths, initialIndex)
                                    }
                                    item.kind == FileKind.Video || FileTypes.isVideoFile(item.name) -> {
                                        onOpenVideo(item.path)
                                    }
                                    item.kind == FileKind.Audio || FileTypes.isAudioFile(item.name) -> {
                                        val audioPaths = items
                                            .filter { !it.isDirectory && (it.kind == FileKind.Audio || FileTypes.isAudioFile(it.name)) }
                                            .map { it.path }
                                        context.zibzipApp.audioPlayer.playFile(item.path, audioPaths)
                                    }
                                    item.kind == FileKind.Code || FileTypes.isTextFile(item.name) || FileTypes.isTextFile(java.io.File(item.path)) -> {
                                        onOpenText(item.path, false)
                                    }
                                    else -> context.openFile(item.path)
                                }
                            },
                            onLongClick = { item -> vm.toggleSelection(item.path) },
                            onRetry = { vm.refresh(silent = false) },
                            onRefresh = { vm.refresh(silent = false) },
                        )
                        if (ui.loading || ui.searchRunning) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (showFab && ui.path != null && ui.selection.isEmpty() && !ui.searchOpen) {
            NewItemFab(
                onNewFolder = { vm.showDialog(FileDialog.NewFolder) },
                onNewFile = { vm.showDialog(FileDialog.NewFile) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = fabBottomOffset),
            )
        }
        if (showStorageSheet && !isWide) {
            StorageBottomSheet(
                volumes = ui.volumes,
                useTrash = settings.useTrash,
                onOpen = { targetPath ->
                    showStorageSheet = false
                    vm.open(targetPath)
                },
                onOpenTrash = {
                    showStorageSheet = false
                    vm.showDialog(FileDialog.Trash)
                },
                onDismiss = { showStorageSheet = false },
            )
        }
        FilesDialogs(vm, ui.dialog, settings)
    }
}

@Composable
private fun FilesTopBar(
    ui: FilesUiState,
    count: Int,
    settings: AppSettings,
    vm: FilesViewModel,
    onOpenStorageSheet: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                ui.selection.isNotEmpty() -> {
                    IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Rounded.Close, "Clear selection", tint = cs.primary)
                    }
                    Text(
                        "${ui.selection.size} selected",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::selectAll) {
                        Icon(Icons.Rounded.SelectAll, "Select all", tint = cs.primary)
                    }
                }

                ui.searchOpen -> {
                    IconButton(onClick = vm::closeSearch) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search", tint = cs.primary)
                    }
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TextField(
                        value = ui.query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        placeholder = { Text("Search in this folder") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = cs.primary,
                        ),
                        trailingIcon = {
                            if (ui.query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) {
                                    Icon(Icons.Rounded.Close, "Clear", tint = cs.onSurfaceVariant)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus),
                    )
                }

                ui.path == null -> {
                    RootCrumbChip(
                        text = "Storage",
                        onClick = onOpenStorageSheet,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                }

                else -> {
                    Breadcrumbs(
                        path = ui.path.orEmpty(),
                        volumes = ui.volumes,
                        onNavigate = vm::open,
                        onOpenStorageSheet = onOpenStorageSheet,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 4.dp),
                    )
                    IconButton(onClick = vm::openSearch) {
                        Icon(Icons.Rounded.Search, "Search", tint = cs.primary)
                    }
                    SortMenu(settings, vm::updateSettings)
                }
            }
        }
        if (ui.path != null && ui.selection.isEmpty()) {
            val sort = settings.sort
            val caption = if (ui.isSearching) {
                "$count ${if (count == 1) "result" else "results"}" + if (ui.searchRunning) " · searching…" else ""
            } else {
                "${itemsLabel(count)} · ${sort.field.name.lowercase()} ${if (sort.ascending) "↑" else "↓"}"
            }
            Text(
                caption,
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Breadcrumbs(
    path: String,
    volumes: List<StorageVolumeInfo>,
    onNavigate: (String) -> Unit,
    onOpenStorageSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val primary = remember { context.zibzipApp.storage.primaryRoot.path }
    val root by remember(path, volumes) {
        derivedStateOf {
            volumes.filter { path == it.path || path.startsWith(it.path + "/") }
                .maxByOrNull { it.path.length }?.path ?: primary
        }
    }
    val rootLabel = remember(volumes, root) {
        volumes.firstOrNull { it.path == root }?.label ?: "Internal storage"
    }
    val segments = remember(path, root) {
        path.removePrefix(root).split('/').filter { it.isNotEmpty() }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Row(
        modifier = modifier.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RootCrumbChip(
            text = rootLabel,
            onClick = onOpenStorageSheet,
        )
        var acc = root
        segments.forEachIndexed { i, segment ->
            acc = "$acc/$segment"
            val target = acc
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Crumb(segment, last = i == segments.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun RootCrumbChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = "Switch storage",
                tint = cs.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Crumb(text: String, last: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal),
        color = if (last) cs.onSurface else cs.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

@Composable
private fun SortMenu(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, "Sort and view", tint = cs.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { field ->
                val selected = settings.sort.field == field
                DropdownMenuItem(
                    text = { Text(field.name) },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                if (settings.sort.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                contentDescription = if (settings.sort.ascending) "Ascending" else "Descending",
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                    onClick = {
                        onChange { s ->
                            if (s.sort.field == field) s.copy(sort = s.sort.copy(ascending = !s.sort.ascending))
                            else s.copy(sort = s.sort.copy(field = field, ascending = true))
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Folders first") },
                trailingIcon = if (settings.sort.foldersFirst) ({
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.primary)
                }) else null,
                onClick = { onChange { s -> s.copy(sort = s.sort.copy(foldersFirst = !s.sort.foldersFirst)) } },
            )
            DropdownMenuItem(
                text = { Text("Show hidden files") },
                trailingIcon = if (settings.showHidden) ({
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.primary)
                }) else null,
                onClick = { onChange { s -> s.copy(showHidden = !s.showHidden) } },
            )
            DropdownMenuItem(
                text = { Text("Show \"..\" folder") },
                trailingIcon = if (settings.showParentFolder) ({
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.primary)
                }) else null,
                onClick = { onChange { s -> s.copy(showParentFolder = !s.showParentFolder) } },
            )
        }
    }
}

@Composable
private fun FolderStatusCard(
    path: String,
    items: List<FileItem>,
    volumes: List<StorageVolumeInfo>,
    pullProgress: Float,
    isReadyToRefresh: Boolean,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val currentVolume = remember(volumes, path) {
        volumes.firstOrNull { path.startsWith(it.path) } ?: volumes.firstOrNull()
    }
    val folderName = remember(path, currentVolume) {
        val f = java.io.File(path)
        if (f.name.isEmpty()) currentVolume?.label ?: "Storage" else f.name
    }
    val foldersCount = remember(items) { items.count { it.isDirectory } }
    val filesCount = remember(items) { items.count { !it.isDirectory } }
    val totalFilesSize = remember(items) { items.filter { !it.isDirectory }.sumOf { it.size } }

    val freeBytes = currentVolume?.freeBytes ?: 0L
    val totalBytes = currentVolume?.totalBytes ?: 0L
    val usedFraction = remember(freeBytes, totalBytes) {
        if (totalBytes > 0) ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    val volumeLabel = currentVolume?.label ?: "Storage"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            // Folder title and size badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(cs.primaryContainer.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "$foldersCount ${if (foldersCount == 1) "folder" else "folders"} · $filesCount ${if (filesCount == 1) "file" else "files"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cs.secondaryContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = "Size: ${formatSize(totalFilesSize)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Storage bar & free space
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Storage: $volumeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    text = "Free: ${formatSize(freeBytes)} / ${formatSize(totalBytes)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )

            Spacer(Modifier.height(12.dp))

            // Pull status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = cs.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Refreshing folder…",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.primary,
                    )
                } else if (isReadyToRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = pullProgress * 360f },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Release to refresh",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = cs.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Pull down further to refresh",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileList(
    items: List<FileItem>,
    ui: FilesUiState,
    settings: AppSettings,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
) {
    val density = LocalDensity.current
    val peekHeight = 150.dp
    val peekHeightPx = with(density) { peekHeight.toPx() }
    val refreshThreshold = 200.dp
    val refreshThresholdPx = with(density) { refreshThreshold.toPx() }
    val haptic = LocalHapticFeedback.current

    var pullOffsetPx by remember { mutableFloatStateOf(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    val animatedOffset by animateFloatAsState(
        targetValue = pullOffsetPx,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pullOffset",
    )

    val lazyGridState = rememberLazyGridState()

    val isAtTop by remember {
        derivedStateOf {
            lazyGridState.firstVisibleItemIndex == 0 && lazyGridState.firstVisibleItemScrollOffset == 0
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && pullOffsetPx > 0) {
                    val newOffset = (pullOffsetPx + available.y).coerceAtLeast(0f)
                    val consumed = pullOffsetPx - newOffset
                    pullOffsetPx = newOffset
                    if (pullOffsetPx < refreshThresholdPx) hasTriggeredHaptic = false
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && isAtTop && source == NestedScrollSource.UserInput) {
                    val resistance = (1f - (pullOffsetPx / (refreshThresholdPx * 2.2f))).coerceIn(0.2f, 0.65f)
                    val newOffset = (pullOffsetPx + available.y * resistance).coerceAtMost(refreshThresholdPx * 1.4f)
                    pullOffsetPx = newOffset
                    if (pullOffsetPx >= refreshThresholdPx && !hasTriggeredHaptic) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hasTriggeredHaptic = true
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffsetPx >= refreshThresholdPx) {
                    onRefresh()
                }
                pullOffsetPx = 0f
                hasTriggeredHaptic = false
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
    ) {
        if (animatedOffset > 4f && ui.path != null) {
            val progress = (animatedOffset / refreshThresholdPx).coerceIn(0f, 1f)
            val isReady = animatedOffset >= refreshThresholdPx
            FolderStatusCard(
                path = ui.path,
                items = items,
                volumes = ui.volumes,
                pullProgress = progress,
                isReadyToRefresh = isReady,
                isRefreshing = ui.loading,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = (animatedOffset - peekHeightPx).coerceAtMost(0f)
                        alpha = (animatedOffset / (peekHeightPx * 0.35f)).coerceIn(0f, 1f)
                    },
            )
        }

        val showNavigateUp = settings.showParentFolder && canNavigateUp && !ui.isSearching

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = animatedOffset
                },
        ) {
            when {
                ui.error != null && items.isEmpty() -> {
                    val denied = ui.error.contains("denied", ignoreCase = true)
                    EmptyState(
                        icon = Icons.Rounded.FolderOff,
                        title = if (denied) "Can't open this folder" else "Something went wrong",
                        message = ui.error,
                        actionLabel = "Try again",
                        onAction = onRetry,
                    )
                }

                items.isEmpty() && (ui.loading || ui.searchRunning) -> LoadingState()
                items.isEmpty() && ui.isSearching -> EmptyState(
                    Icons.Rounded.SearchOff,
                    "No matches",
                    "Nothing named “${ui.query.trim()}” in this folder.",
                )
                items.isEmpty() && !showNavigateUp -> EmptyState(Icons.Rounded.FolderOpen, "This folder is empty")
                else -> {
                    val base = ui.path.orEmpty()
                    key(ui.path) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 340.dp),
                            state = lazyGridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .mouseDragScroll(lazyGridState),
                            contentPadding = PaddingValues(top = 2.dp, bottom = LocalFloatingBarClearance.current),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (showNavigateUp) {
                                item(key = "..", span = { GridItemSpan(maxLineSpan) }, contentType = "parent_dir") {
                                    FileRow(
                                        title = "..",
                                        subtitle = "Parent folder",
                                        kind = FileKind.Folder,
                                        badge = "",
                                        selected = false,
                                        onClick = onNavigateUp,
                                        onLongClick = {},
                                    )
                                }
                            }
                            if (items.isEmpty()) {
                                item(key = "__empty__", span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 100.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        EmptyState(
                                            icon = Icons.Rounded.FolderOpen,
                                            title = "This folder is empty",
                                        )
                                    }
                                }
                            } else {
                                items(
                                    items = items,
                                    key = { it.path },
                                    contentType = { it.isDirectory },
                                ) { item ->
                                    // Cache format string computations to avoid recalculating during scrolling
                                    val subtitle = remember(item.path, item.lastModified, item.size, item.isDirectory, ui.isSearching, ui.query) {
                                        val details = if (item.isDirectory) formatDate(item.lastModified)
                                        else "${formatSize(item.size)} · ${formatDate(item.lastModified)}"
                                        if (ui.isSearching) {
                                            val where = item.parentPath.removePrefix(base).trim('/')
                                            "$details · ${if (where.isEmpty()) "this folder" else where}"
                                        } else details
                                    }

                                    FileRow(
                                        title = item.name,
                                        subtitle = subtitle,
                                        kind = item.kind,
                                        badge = item.typeLabel,
                                        selected = item.path in ui.selection,
                                        onClick = { onClick(item) },
                                        onLongClick = { onLongClick(item) },
                                        dimmed = item.isHidden,
                                        path = item.path,
                                        lastModified = item.lastModified,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

