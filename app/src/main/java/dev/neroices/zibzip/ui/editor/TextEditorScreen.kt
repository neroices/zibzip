package dev.neroices.zibzip.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.ui.openFile
import dev.neroices.zibzip.ui.shareFiles
import dev.neroices.zibzip.zibzipApp
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var isReadOnlyLock by rememberSaveable { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var originalContent by remember { mutableStateOf("") }
    var fileContent by remember { mutableStateOf("") }
    var isDirty by remember { mutableStateOf(false) }

    var editorInstance by remember { mutableStateOf<CodeEditor?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorCol by remember { mutableIntStateOf(1) }
    var selectedChars by remember { mutableIntStateOf(0) }
    var totalLines by remember { mutableIntStateOf(1) }

    var fontSizeSp by rememberSaveable { mutableFloatStateOf(14f) }
    var wordWrap by rememberSaveable {
        mutableStateOf(language == SyntaxLanguage.Markdown || language == SyntaxLanguage.PlainText || language == SyntaxLanguage.Lrc)
    }
    var showLineNumbers by rememberSaveable { mutableStateOf(true) }

    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCaseSensitive by remember { mutableStateOf(false) }
    var searchMatchesCount by remember { mutableIntStateOf(0) }
    var activeMatchIndex by remember { mutableIntStateOf(-1) }

    var showSaveConfirmDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showLargeFileEditDialog by remember { mutableStateOf(false) }
    var showLargeFileOpenDialog by remember { mutableStateOf(false) }
    var showFileTooLargeDialog by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }

    val markdownListState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }

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
                    fileContent = text
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

    fun saveContent(onSuccess: () -> Unit = {}) {
        if (isReadOnly) {
            context.zibzipApp.toast("File is opened in read-only mode")
            return
        }
        isSaving = true
        val textToSave = editorInstance?.text?.toString() ?: fileContent
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
                    fileContent = textToSave
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

    BackHandler {
        when {
            searchOpen -> searchOpen = false
            isDirty -> showUnsavedDialog = true
            else -> onClose()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.editorBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            TextEditorTopBar(
                fileName = fileName,
                isDirty = isDirty,
                isMarkdown = isMarkdown,
                showMarkdownPreview = showMarkdownPreview,
                isReadOnly = isReadOnly,
                isReadOnlyLock = isReadOnlyLock,
                language = language,
                textLength = (editorInstance?.text?.length ?: fileContent.length).toLong(),
                totalLines = totalLines,
                searchOpen = searchOpen,
                wordWrap = wordWrap,
                showLineNumbers = showLineNumbers,
                isSaving = isSaving,
                canUndo = canUndo,
                canRedo = canRedo,
                fontSizeSp = fontSizeSp,
                editorBackgroundColor = colors.editorBackground,
                onBack = {
                    if (isDirty) showUnsavedDialog = true else onClose()
                },
                onUndo = {
                    editorInstance?.undo()
                    canUndo = editorInstance?.canUndo() ?: false
                    canRedo = editorInstance?.canRedo() ?: false
                },
                onRedo = {
                    editorInstance?.redo()
                    canUndo = editorInstance?.canUndo() ?: false
                    canRedo = editorInstance?.canRedo() ?: false
                },
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (searchOpen) {
                        scope.launch { searchFocusRequester.requestFocus() }
                    }
                },
                onToggleMarkdownPreview = {
                    if (!showMarkdownPreview) {
                        editorInstance?.let { fileContent = it.text.toString() }
                    }
                    showMarkdownPreview = !showMarkdownPreview
                },
                onToggleReadOnlyLock = {
                    isReadOnlyLock = !isReadOnlyLock
                },
                onSave = { showSaveConfirmDialog = true },
                onToggleWordWrap = { wordWrap = !wordWrap },
                onToggleLineNumbers = { showLineNumbers = !showLineNumbers },
                onIncreaseFontSize = {
                    fontSizeSp = (fontSizeSp + 2f).coerceAtMost(30f)
                },
                onDecreaseFontSize = {
                    fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(10f)
                },
                onResetFontSize = {
                    fontSizeSp = 14f
                },
                onCopyAll = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val currentText = editorInstance?.text?.toString() ?: fileContent
                    clipboard?.setPrimaryClip(ClipData.newPlainText(fileName, currentText))
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
                searchMatchesCount = searchMatchesCount,
                activeMatchIndex = activeMatchIndex,
                searchCaseSensitive = searchCaseSensitive,
                searchFocusRequester = searchFocusRequester,
                onToggleCaseSensitive = { searchCaseSensitive = !searchCaseSensitive },
                onPrevMatch = {
                    val searcher = editorInstance?.searcher
                    if (searcher?.hasQuery() == true) {
                        searcher.gotoPrevious()
                    }
                },
                onNextMatch = {
                    val searcher = editorInstance?.searcher
                    if (searcher?.hasQuery() == true) {
                        searcher.gotoNext()
                    }
                },
                onCloseSearch = {
                    searchOpen = false
                    searchQuery = ""
                },
            )

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
                    TextEditorErrorView(
                        errorMessage = loadError ?: "",
                        onOpenExternal = { context.openFile(path) },
                    )
                } else if (isMarkdown && showMarkdownPreview) {
                    MarkdownViewer(
                        content = editorInstance?.text?.toString() ?: fileContent,
                        colors = colors,
                        searchQuery = searchQuery,
                        searchCaseSensitive = searchCaseSensitive,
                        listState = markdownListState,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SoraEditorView(
                        text = fileContent,
                        onTextChanged = { newText ->
                            isDirty = newText != originalContent
                            canUndo = editorInstance?.canUndo() ?: false
                            canRedo = editorInstance?.canRedo() ?: false
                        },
                        isReadOnly = isReadOnly || isReadOnlyLock,
                        showLineNumbers = showLineNumbers,
                        wordWrap = wordWrap,
                        fontSizeSp = fontSizeSp,
                        language = language,
                        colors = colors,
                        isDark = isDark,
                        searchQuery = searchQuery,
                        searchCaseSensitive = searchCaseSensitive,
                        onEditorReady = { editor ->
                            editorInstance = editor
                            canUndo = editor.canUndo()
                            canRedo = editor.canRedo()
                        },
                        onCursorChange = { line, col, selected, linesCount ->
                            cursorLine = line
                            cursorCol = col
                            selectedChars = selected
                            totalLines = linesCount
                            canUndo = editorInstance?.canUndo() ?: false
                            canRedo = editorInstance?.canRedo() ?: false
                        },
                        onSearchMatchesCountChange = { count, activeIndex ->
                            searchMatchesCount = count
                            activeMatchIndex = activeIndex
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            TextEditorStatusBar(
                cursorLine = cursorLine,
                cursorCol = cursorCol,
                selectedChars = selectedChars,
                totalLines = totalLines,
                wordWrap = wordWrap,
                fontSizeSp = fontSizeSp,
                isReadOnly = isReadOnly || isReadOnlyLock,
                colors = colors,
            )
        }
    }

    TextEditorDialogs(
        showSaveConfirmDialog = showSaveConfirmDialog,
        onDismissSaveConfirmDialog = { showSaveConfirmDialog = false },
        onConfirmSave = {
            showSaveConfirmDialog = false
            saveContent()
        },
        showUnsavedDialog = showUnsavedDialog,
        onDismissUnsavedDialog = { showUnsavedDialog = false },
        onConfirmDiscard = {
            showUnsavedDialog = false
            onClose()
        },
        onConfirmSaveAndClose = {
            showUnsavedDialog = false
            saveContent(onSuccess = onClose)
        },
        showLargeFileEditDialog = showLargeFileEditDialog,
        onDismissLargeFileDialog = { showLargeFileEditDialog = false },
        onConfirmLargeFileEdit = {
            showLargeFileEditDialog = false
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
