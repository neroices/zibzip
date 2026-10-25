package dev.neroices.zibzip.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ConflictPolicy
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.ui.components.ChipRow
import dev.neroices.zibzip.ui.components.FolderPickerDialog
import dev.neroices.zibzip.ui.components.PopupDialogContainer
import dev.neroices.zibzip.ui.components.SectionLabel
import dev.neroices.zibzip.ui.files.ExtractTarget
import java.io.File
@Composable
fun CompressDialog(
    items: List<FileItem>,
    suggestedName: String,
    initialKeepScreenOn: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: ArchiveFormat, level: Int, password: String?, keepScreenOn: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val singleFile = items.size == 1 && !items[0].isDirectory
    val formats = remember(singleFile) { ArchiveFormat.creatable.filter { singleFile || it.multiFile } }
    var name by remember { mutableStateOf(suggestedName) }
    var format by remember { mutableStateOf(formats.first()) }
    var level by remember { mutableFloatStateOf(5f) }
    var password by remember { mutableStateOf("") }
    var keepScreenOn by remember { mutableStateOf(initialKeepScreenOn) }
    val totalBytes = remember(items) { items.sumOf { it.size } }

    PopupDialogContainer(
        onDismiss = onDismiss,
        icon = Icons.Rounded.Archive,
        iconContainerColor = cs.primaryContainer,
        iconContentColor = cs.primary,
        title = "Compress ${itemsLabel(items.size)}",
        subtitle = "${formatSize(totalBytes)} in total",
        confirmLabel = "Compress",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name, format, level.toInt(), if (format.passwordOnCreate) password else null, keepScreenOn) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Archive name") },
                suffix = { Text(format.primarySuffix) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            SectionLabel("Format")
            ChipRow(formats.map { it to it.label }, format) { format = it }
            if (format.hasLevel) {
                SectionLabel("Compression: ${levelName(level.toInt())}")
                Slider(
                    value = level,
                    onValueChange = { level = it },
                    valueRange = 0f..9f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = cs.primary,
                        activeTrackColor = cs.primary,
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Store",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level.toInt() == 0) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (level.toInt() == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { level = 0f },
                    )
                    Text(
                        text = "Fast",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level.toInt() in 1..4) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (level.toInt() in 1..4) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { level = 3f },
                    )
                    Text(
                        text = "Normal",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level.toInt() in 5..6) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (level.toInt() in 5..6) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { level = 5f },
                    )
                    Text(
                        text = "Max",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level.toInt() >= 7) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (level.toInt() >= 7) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { level = 9f },
                    )
                }
            }
            if (format.passwordOnCreate) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password (optional, AES-256)") },
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { keepScreenOn = !keepScreenOn }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = keepScreenOn,
                    onCheckedChange = { keepScreenOn = it },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Keep screen on",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
            }
        }
    }
}

private fun levelName(level: Int) = when (level) {
    0 -> "store (no compression)"
    in 1..2 -> "fastest"
    in 3..4 -> "fast"
    in 5..6 -> "normal"
    in 7..8 -> "high"
    else -> "maximum"
}

private enum class ExtractTargetMode {
    NamedFolder,
    SameFolder,
    CustomFolder,
}

/**
 * Extract options. [selectionOnly] switches the wording for "extract selected entries" in the viewer.
 */
@Composable
fun ExtractDialog(
    archiveName: String,
    folderName: String,
    selectionOnly: Boolean,
    initialPath: String? = null,
    initialKeepScreenOn: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (target: ExtractTarget, conflict: ConflictPolicy, password: String?, keepScreenOn: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var targetMode by remember {
        mutableStateOf(if (selectionOnly) ExtractTargetMode.SameFolder else ExtractTargetMode.NamedFolder)
    }
    var customPath by remember { mutableStateOf<String?>(null) }
    var customCreateSubfolder by remember { mutableStateOf(!selectionOnly) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(ConflictPolicy.KeepBoth) }
    var password by remember { mutableStateOf("") }
    var keepScreenOn by remember { mutableStateOf(initialKeepScreenOn) }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialDir = customPath?.let { File(it) } ?: initialPath?.let { File(it) },
            onDismiss = {
                showFolderPicker = false
                if (customPath == null) {
                    targetMode = if (selectionOnly) ExtractTargetMode.SameFolder else ExtractTargetMode.NamedFolder
                }
            },
            onConfirm = { selectedDir ->
                customPath = selectedDir.absolutePath
                targetMode = ExtractTargetMode.CustomFolder
                showFolderPicker = false
            },
        )
    } else {
        PopupDialogContainer(
            onDismiss = onDismiss,
            icon = Icons.Rounded.Unarchive,
            iconContainerColor = cs.primaryContainer,
            iconContentColor = cs.primary,
            title = if (selectionOnly) "Extract selected" else "Extract archive",
            subtitle = archiveName,
            confirmLabel = "Extract",
            confirmEnabled = targetMode != ExtractTargetMode.CustomFolder || customPath != null,
            onConfirm = {
                val target = when (targetMode) {
                    ExtractTargetMode.SameFolder -> ExtractTarget.SameFolder
                    ExtractTargetMode.NamedFolder -> ExtractTarget.NamedFolder
                    ExtractTargetMode.CustomFolder -> ExtractTarget.CustomFolder(
                        path = customPath ?: initialPath ?: "",
                        createSubfolder = customCreateSubfolder,
                    )
                }
                onConfirm(target, conflict, password.ifEmpty { null }, keepScreenOn)
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel("Extract to")
                val customChipLabel = customPath?.let { path ->
                    val dirName = File(path).name.ifBlank { "Root" }
                    "Folder “$dirName”"
                } ?: "Custom folder…"
                ChipRow(
                    listOf(
                        ExtractTargetMode.NamedFolder to "Folder “$folderName”",
                        ExtractTargetMode.SameFolder to "Next to archive",
                        ExtractTargetMode.CustomFolder to customChipLabel,
                    ),
                    targetMode,
                ) { selected ->
                    targetMode = selected
                    if (selected == ExtractTargetMode.CustomFolder && customPath == null) {
                        showFolderPicker = true
                    }
                }

                if (targetMode == ExtractTargetMode.CustomFolder) {
                    Spacer(Modifier.height(8.dp))
                    val path = customPath
                    if (path != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cs.surfaceContainerHighest.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showFolderPicker = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = File(path).name.ifBlank { "Root" },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = cs.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Change",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = cs.primary,
                                    ),
                                )
                            }
                        }
                        if (!selectionOnly) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { customCreateSubfolder = !customCreateSubfolder }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = customCreateSubfolder,
                                    onCheckedChange = { customCreateSubfolder = it },
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Extract into subfolder “$folderName”",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurface,
                                )
                            }
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { showFolderPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose destination folder")
                        }
                    }
                }

                SectionLabel("If a file already exists")
                ChipRow(
                    listOf(
                        ConflictPolicy.KeepBoth to "Keep both",
                        ConflictPolicy.Overwrite to "Replace",
                        ConflictPolicy.Skip to "Skip",
                    ),
                    conflict,
                ) { conflict = it }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password (if protected)") },
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { keepScreenOn = !keepScreenOn }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = keepScreenOn,
                        onCheckedChange = { keepScreenOn = it },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Keep screen on",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}
