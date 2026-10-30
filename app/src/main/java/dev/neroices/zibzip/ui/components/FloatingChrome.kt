package dev.neroices.zibzip.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.neroices.zibzip.domain.OpState
import dev.neroices.zibzip.domain.formatSize

enum class Tab(val label: String, val icon: ImageVector) {
    Files("Files", Icons.Rounded.Folder),
    Archives("Archives", Icons.Rounded.FolderZip),
    Settings("Settings", Icons.Rounded.Settings),
}

/** Shared look of every floating pill (navigation, selection actions, paste bar). */
@Composable
private fun FloatingPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
        content = content,
    )
}

/** Floating pill navigation: the selected destination expands to show its label. */
@Composable
fun FloatingNavBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    FloatingPill(modifier) {
        Row(
            modifier = Modifier
                .padding(6.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab -> NavItem(tab, tab == selected) { onSelect(tab) } }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) cs.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        label = "navBackground",
    )
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (selected) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(visible = selected) {
            Text(
                text = tab.label,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = cs.primary,
                maxLines = 1,
            )
        }
    }
}

/** Small "+" button with a New folder / New file menu. */
@Composable
fun NewItemFab(onNewFolder: () -> Unit, onNewFile: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FloatingActionButton(
            onClick = { open = true },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "New")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("New folder") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { open = false; onNewFolder() },
            )
            DropdownMenuItem(
                text = { Text("New file") },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.NoteAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { open = false; onNewFile() },
            )
        }
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    description: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Replaces the navigation pill while items are selected. */
@Composable
fun SelectionBar(
    single: Boolean,
    singleIsArchive: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onCompress: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onExtract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    FloatingPill(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Rounded.ContentCopy, "Copy", tint = cs.primary, onClick = onCopy)
            BarAction(Icons.Rounded.ContentCut, "Move", tint = cs.primary, onClick = onCut)
            BarAction(Icons.Rounded.Archive, "Compress", tint = cs.primary, onClick = onCompress)
            BarAction(Icons.Rounded.Delete, "Delete", tint = cs.error, onClick = onDelete)
            Box {
                BarAction(Icons.Rounded.MoreVert, "More", tint = cs.primary) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (singleIsArchive) {
                        DropdownMenuItem(
                            text = { Text("Extract…") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Unarchive,
                                    contentDescription = null,
                                    tint = cs.primary,
                                )
                            },
                            onClick = { menu = false; onExtract() },
                        )
                    }
                    if (single) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DriveFileRenameOutline,
                                    contentDescription = null,
                                    tint = cs.primary,
                                )
                            },
                            onClick = { menu = false; onRename() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = null,
                                tint = cs.primary,
                            )
                        },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = cs.primary,
                            )
                        },
                        onClick = { menu = false; onDetails() },
                    )
                }
            }
        }
    }
}

/** Bar for the archive viewer: extract the current selection. */
@Composable
fun ArchiveSelectionBar(onExtract: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    FloatingPill(modifier) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExtract) {
                Icon(
                    Icons.Rounded.Unarchive,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = cs.primary,
                )
                Text(
                    "Extract selected",
                    modifier = Modifier.padding(start = 8.dp),
                    color = cs.primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            BarAction(Icons.Rounded.Close, "Clear selection", tint = cs.onSurfaceVariant, onClick = onClear)
        }
    }
}

/** Shown above the nav pill after Copy / Move: paste into whichever folder is open. */
@Composable
fun PasteBar(count: Int, cut: Boolean, onPaste: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    FloatingPill(modifier) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = cs.primary,
            )
            Text(
                text = (if (cut) "Move " else "Copy ") + count + if (count == 1) " item" else " items",
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
            )
            TextButton(onClick = onPaste) {
                Text(
                    "Paste here",
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            BarAction(Icons.Rounded.Close, "Cancel", tint = cs.onSurfaceVariant, onClick = onCancel)
        }
    }
}

/** Progress for the running operation, with speed and cancel. */
@Composable
fun OperationCard(state: OpState.Running, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = cs.surfaceContainerHighest,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Real-time speed and percentage indicator
                val speedText = if (state.speedBytesPerSec > 0) " · ${formatSize(state.speedBytesPerSec)}/s" else ""
                val progressLabel = if (state.total > 0) "${state.percentage}%$speedText" else if (speedText.isNotEmpty()) speedText.removePrefix(" · ") else ""
                if (progressLabel.isNotEmpty()) {
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = cs.primary,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(
                        "Cancel",
                        color = cs.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val fraction = state.fraction
            if (fraction != null) {
                val animatedFraction by animateFloatAsState(
                    targetValue = fraction,
                    label = "operationProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier
                        .padding(top = 4.dp, end = 4.dp)
                        .fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerLow,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .padding(top = 4.dp, end = 4.dp)
                        .fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerLow,
                )
            }
            if (state.current.isNotEmpty()) {
                Text(
                    text = state.current,
                    modifier = Modifier.padding(top = 6.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Container that renders a single card for 1 task, or a multi-task list for multiple concurrent tasks. */
@Composable
fun OperationsContainer(
    tasks: List<OpState.Running>,
    onCancelTask: (String) -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return

    if (tasks.size == 1) {
        val task = tasks.first()
        OperationCard(
            state = task,
            onCancel = { onCancelTask(task.id) },
            modifier = modifier,
        )
    } else {
        MultiOperationCard(
            tasks = tasks,
            onCancelTask = onCancelTask,
            onCancelAll = onCancelAll,
            modifier = modifier,
        )
    }
}

@Composable
fun MultiOperationCard(
    tasks: List<OpState.Running>,
    onCancelTask: (String) -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = cs.surfaceContainerHighest,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Text(
                    text = "${tasks.size} active tasks",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onCancelAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "Cancel all",
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            // Task list with max height so it never overwhelms the screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = cs.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    OperationTaskRow(task = task, onCancel = { onCancelTask(task.id) })
                }
            }
        }
    }
}

@Composable
private fun OperationTaskRow(task: OpState.Running, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val speedText = if (task.speedBytesPerSec > 0) " · ${formatSize(task.speedBytesPerSec)}/s" else ""
            val progressLabel = if (task.total > 0) "${task.percentage}%$speedText" else if (speedText.isNotEmpty()) speedText.removePrefix(" · ") else ""
            if (progressLabel.isNotEmpty()) {
                Text(
                    text = progressLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = cs.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel task",
                    tint = cs.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        val fraction = task.fraction
        if (fraction != null) {
            val animatedFraction by animateFloatAsState(
                targetValue = fraction,
                label = "taskProgress",
            )
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp)
                    .fillMaxWidth(),
                color = cs.primary,
                trackColor = cs.surfaceContainerLow,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp)
                    .fillMaxWidth(),
                color = cs.primary,
                trackColor = cs.surfaceContainerLow,
            )
        }
        if (task.current.isNotEmpty()) {
            Text(
                text = task.current,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
