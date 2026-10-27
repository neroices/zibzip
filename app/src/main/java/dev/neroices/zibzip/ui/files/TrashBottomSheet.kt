package dev.neroices.zibzip.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neroices.zibzip.data.TrashedItem
import dev.neroices.zibzip.domain.FileTypes
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.ui.components.mouseDragScroll
import dev.neroices.zibzip.ui.components.ConfirmDialog
import dev.neroices.zibzip.ui.components.formatDate
import dev.neroices.zibzip.ui.components.kindVisual

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashBottomSheet(
    vm: FilesViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val trashItems by vm.trashItems.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    var showEmptyConfirm by remember { mutableStateOf(false) }
    var itemToDeletePermanently by remember { mutableStateOf<TrashedItem?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshTrash()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 440.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = cs.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .padding(horizontal = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trash",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = cs.onSurface,
                    )
                    Text(
                        text = if (trashItems.isEmpty()) {
                            "Empty"
                        } else {
                            val totalBytes = trashItems.sumOf { it.size }
                            "${trashItems.size} ${if (trashItems.size == 1) "item" else "items"} · ${formatSize(totalBytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }

                if (trashItems.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = { vm.restoreAllFromTrash() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Restore all", style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = { showEmptyConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.errorContainer,
                                contentColor = cs.onErrorContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Empty", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (trashItems.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = cs.surfaceContainerHighest,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Trash is empty",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Deleted files and folders will appear here when Trash is enabled in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(top = 4.dp),
                    )
                }
            } else {
                // List of items
                val trashListState = rememberLazyListState()
                LazyColumn(
                    state = trashListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .mouseDragScroll(trashListState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = trashItems,
                        key = { it.id },
                    ) { item ->
                        TrashedItemCard(
                            item = item,
                            onRestore = { vm.restoreFromTrash(item) },
                            onDeletePermanently = { itemToDeletePermanently = item },
                        )
                    }
                }
            }
        }
    }

    if (showEmptyConfirm) {
        val totalBytes = trashItems.sumOf { it.size }
        val sizeLabel = if (totalBytes > 0) " (${formatSize(totalBytes)})" else ""
        ConfirmDialog(
            title = "Empty Trash?",
            message = "All ${trashItems.size} items$sizeLabel in Trash will be permanently deleted." +
                "\n\nThis cannot be undone.",
            confirmLabel = "Empty",
            onDismiss = { showEmptyConfirm = false },
            onConfirm = {
                showEmptyConfirm = false
                vm.emptyTrash()
            },
            isTrash = true,
        )
    }

    itemToDeletePermanently?.let { item ->
        val sizeLabel = if (item.size > 0) " (${formatSize(item.size)})" else ""
        ConfirmDialog(
            title = "Delete permanently?",
            message = "\"${item.originalName}\"$sizeLabel will be permanently deleted.\n\nThis cannot be undone.",
            confirmLabel = "Delete",
            onDismiss = { itemToDeletePermanently = null },
            onConfirm = {
                itemToDeletePermanently = null
                vm.deletePermanentlyFromTrash(item)
            },
        )
    }
}

@Composable
private fun TrashedItemCard(
    item: TrashedItem,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val kind = FileTypes.kindOf(item.originalName, item.isDirectory)
    val visual = kindVisual(kind)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Visual Kind Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(visual.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.content,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.originalName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatDate(item.deletedAt)} · ${formatSize(item.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                val originalParent = item.originalPath.substringBeforeLast('/', "")
                if (originalParent.isNotEmpty()) {
                    Text(
                        text = "From: $originalParent",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRestore,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = "Restore",
                        tint = cs.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onDeletePermanently,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = "Delete permanently",
                        tint = cs.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
