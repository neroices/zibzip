package dev.neroices.zibzip.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatLineSpacing
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.zibzipApp

internal const val LARGE_FILE_EDIT_WARNING_THRESHOLD = 512L * 1024L
internal const val LARGE_FILE_OPEN_WARNING_THRESHOLD = 2L * 1024L * 1024L
internal const val MAX_SUPPORTED_FILE_SIZE = 10L * 1024L * 1024L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextEditorTopBar(
    fileName: String,
    isDirty: Boolean,
    isEditMode: Boolean,
    isMarkdown: Boolean,
    showMarkdownPreview: Boolean,
    isReadOnly: Boolean,
    language: SyntaxLanguage,
    textLength: Long,
    totalLines: Int,
    searchOpen: Boolean,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    isSaving: Boolean,
    editorBackgroundColor: Color,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleMarkdownPreview: () -> Unit,
    onToggleEditMode: () -> Unit,
    onSave: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onCopyAll: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onDiscardChanges: () -> Unit,
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isDirty) {
                        Text(
                            text = "  ●",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }
                val modeSubtitle = when {
                    isEditMode -> "Edit Mode"
                    isMarkdown && showMarkdownPreview -> "Preview"
                    else -> "View Mode"
                }
                Text(
                    text = "${language.displayName} • ${formatSize(textLength)} • $totalLines lines • $modeSubtitle${if (isReadOnly) " • Read-only" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (isMarkdown && !isEditMode) {
                IconButton(onClick = onToggleMarkdownPreview) {
                    Icon(
                        imageVector = if (showMarkdownPreview) Icons.Rounded.Code else Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = if (showMarkdownPreview) "View raw markdown" else "View markdown preview",
                        tint = if (showMarkdownPreview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (!isReadOnly) {
                IconButton(onClick = onToggleEditMode) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Rounded.Visibility else Icons.Rounded.Edit,
                        contentDescription = if (isEditMode) "Switch to View Mode" else "Switch to Edit Mode",
                        tint = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(
                    onClick = onSave,
                    enabled = !isSaving && isDirty,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = "Save",
                            tint = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                    )
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    if (isMarkdown && !isEditMode) {
                        DropdownMenuItem(
                            text = { Text(if (showMarkdownPreview) "View raw markdown" else "View markdown preview") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (showMarkdownPreview) Icons.Rounded.Code else Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onToggleMarkdownPreview()
                                showMoreMenu = false
                            },
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text(if (wordWrap) "Disable word wrap" else "Enable word wrap") },
                        trailingIcon = {
                            if (wordWrap) Icon(Icons.Rounded.Check, contentDescription = null)
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.WrapText, contentDescription = null) },
                        onClick = {
                            onToggleWordWrap()
                            showMoreMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (showLineNumbers) "Hide line numbers" else "Show line numbers") },
                        trailingIcon = {
                            if (showLineNumbers) Icon(Icons.Rounded.Check, contentDescription = null)
                        },
                        leadingIcon = { Icon(Icons.Rounded.FormatLineSpacing, contentDescription = null) },
                        onClick = {
                            onToggleLineNumbers()
                            showMoreMenu = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Copy all text") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            onCopyAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in external app") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            onOpenExternal()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share file") },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        onClick = {
                            showMoreMenu = false
                            onShare()
                        },
                    )
                    if (isDirty) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Discard changes") },
                            leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onDiscardChanges()
                            },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = editorBackgroundColor,
        ),
    )
}

@Composable
internal fun TextEditorSearchBar(
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchMatchesCount: Int,
    activeMatchIndex: Int,
    searchCaseSensitive: Boolean,
    searchFocusRequester: FocusRequester,
    onToggleCaseSensitive: () -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = searchOpen,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            color = colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Find in document", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                )

                Text(
                    text = if (searchQuery.isEmpty()) "" else if (searchMatchesCount == 0) "0/0" else "${activeMatchIndex + 1}/$searchMatchesCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                IconButton(
                    onClick = onToggleCaseSensitive,
                    modifier = Modifier.size(36.dp),
                ) {
                    Text(
                        text = "Aa",
                        fontWeight = if (searchCaseSensitive) FontWeight.Bold else FontWeight.Normal,
                        color = if (searchCaseSensitive) colorScheme.primary else colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }

                IconButton(
                    onClick = onPrevMatch,
                    enabled = searchMatchesCount > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                }

                IconButton(
                    onClick = onNextMatch,
                    enabled = searchMatchesCount > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                }

                IconButton(
                    onClick = onCloseSearch,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close search")
                }
            }
        }
    }
}

@Composable
internal fun TextEditorDialogs(
    showUnsavedDialog: Boolean,
    onDismissUnsavedDialog: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onConfirmSaveAndClose: () -> Unit,
    showLargeFileEditDialog: Boolean,
    onDismissLargeFileDialog: () -> Unit,
    onConfirmLargeFileEdit: () -> Unit,
    onOpenExternalLargeFile: () -> Unit,
    showFileTooLargeDialog: Boolean,
    onDismissFileTooLargeDialog: () -> Unit,
    onOpenExternalFileTooLarge: () -> Unit,
    showLargeFileOpenDialog: Boolean,
    onDismissLargeFileOpenDialog: () -> Unit,
    onConfirmLargeFileOpen: () -> Unit,
    onOpenExternalLargeFileOpen: () -> Unit,
    fileName: String,
    fileSize: Long,
    totalLines: Int,
    fileLength: Long,
) {
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = onDismissUnsavedDialog,
            title = { Text("Save changes?") },
            text = { Text("You have unsaved edits in \"$fileName\". Do you want to save your changes before closing?") },
            confirmButton = {
                Button(onClick = onConfirmSaveAndClose) {
                    Text("Save & Exit")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onConfirmDiscard) {
                        Text("Discard")
                    }
                    TextButton(onClick = onDismissUnsavedDialog) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    if (showLargeFileEditDialog) {
        AlertDialog(
            onDismissRequest = onDismissLargeFileDialog,
            title = { Text("Edit Large File?") },
            text = {
                Text(
                    "This file is ${formatSize(fileLength)} ($totalLines lines). " +
                        "Editing very large files in the mobile text input may experience typing latency.\n\n" +
                        "Would you like to edit directly or open in an external editor?",
                )
            },
            confirmButton = {
                Button(onClick = onConfirmLargeFileEdit) {
                    Text("Edit anyway")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenExternalLargeFile) {
                        Text("Open external")
                    }
                    TextButton(onClick = onDismissLargeFileDialog) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    if (showFileTooLargeDialog) {
        AlertDialog(
            onDismissRequest = onDismissFileTooLargeDialog,
            title = { Text("File too large") },
            text = {
                Text(
                    "This file is ${formatSize(fileSize)}. Files larger than " +
                        "${formatSize(MAX_SUPPORTED_FILE_SIZE)} cannot be opened in the built-in text editor " +
                        "to prevent memory exhaustion.\n\nWould you like to open it in an external app?",
                )
            },
            confirmButton = {
                Button(onClick = onOpenExternalFileTooLarge) {
                    Text("Open external")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissFileTooLargeDialog) {
                    Text("Close")
                }
            },
        )
    }

    if (showLargeFileOpenDialog) {
        AlertDialog(
            onDismissRequest = onDismissLargeFileOpenDialog,
            title = { Text("Open large file?") },
            text = {
                Text(
                    "This file is ${formatSize(fileSize)}. Loading and parsing large files in memory " +
                        "may take a moment.\n\nWould you like to continue or open in an external app?",
                )
            },
            confirmButton = {
                Button(onClick = onConfirmLargeFileOpen) {
                    Text("Open anyway")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenExternalLargeFileOpen) {
                        Text("Open external")
                    }
                    TextButton(onClick = onDismissLargeFileOpenDialog) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
internal fun ViewerLineRow(
    lineNumber: Int,
    lineText: String,
    gutterWidth: Dp,
    showLineNumbers: Boolean,
    wordWrap: Boolean,
    language: SyntaxLanguage,
    colors: SyntaxColors,
    searchQuery: String,
    searchCaseSensitive: Boolean,
    isActiveMatchLine: Boolean,
    gutterTextStyle: TextStyle,
    codeTextStyle: TextStyle,
    horizontalScrollState: ScrollState,
) {
    val highlightedText = remember(lineText, language, colors, searchQuery, searchCaseSensitive, isActiveMatchLine) {
        highlightLine(
            line = lineText,
            language = language,
            colors = colors,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
            isActiveMatchLine = isActiveMatchLine,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showLineNumbers) {
            Text(
                text = "$lineNumber",
                style = gutterTextStyle,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(gutterWidth)
                    .padding(end = 8.dp),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(colors.gutterText.copy(alpha = 0.2f)),
            )
            Spacer(Modifier.width(8.dp))
        }

        val textModifier = if (wordWrap) {
            Modifier
                .weight(1f)
                .padding(end = 8.dp)
        } else {
            Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .padding(end = 8.dp)
        }

        Text(
            text = highlightedText,
            style = codeTextStyle,
            softWrap = wordWrap,
            modifier = textModifier,
        )
    }
}
