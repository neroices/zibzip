package dev.neroices.zibzip.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.ui.openFile
import dev.neroices.zibzip.ui.shareFiles
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

private data class SearchMatchItem(
    val lineIndex: Int,
    val startIndex: Int,
    val endIndex: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    path: String,
    isReadOnly: Boolean = false,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colorScheme = MaterialTheme.colorScheme
    val colors = remember(isDark, colorScheme) { createSyntaxColors(isDark, colorScheme) }

    val file = remember(path) { File(path) }
    val fileName = remember(path) { file.name }
    val language = remember(fileName) { SyntaxLanguage.detect(fileName) }

    val fileSize = remember(path) { if (file.exists()) file.length() else 0L }

    val isMarkdown = remember(language) { language == SyntaxLanguage.Markdown }
    var showMarkdownPreview by rememberSaveable { mutableStateOf(isMarkdown) }

    // Default to View mode when opening text/code
    var isEditMode by rememberSaveable { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var originalContent by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isDirty by remember { mutableStateOf(false) }

    var wordWrap by rememberSaveable {
        mutableStateOf(language == SyntaxLanguage.Markdown || language == SyntaxLanguage.PlainText || language == SyntaxLanguage.Lrc)
    }
    var showLineNumbers by rememberSaveable { mutableStateOf(true) }

    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCaseSensitive by remember { mutableStateOf(false) }
    var activeMatchIndex by remember { mutableIntStateOf(-1) }

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showLargeFileEditDialog by remember { mutableStateOf(false) }
    var showLargeFileOpenDialog by remember { mutableStateOf(false) }
    var showFileTooLargeDialog by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val lazyListState = rememberLazyListState()
    val markdownListState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }

    // Load file content on background thread
    fun loadFileContent() {
        isLoading = true
        loadError = null
        scope.launch(Dispatchers.IO) {
            try {
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        loadError = "File does not exist"
                        isLoading = false
                    }
                    return@launch
                }

                if (file.length() > MAX_SUPPORTED_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        loadError = "File is too large (${formatSize(file.length())}). Maximum supported size is ${formatSize(MAX_SUPPORTED_FILE_SIZE)}."
                        isLoading = false
                        showFileTooLargeDialog = true
                    }
                    return@launch
                }

                val text = file.inputStream().bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }

                withContext(Dispatchers.Main) {
                    originalContent = text
                    textFieldValue = TextFieldValue(text)
                    isDirty = false
                    isLoading = false
                }
            } catch (e: OutOfMemoryError) {
                System.gc()
                withContext(Dispatchers.Main) {
                    loadError = "Out of memory: file is too large (${formatSize(file.length())})"
                    isLoading = false
                    showFileTooLargeDialog = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadError = e.message ?: "Failed to read file"
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(path) {
        isEditMode = false
        showMarkdownPreview = (SyntaxLanguage.detect(File(path).name) == SyntaxLanguage.Markdown)
        val size = if (file.exists()) file.length() else 0L
        when {
            size > MAX_SUPPORTED_FILE_SIZE -> {
                isLoading = false
                loadError = "File is too large (${formatSize(size)}). Maximum supported size is ${formatSize(MAX_SUPPORTED_FILE_SIZE)}."
                showFileTooLargeDialog = true
            }
            size > LARGE_FILE_OPEN_WARNING_THRESHOLD -> {
                isLoading = false
                showLargeFileOpenDialog = true
            }
            else -> {
                loadFileContent()
            }
        }
    }

    // Split into lines for line-based viewer
    val lines = remember(textFieldValue.text) {
        val raw = textFieldValue.text
        if (raw.isEmpty()) listOf("") else raw.lines()
    }
    val totalLines = lines.size

    // Calculate search matches across lines
    val searchMatches = remember(searchQuery, searchCaseSensitive, lines) {
        if (searchQuery.isEmpty()) {
            emptyList()
        } else {
            val matches = ArrayList<SearchMatchItem>()
            var charCounter = 0
            for ((lineIdx, line) in lines.withIndex()) {
                var start = 0
                while (true) {
                    val idx = line.indexOf(searchQuery, start, ignoreCase = !searchCaseSensitive)
                    if (idx == -1) break
                    matches.add(SearchMatchItem(lineIdx, charCounter + idx, charCounter + idx + searchQuery.length))
                    start = idx + searchQuery.length
                }
                charCounter += line.length + 1 // +1 for newline
            }
            matches
        }
    }

    LaunchedEffect(searchMatches) {
        if (searchMatches.isEmpty()) {
            activeMatchIndex = -1
        } else if (activeMatchIndex !in searchMatches.indices) {
            activeMatchIndex = 0
        }
    }

    fun jumpToMatch(index: Int) {
        if (index in searchMatches.indices) {
            activeMatchIndex = index
            val match = searchMatches[index]
            scope.launch {
                if (isEditMode) {
                    textFieldValue = textFieldValue.copy(selection = TextRange(match.startIndex, match.endIndex))
                    textLayoutResult?.let { layout ->
                        val line = layout.getLineForOffset(match.startIndex)
                        val top = layout.getLineTop(line).toInt()
                        verticalScrollState.animateScrollTo((top - 120).coerceAtLeast(0))
                    }
                } else if (isMarkdown && showMarkdownPreview) {
                    markdownListState.animateScrollToItem((match.lineIndex / 2).coerceAtLeast(0))
                } else {
                    lazyListState.animateScrollToItem((match.lineIndex - 3).coerceAtLeast(0))
                }
            }
        }
    }

    fun saveContent(onSuccess: () -> Unit = {}) {
        if (isReadOnly) {
            context.zibzipApp.toast("File is opened in read-only mode")
            return
        }
        isSaving = true
        val textToSave = textFieldValue.text
        scope.launch(Dispatchers.IO) {
            try {
                val parentDir = file.parentFile ?: file.absoluteFile.parentFile ?: File(".")
                val tempFile = File.createTempFile(".${file.name}".padEnd(3, '_'), ".tmp", parentDir)
                try {
                    tempFile.writeText(textToSave, Charsets.UTF_8)
                    if (!tempFile.renameTo(file)) {
                        tempFile.copyTo(file, overwrite = true)
                        tempFile.delete()
                    }
                } catch (t: Throwable) {
                    tempFile.delete()
                    throw t
                }
                withContext(Dispatchers.Main) {
                    originalContent = textToSave
                    isDirty = false
                    isSaving = false
                    context.zibzipApp.toast("Saved $fileName")
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    context.zibzipApp.toast("Error saving: ${e.message}")
                }
            }
        }
    }

    // Line start offsets calculation for cursor line/col
    val lineStartOffsets = remember(lines) {
        val offsets = ArrayList<Int>(lines.size + 1)
        var cur = 0
        for (line in lines) {
            offsets.add(cur)
            cur += line.length + 1
        }
        offsets
    }

    val (cursorLine, cursorCol) = remember(textFieldValue.selection, lineStartOffsets) {
        val offset = textFieldValue.selection.start
        val search = lineStartOffsets.binarySearch(offset)
        if (search >= 0) {
            (search + 1) to 1
        } else {
            val insertion = -(search + 1)
            val lineIdx = (insertion - 1).coerceAtLeast(0)
            val col = offset - (lineStartOffsets.getOrNull(lineIdx) ?: 0) + 1
            (lineIdx + 1) to col
        }
    }

    // Back handling
    BackHandler {
        when {
            searchOpen -> searchOpen = false
            isDirty -> showUnsavedDialog = true
            else -> onClose()
        }
    }

    val visualTransformation = remember(
        language,
        colors,
        searchQuery,
        searchCaseSensitive,
        activeMatchIndex,
    ) {
        SyntaxHighlightTransformation(
            language = language,
            colors = colors,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
            activeMatchIndex = activeMatchIndex,
        )
    }

    val codeTextStyle = remember(colors) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = colors.editorText,
        )
    }

    val gutterTextStyle = remember(colors) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = colors.gutterText,
        )
    }

    val maxDigits = totalLines.toString().length.coerceAtLeast(2)
    val gutterWidth = (maxDigits * 8 + 24).dp

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.editorBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            TextEditorTopBar(
                fileName = fileName,
                isDirty = isDirty,
                isEditMode = isEditMode,
                isMarkdown = isMarkdown,
                showMarkdownPreview = showMarkdownPreview,
                isReadOnly = isReadOnly,
                language = language,
                textLength = textFieldValue.text.length.toLong(),
                totalLines = totalLines,
                searchOpen = searchOpen,
                wordWrap = wordWrap,
                showLineNumbers = showLineNumbers,
                isSaving = isSaving,
                editorBackgroundColor = colors.editorBackground,
                onBack = {
                    if (isDirty) showUnsavedDialog = true else onClose()
                },
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (searchOpen) {
                        scope.launch { searchFocusRequester.requestFocus() }
                    }
                },
                onToggleMarkdownPreview = {
                    showMarkdownPreview = !showMarkdownPreview
                },
                onToggleEditMode = {
                    if (isEditMode) {
                        isEditMode = false
                    } else {
                        if (file.length() > LARGE_FILE_EDIT_WARNING_THRESHOLD) {
                            showLargeFileEditDialog = true
                        } else {
                            isEditMode = true
                        }
                    }
                },
                onSave = { saveContent() },
                onToggleWordWrap = { wordWrap = !wordWrap },
                onToggleLineNumbers = { showLineNumbers = !showLineNumbers },
                onCopyAll = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(fileName, textFieldValue.text))
                    context.zibzipApp.toast("Copied all text to clipboard")
                },
                onOpenExternal = { context.openFile(path) },
                onShare = { context.shareFiles(listOf(path)) },
                onDiscardChanges = { loadFileContent() },
            )

            TextEditorSearchBar(
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                searchMatchesCount = searchMatches.size,
                activeMatchIndex = activeMatchIndex,
                searchCaseSensitive = searchCaseSensitive,
                searchFocusRequester = searchFocusRequester,
                onToggleCaseSensitive = { searchCaseSensitive = !searchCaseSensitive },
                onPrevMatch = {
                    if (searchMatches.isNotEmpty()) {
                        val prev = if (activeMatchIndex <= 0) searchMatches.size - 1 else activeMatchIndex - 1
                        jumpToMatch(prev)
                    }
                },
                onNextMatch = {
                    if (searchMatches.isNotEmpty()) {
                        val next = (activeMatchIndex + 1) % searchMatches.size
                        jumpToMatch(next)
                    }
                },
                onCloseSearch = { searchOpen = false },
            )

            // Main content (virtualized line viewer vs editor)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colorScheme.primary)
                    }
                } else if (loadError != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Unable to read file",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.error,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { context.openFile(path) }) {
                            Text("Open in external app")
                        }
                    }
                } else if (!isEditMode) {
                    if (isMarkdown && showMarkdownPreview) {
                        // Markdown preview mode
                        MarkdownViewer(
                            content = textFieldValue.text,
                            colors = colors,
                            searchQuery = searchQuery,
                            searchCaseSensitive = searchCaseSensitive,
                            listState = markdownListState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // View mode (LazyColumn)
                        val currentActiveMatch = if (activeMatchIndex in searchMatches.indices) {
                            searchMatches[activeMatchIndex]
                        } else null

                        SelectionContainer {
                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                itemsIndexed(
                                    items = lines,
                                    key = { index, _ -> index },
                                ) { index, lineText ->
                                    ViewerLineRow(
                                        lineNumber = index + 1,
                                        lineText = lineText,
                                        gutterWidth = gutterWidth,
                                        showLineNumbers = showLineNumbers,
                                        wordWrap = wordWrap,
                                        language = language,
                                        colors = colors,
                                        searchQuery = searchQuery,
                                        searchCaseSensitive = searchCaseSensitive,
                                        isActiveMatchLine = currentActiveMatch?.lineIndex == index,
                                        gutterTextStyle = gutterTextStyle,
                                        codeTextStyle = codeTextStyle,
                                        horizontalScrollState = horizontalScrollState,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Interactive edit mode (BasicTextField: for editing)
                    val textMeasurer = rememberTextMeasurer()
                    val lineMeasureCache = remember { HashMap<Int, TextLayoutResult>() }

                    Row(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Gutter Column (Line Numbers in Edit Mode)
                        if (showLineNumbers) {
                            Canvas(
                                modifier = Modifier
                                    .width(gutterWidth)
                                    .fillMaxHeight()
                                    .background(colors.gutterBackground),
                            ) {
                                val layout = textLayoutResult ?: return@Canvas
                                val scrollY = verticalScrollState.value
                                val viewportH = size.height

                                val firstVisual = layout.getLineForVerticalPosition(scrollY.toFloat()).coerceAtLeast(0)
                                val lastVisual = layout.getLineForVerticalPosition(scrollY + viewportH)
                                    .coerceAtMost(layout.lineCount - 1)

                                val textStr = textFieldValue.text

                                for (v in firstVisual..lastVisual) {
                                    val startChar = layout.getLineStart(v)
                                    val isStartOfLine = startChar == 0 || (startChar <= textStr.length && textStr[startChar - 1] == '\n')
                                    if (isStartOfLine) {
                                        val lineIdx = lineStartOffsets.binarySearch(startChar)
                                        val lineNum = if (lineIdx >= 0) lineIdx + 1 else 1
                                        val topY = layout.getLineTop(v) - scrollY

                                        val measured = lineMeasureCache.getOrPut(lineNum) {
                                            textMeasurer.measure(
                                                text = AnnotatedString(lineNum.toString()),
                                                style = gutterTextStyle,
                                            )
                                        }
                                        val rightX = size.width - measured.size.width - 8.dp.toPx()
                                        drawText(measured, topLeft = Offset(rightX, topY))
                                    }
                                }

                                // Right separator line
                                drawLine(
                                    color = colors.gutterText.copy(alpha = 0.2f),
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                        }

                        // Code Editor Body
                        val editorModifier = if (wordWrap) {
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(verticalScrollState)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        } else {
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        }

                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                if (!isReadOnly) {
                                    textFieldValue = newValue
                                    isDirty = newValue.text != originalContent
                                }
                            },
                            readOnly = isReadOnly,
                            textStyle = codeTextStyle,
                            cursorBrush = SolidColor(colorScheme.primary),
                            visualTransformation = visualTransformation,
                            onTextLayout = { textLayoutResult = it },
                            modifier = editorModifier,
                        )
                    }
                }
            }

            // Status footer bar
            Surface(
                color = colors.gutterBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val statusText = when {
                        isEditMode -> "Ln $cursorLine, Col $cursorCol"
                        isMarkdown && showMarkdownPreview -> "Markdown Preview"
                        else -> "Lines 1–$totalLines"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.gutterText,
                        fontFamily = FontFamily.Monospace,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (wordWrap) "Wrap: ON" else "Wrap: OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.gutterText,
                        )
                        Text(
                            text = "UTF-8",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.gutterText,
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    TextEditorDialogs(
        showUnsavedDialog = showUnsavedDialog,
        onDismissUnsavedDialog = { showUnsavedDialog = false },
        onConfirmDiscard = {
            showUnsavedDialog = false
            onClose()
        },
        onConfirmSaveAndClose = {
            showUnsavedDialog = false
            saveContent { onClose() }
        },
        showLargeFileEditDialog = showLargeFileEditDialog,
        onDismissLargeFileDialog = { showLargeFileEditDialog = false },
        onConfirmLargeFileEdit = {
            showLargeFileEditDialog = false
            isEditMode = true
        },
        onOpenExternalLargeFile = {
            showLargeFileEditDialog = false
            context.openFile(path)
        },
        showFileTooLargeDialog = showFileTooLargeDialog,
        onDismissFileTooLargeDialog = {
            showFileTooLargeDialog = false
            onClose()
        },
        onOpenExternalFileTooLarge = {
            showFileTooLargeDialog = false
            context.openFile(path)
            onClose()
        },
        showLargeFileOpenDialog = showLargeFileOpenDialog,
        onDismissLargeFileOpenDialog = {
            showLargeFileOpenDialog = false
            onClose()
        },
        onConfirmLargeFileOpen = {
            showLargeFileOpenDialog = false
            loadFileContent()
        },
        onOpenExternalLargeFileOpen = {
            showLargeFileOpenDialog = false
            context.openFile(path)
            onClose()
        },
        fileName = fileName,
        fileSize = fileSize,
        totalLines = totalLines,
        fileLength = file.length(),
    )




}

