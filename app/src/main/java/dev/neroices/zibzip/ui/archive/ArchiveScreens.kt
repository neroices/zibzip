package dev.neroices.zibzip.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neroices.zibzip.domain.ArchiveEntryItem
import dev.neroices.zibzip.domain.ArchiveTree
import dev.neroices.zibzip.domain.FileKind
import dev.neroices.zibzip.domain.FileTypes
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.ui.components.ConfirmDialog
import dev.neroices.zibzip.ui.components.EmptyState
import dev.neroices.zibzip.ui.archive.ExtractDialog
import dev.neroices.zibzip.ui.components.FileRow
import dev.neroices.zibzip.ui.components.LoadingState
import dev.neroices.zibzip.ui.components.PasswordDialog
import dev.neroices.zibzip.ui.components.mouseDragScroll
import dev.neroices.zibzip.ui.components.formatDate
import dev.neroices.zibzip.ui.files.FloatingBarClearance
import dev.neroices.zibzip.ui.files.LocalFloatingBarClearance

@Composable
fun ArchivesScreen(vm: ArchivesViewModel, modifier: Modifier = Modifier) {
    val state by vm.list.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) { vm.scan() }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Archives",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = cs.onSurface,
                )
            }
            IconButton(onClick = { vm.scan(force = true) }, enabled = !state.scanning) {
                Icon(Icons.Rounded.Refresh, "Rescan", tint = cs.primary)
            }
        }
        Text(
            text = if (state.scanning) "${state.items.size} found · scanning…" else "${state.items.size} found",
            modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.items.isEmpty() && state.scanning -> LoadingState()
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.FolderZip,
                    title = "No archives found",
                    message = "ZIP, 7z, TAR, GZ, RAR and more show up here.",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .mouseDragScroll(gridState),
                    contentPadding = PaddingValues(top = 2.dp, bottom = LocalFloatingBarClearance.current),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.items,
                        key = { it.path },
                        contentType = { false },
                    ) { item ->
                        val subtitle = remember(item.path, item.lastModified, item.size, item.parentPath) {
                            "${formatSize(item.size)} · ${formatDate(item.lastModified)} · ${item.parentPath.substringAfterLast('/')}"
                        }
                        FileRow(
                            title = item.name,
                            subtitle = subtitle,
                            kind = item.kind,
                            badge = item.typeLabel,
                            selected = false,
                            onClick = { vm.openArchive(item.path) },
                            onLongClick = {},
                        )
                    }
                }
            }
            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary,
                )
            }
        }
    }
}

@Composable
fun ArchiveViewerScreen(
    vm: ArchivesViewModel,
    state: ViewerState,
    modifier: Modifier = Modifier,
    onOpenImage: (String) -> Unit = {},
    onOpenVideo: (String) -> Unit = {},
    onOpenText: (String) -> Unit = {},
    onOpenPdf: (String) -> Unit = {},
) {
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val gridState = rememberLazyGridState()

    Surface(modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ViewerTopBar(vm, state, canAdd = state.canEdit && clipboard != null)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.error != null -> EmptyState(
                        icon = Icons.Rounded.ErrorOutline,
                        title = "Can't open this archive",
                        message = state.error,
                        actionLabel = "Close",
                        onAction = vm::closeViewer,
                    )

                    state.loading && state.visible.isEmpty() -> LoadingState()
                    else -> Column(Modifier.fillMaxSize()) {
                        if (state.hasEncrypted && state.password == null) {
                            LockedBanner(onUnlock = { vm.showDialog(ViewerDialog.Password(wrong = false)) })
                        }
                        val showNavigateUp = settings.showParentFolder && state.dir.isNotEmpty()
                        if (state.visible.isEmpty() && !showNavigateUp) {
                            EmptyState(Icons.Rounded.FolderZip, "This folder is empty")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 340.dp),
                                state = gridState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .mouseDragScroll(gridState),
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
                                            onClick = { vm.enter(ArchiveTree.parentOf(state.dir)) },
                                            onLongClick = {},
                                        )
                                    }
                                }
                                if (state.visible.isEmpty()) {
                                    item(key = "__empty__", span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 100.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            EmptyState(
                                                icon = Icons.Rounded.FolderZip,
                                                title = "This folder is empty",
                                            )
                                        }
                                    }
                                } else {
                                    items(
                                        items = state.visible,
                                        key = { it.path },
                                        contentType = { it.isDirectory },
                                    ) { entry ->
                                        EntryRow(
                                            entry = entry,
                                            selected = entry.path in state.selection,
                                            onClick = {
                                                when {
                                                    state.selection.isNotEmpty() -> vm.toggleSelection(entry.path)
                                                    entry.isDirectory -> vm.enter(entry.path)
                                                    else -> vm.preview(entry, onOpenImage, onOpenVideo, onOpenText, onOpenPdf)
                                                }
                                            },
                                            onLongClick = { vm.toggleSelection(entry.path) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = cs.primary,
                    )
                }
            }
        }
    }
    ViewerDialogs(vm, state)
}

@Composable
private fun EntryRow(entry: ArchiveEntryItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val subtitle = remember(entry.path, entry.lastModified, entry.size, entry.isDirectory) {
        when {
            entry.isDirectory -> if (entry.lastModified > 0) formatDate(entry.lastModified) else "Folder"
            else -> "${formatSize(entry.size)} · ${formatDate(entry.lastModified)}"
        }
    }
    FileRow(
        title = entry.name,
        subtitle = subtitle,
        kind = FileTypes.kindOf(entry.name, entry.isDirectory),
        badge = when {
            entry.isEncrypted -> "LOCKED"
            entry.isDirectory -> ""
            else -> entry.name.substringAfterLast('.', "").uppercase().take(7)
        },
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun LockedBanner(onUnlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Text(
                "Some files are password protected",
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = cs.onTertiaryContainer,
            )
            TextButton(onClick = onUnlock) {
                Text("Unlock", color = cs.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ViewerTopBar(vm: ArchivesViewModel, state: ViewerState, canAdd: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.selection.isNotEmpty()) {
            IconButton(onClick = vm::clearSelection) {
                Icon(Icons.Rounded.Close, "Clear selection", tint = cs.primary)
            }
            Text(
                "${state.selection.size} selected",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.canEdit) {
                IconButton(onClick = { vm.showDialog(ViewerDialog.DeleteEntries) }) {
                    Icon(Icons.Rounded.Delete, "Remove from archive", tint = cs.error)
                }
            }
            IconButton(onClick = vm::selectAll) {
                Icon(Icons.Rounded.SelectAll, "Select all", tint = cs.primary)
            }
        } else {
            IconButton(onClick = vm::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = cs.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.dir.isEmpty()) {
                        "${state.format?.label ?: "Archive"} · ${itemsLabel(state.fileCount)} · ${formatSize(state.totalSize)}"
                    } else "/${state.dir}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { vm.showDialog(ViewerDialog.Extract(selectionOnly = false)) },
                enabled = state.error == null && !state.loading,
            ) {
                Icon(Icons.Rounded.Unarchive, "Extract all", tint = cs.primary)
            }
            if (canAdd) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, "More", tint = cs.primary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add copied items here") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, null, tint = cs.primary) },
                            onClick = { menu = false; vm.addClipboardItems() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerDialogs(vm: ArchivesViewModel, state: ViewerState) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    when (val dialog = state.dialog) {
        null -> Unit
        is ViewerDialog.Password -> PasswordDialog(
            archiveName = state.name,
            wrong = dialog.wrong,
            onDismiss = vm::dismissDialog,
            onConfirm = vm::setPassword,
        )

        is ViewerDialog.Extract -> ExtractDialog(
            archiveName = state.name,
            folderName = vm.folderNameFor(state.name),
            selectionOnly = dialog.selectionOnly,
            initialPath = java.io.File(state.path).parent,
            initialKeepScreenOn = settings.keepScreenOn,
            onDismiss = vm::dismissDialog,
            onConfirm = { target, conflict, password, keepScreenOn ->
                vm.extract(dialog.selectionOnly, target, conflict, password, keepScreenOn)
            },
        )

        ViewerDialog.DeleteEntries -> ConfirmDialog(
            title = "Remove ${itemsLabel(state.selection.size)} from archive?",
            message = "The archive file is rewritten without them. This can't be undone.",
            confirmLabel = "Remove",
            onDismiss = vm::dismissDialog,
            onConfirm = vm::deleteSelectedEntries,
        )
    }
}
