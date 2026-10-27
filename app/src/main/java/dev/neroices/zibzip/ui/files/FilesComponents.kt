package dev.neroices.zibzip.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neroices.zibzip.data.AppSettings
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.FileKind
import dev.neroices.zibzip.domain.StorageVolumeInfo
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.ui.archive.CompressDialog
import dev.neroices.zibzip.ui.components.ConfirmDialog
import dev.neroices.zibzip.ui.archive.ExtractDialog
import dev.neroices.zibzip.ui.components.FileRow
import dev.neroices.zibzip.ui.components.NameDialog
import dev.neroices.zibzip.ui.components.SelectionBar
import dev.neroices.zibzip.ui.components.mouseDragScroll
import dev.neroices.zibzip.ui.shareFiles
import dev.neroices.zibzip.zibzipApp
import java.io.File

private val shortcutFolders = listOf("Download", "DCIM", "Pictures", "Documents", "Music", "Movies")

@Composable
internal fun StorageOverview(volumes: List<StorageVolumeInfo>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val primary = remember { context.zibzipApp.storage.primaryRoot }
    val shortcuts = remember(primary) {
        shortcutFolders.map { File(primary, it) }.filter { it.isDirectory }
    }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .mouseDragScroll(listState),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = LocalFloatingBarClearance.current),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = volumes,
            key = { it.path },
            contentType = { false },
        ) { v ->
            VolumeCard(v) { onOpen(v.path) }
        }
        if (shortcuts.isNotEmpty()) {
            item {
                Text(
                    "Quick access",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                )
            }
            items(
                items = shortcuts,
                key = { it.path },
                contentType = { true },
            ) { dir ->
                FileRow(
                    title = dir.name,
                    subtitle = "Internal storage",
                    kind = FileKind.Folder,
                    badge = "",
                    selected = false,
                    onClick = { onOpen(dir.path) },
                    onLongClick = {},
                )
            }
        }
    }
}

@Composable
private fun VolumeCard(volume: StorageVolumeInfo, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val used = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0)
    val fraction = if (volume.totalBytes > 0) (used.toFloat() / volume.totalBytes).coerceIn(0f, 1f) else 0f
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (volume.isRemovable) Icons.Rounded.SdCard else Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        volume.label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSurface,
                    )
                    Text(
                        volume.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (volume.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerLow,
                )
                Text(
                    "${formatSize(volume.freeBytes)} free of ${formatSize(volume.totalBytes)}",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** The floating action pill that replaces the navigation bar while items are selected. */
@Composable
fun FilesSelectionBar(vm: FilesViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selected by remember(ui.selection, items) {
        derivedStateOf { items.filter { it.path in ui.selection } }
    }
    val single = selected.singleOrNull()

    SelectionBar(
        single = single != null,
        singleIsArchive = single != null && single.kind == FileKind.Archive,
        onCopy = { vm.copySelection(cut = false) },
        onCut = { vm.copySelection(cut = true) },
        onDelete = { vm.showDialog(FileDialog.Delete(selected)) },
        onCompress = { vm.showDialog(FileDialog.Compress(selected)) },
        onShare = {
            val files = selected.filter { !it.isDirectory }
            if (files.isEmpty()) context.zibzipApp.toast("Folders can't be shared. Compress them first.")
            else context.shareFiles(files.map { it.path })
        },
        onRename = { single?.let { vm.showDialog(FileDialog.Rename(it)) } },
        onDetails = { vm.showDialog(FileDialog.Details(selected)) },
        onExtract = { single?.let { vm.showDialog(FileDialog.Extract(it)) } },
        modifier = modifier,
    )
}

@Composable
internal fun FilesDialogs(vm: FilesViewModel, dialog: FileDialog?, settings: AppSettings) {
    when (dialog) {
        null -> Unit
        FileDialog.NewFolder -> NameDialog("New folder", "", "Create", vm::dismissDialog, vm::createFolder)
        FileDialog.NewFile -> NameDialog("New file", "", "Create", vm::dismissDialog, vm::createFile)
        is FileDialog.Rename -> NameDialog(
            title = "Rename",
            initial = dialog.item.name,
            confirmLabel = "Rename",
            onDismiss = vm::dismissDialog,
            onConfirm = { vm.rename(dialog.item, it) },
            selectStem = !dialog.item.isDirectory,
        )

        is FileDialog.Delete -> {
            val useTrash = settings.useTrash && !dialog.items.any { vm.isInsideTrash(File(it.path)) }
            val totalBytes = dialog.items.sumOf { it.size }
            ConfirmDialog(
                title = if (useTrash) "Move ${itemsLabel(dialog.items.size)} to Trash?" else "Delete ${itemsLabel(dialog.items.size)}?",
                subtitle = "${formatSize(totalBytes)} in total",
                message = if (useTrash) "You can restore them from Trash." else "This can't be undone.",
                confirmLabel = if (useTrash) "Move to Trash" else "Delete",
                onDismiss = vm::dismissDialog,
                onConfirm = { vm.delete(dialog.items) },
                items = dialog.items,
                isTrash = useTrash,
            )
        }

        is FileDialog.Details -> DetailsBottomSheet(
            items = dialog.items,
            folderStats = vm::folderStats,
            onDismiss = vm::dismissDialog,
            calculateSha256 = vm::calculateSha256,
        )
        is FileDialog.Compress -> CompressDialog(
            items = dialog.items,
            suggestedName = vm.suggestedArchiveName(dialog.items),
            initialKeepScreenOn = settings.keepScreenOn,
            onDismiss = vm::dismissDialog,
            onConfirm = { name, format, level, password, keepScreenOn ->
                vm.compress(dialog.items, name, format, level, password, keepScreenOn)
            },
        )

        is FileDialog.Extract -> ExtractDialog(
            archiveName = dialog.archive.name,
            folderName = ArchiveFormat.detect(dialog.archive.name)?.stripSuffix(dialog.archive.name) ?: dialog.archive.name,
            selectionOnly = false,
            initialPath = File(dialog.archive.path).parent,
            initialKeepScreenOn = settings.keepScreenOn,
            onDismiss = vm::dismissDialog,
            onConfirm = { target, conflict, password, keepScreenOn ->
                vm.extract(dialog.archive, target, conflict, password, keepScreenOn)
            },
        )

        FileDialog.Trash -> TrashBottomSheet(
            vm = vm,
            onDismiss = vm::dismissDialog,
        )
    }
}
