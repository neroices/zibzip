package dev.neroices.zibzip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.neroices.zibzip.data.FolderStats
import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ConflictPolicy
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.ui.files.ExtractTarget
import java.io.File
import java.util.Locale

/**
 * Modern expressive popup dialog container matching the Material 3 squircle header
 * and dual pill button design language.
 */
@Composable
fun PopupDialogContainer(
    onDismiss: () -> Unit,
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    title: String,
    subtitle: String? = null,
    confirmLabel: String,
    cancelLabel: String = "Cancel",
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxDialogHeight = maxHeight
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(28.dp),
                color = cs.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                ) {
                    // Header: Squircle Icon + Title & Subtitle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(iconContainerColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconContentColor,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                    lineHeight = 25.sp,
                                ),
                                color = cs.onSurface,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 16.dp, bottom = 14.dp),
                        color = cs.outlineVariant.copy(alpha = 0.35f),
                    )

                    // Dialog Content Body (wrapped in weighted Column so long content scrolls and buttons never get pushed offscreen)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        content()
                    }

                    // Bottom Action Buttons (Dual Pills or Stacked when space is tight or display size is enlarged)
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                    ) {
                        val fontScale = LocalDensity.current.fontScale
                        val longestLabel = maxOf(confirmLabel.length, cancelLabel.length)
                        val shouldStack = maxWidth < 270.dp ||
                            (longestLabel > 10 && (maxWidth < 320.dp || fontScale > 1.05f)) ||
                            (longestLabel > 7 && maxWidth < 290.dp) ||
                            fontScale > 1.2f

                    if (shouldStack) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (destructive) cs.error else cs.primary,
                                    contentColor = if (destructive) cs.onError else cs.onPrimary,
                                    disabledContainerColor = cs.surfaceContainerHighest.copy(alpha = 0.5f),
                                    disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
                                ),
                            ) {
                                Text(
                                    text = confirmLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Button(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.surfaceContainerHighest,
                                    contentColor = cs.onSurface,
                                ),
                            ) {
                                Text(
                                    text = cancelLabel,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.surfaceContainerHighest,
                                    contentColor = cs.onSurface,
                                ),
                            ) {
                                Text(
                                    text = cancelLabel,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Button(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (destructive) cs.error else cs.primary,
                                    contentColor = if (destructive) cs.onError else cs.onPrimary,
                                    disabledContainerColor = cs.surfaceContainerHighest.copy(alpha = 0.5f),
                                    disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
                                ),
                            ) {
                                Text(
                                    text = confirmLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
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

/** Single text field dialog (new folder, new file, rename). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    selectStem: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var value by remember {
        val stem = if (selectStem) initial.substringBeforeLast('.', initial).length else initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, stem)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val icon = when {
        title.contains("folder", ignoreCase = true) -> Icons.Rounded.CreateNewFolder
        title.contains("rename", ignoreCase = true) -> Icons.Rounded.DriveFileRenameOutline
        else -> Icons.AutoMirrored.Rounded.NoteAdd
    }

    val subtitle = when {
        title.contains("rename", ignoreCase = true) -> "Enter a new name for this item"
        title.contains("folder", ignoreCase = true) -> "Enter a name for the new folder"
        else -> "Enter a name for the new file"
    }

    PopupDialogContainer(
        onDismiss = onDismiss,
        icon = icon,
        iconContainerColor = cs.primaryContainer,
        iconContentColor = cs.primary,
        title = title,
        subtitle = subtitle,
        confirmLabel = confirmLabel,
        confirmEnabled = value.text.isNotBlank(),
        onConfirm = { onConfirm(value.text) },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    subtitle: String? = null,
    items: List<FileItem>? = null,
    isTrash: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val icon = when {
        isTrash -> Icons.Rounded.Delete
        title.contains("delete", ignoreCase = true) || title.contains("remove", ignoreCase = true) || destructive -> Icons.Rounded.DeleteForever
        else -> Icons.Rounded.Info
    }
    val iconContainerColor = cs.primaryContainer
    val iconContentColor = cs.primary

    PopupDialogContainer(
        onDismiss = onDismiss,
        icon = icon,
        iconContainerColor = iconContainerColor,
        iconContentColor = iconContentColor,
        title = title,
        subtitle = subtitle,
        confirmLabel = confirmLabel,
        destructive = false,
        onConfirm = onConfirm,
    ) {
        if (!items.isNullOrEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // "Includes" block with file list
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Includes",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        val displayed = items.take(3)
                        for (item in displayed) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (items.size > 3) {
                            val moreCount = items.size - displayed.size
                            Text(
                                text = "+${String.format(Locale.US, "%,d", moreCount)} more",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                ),
                                color = cs.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                // "Restore" / "Warning" block
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = if (isTrash) Icons.Outlined.Restore else Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isTrash) "Restore" else "Warning",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isTrash) "You can restore them from Trash." else message,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = cs.onSurface,
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = cs.onSurface,
                )
            }
        }
    }
}

@Composable
fun PasswordDialog(
    archiveName: String,
    wrong: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var password by remember { mutableStateOf("") }

    PopupDialogContainer(
        onDismiss = onDismiss,
        icon = Icons.Rounded.Lock,
        iconContainerColor = cs.primaryContainer,
        iconContentColor = cs.primary,
        title = "Password required",
        subtitle = archiveName,
        confirmLabel = "Unlock",
        confirmEnabled = password.isNotEmpty(),
        onConfirm = { onConfirm(password) },
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = { Text("Password") },
            shape = RoundedCornerShape(16.dp),
            isError = wrong,
            supportingText = if (wrong) ({ Text("Wrong password") }) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = cs.primaryContainer,
                    selectedLabelColor = cs.onPrimaryContainer,
                ),
            )
        }
    }
}


/**
 * File details view. Delegates to [dev.neroices.zibzip.ui.files.DetailsBottomSheet].
 */
@Composable
fun DetailsDialog(
    items: List<FileItem>,
    folderStats: suspend (String) -> FolderStats,
    onDismiss: () -> Unit,
    calculateSha256: (suspend (String) -> String)? = null,
) {
    dev.neroices.zibzip.ui.files.DetailsBottomSheet(
        items = items,
        folderStats = folderStats,
        onDismiss = onDismiss,
        calculateSha256 = calculateSha256,
    )
}
