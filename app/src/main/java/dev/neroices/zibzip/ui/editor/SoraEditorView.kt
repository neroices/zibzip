package dev.neroices.zibzip.ui.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

internal class ZibZipLanguage(
    private val language: SyntaxLanguage,
) : EmptyLanguage() {
    private val analyzeManager = ZibZipAnalyzeManager(language)

    override fun getAnalyzeManager(): AnalyzeManager = analyzeManager

    override fun destroy() {
        analyzeManager.destroy()
    }
}

internal class ZibZipAnalyzeManager(
    private val language: SyntaxLanguage,
) : AsyncIncrementalAnalyzeManager<Int, Unit>() {

    override fun getInitialState(): Int = 0

    override fun stateEquals(state1: Int?, state2: Int?): Boolean = state1 == state2

    override fun onAbandonState(state: Int) {
        // Stateless line tokenization does not track state lifecycle.
    }

    override fun onAddState(state: Int) {
        // Stateless line tokenization does not track state lifecycle.
    }

    override fun computeBlocks(
        content: Content,
        delegate: CodeBlockAnalyzeDelegate,
    ): List<CodeBlock> = emptyList()

    override fun generateSpansForLine(
        result: IncrementalAnalyzeManager.LineTokenizeResult<Int, Unit>,
    ): List<Span> = result.spans ?: emptyList()

    override fun tokenizeLine(
        line: CharSequence,
        state: Int,
        lineIndex: Int,
    ): IncrementalAnalyzeManager.LineTokenizeResult<Int, Unit> {
        val spans = mutableListOf<Span>()
        val textStr = line.toString()

        if (textStr.isEmpty() || language == SyntaxLanguage.PlainText) {
            spans.add(SpanFactory.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
            return IncrementalAnalyzeManager.LineTokenizeResult(0, emptyList(), spans)
        }

        val patterns = SyntaxRegistry.getPatterns(language)
        val matcher = patterns.pattern?.matcher(textStr)

        if (matcher == null) {
            spans.add(SpanFactory.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
            return IncrementalAnalyzeManager.LineTokenizeResult(0, emptyList(), spans)
        }

        var lastEnd = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (start == end) continue

            if (start > lastEnd) {
                spans.add(SpanFactory.obtain(lastEnd, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
            }

            for (groupName in patterns.groupNames) {
                if (matcher.group(groupName) != null) {
                    val style = when {
                        groupName.startsWith("comment") -> TextStyle.makeStyle(
                            EditorColorScheme.COMMENT,
                            0,
                            false,
                            true,
                            false,
                        )
                        groupName.startsWith("string") -> TextStyle.makeStyle(EditorColorScheme.LITERAL)
                        groupName.startsWith("keyword") -> TextStyle.makeStyle(
                            EditorColorScheme.KEYWORD,
                            0,
                            true,
                            false,
                            false,
                        )
                        groupName.startsWith("type") -> TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME)
                        groupName.startsWith("number") -> TextStyle.makeStyle(EditorColorScheme.LITERAL)
                        groupName.startsWith("annotation") -> TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
                        groupName.startsWith("tag") -> TextStyle.makeStyle(
                            EditorColorScheme.HTML_TAG,
                            0,
                            true,
                            false,
                            false,
                        )
                        groupName.startsWith("attribute") -> TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)
                        groupName.startsWith("operator") -> TextStyle.makeStyle(EditorColorScheme.OPERATOR)
                        else -> TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
                    }
                    spans.add(SpanFactory.obtain(start, style))
                    break
                }
            }
            lastEnd = end
        }

        if (lastEnd < textStr.length || spans.isEmpty()) {
            spans.add(SpanFactory.obtain(lastEnd, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
        }

        return IncrementalAnalyzeManager.LineTokenizeResult(0, emptyList(), spans)
    }
}

internal fun applyColorScheme(scheme: EditorColorScheme, colors: SyntaxColors, isDark: Boolean) {
    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.editorBackground.toArgb())
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.gutterBackground.toArgb())
    scheme.setColor(EditorColorScheme.LINE_NUMBER, colors.gutterText.toArgb())
    scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.editorText.toArgb())
    scheme.setColor(EditorColorScheme.LINE_DIVIDER, colors.gutterText.copy(alpha = 0.2f).toArgb())
    scheme.setColor(EditorColorScheme.TEXT_NORMAL, colors.editorText.toArgb())
    scheme.setColor(EditorColorScheme.SELECTION_INSERT, colors.keyword.toArgb())
    scheme.setColor(EditorColorScheme.SELECTION_HANDLE, colors.keyword.toArgb())
    scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, if (isDark) 0x663E4451 else 0x44A0C5E8)
    scheme.setColor(EditorColorScheme.CURRENT_LINE, if (isDark) 0x1AFFFFFF else 0x0A000000)
    scheme.setColor(EditorColorScheme.KEYWORD, colors.keyword.toArgb())
    scheme.setColor(EditorColorScheme.COMMENT, colors.comment.toArgb())
    scheme.setColor(EditorColorScheme.LITERAL, colors.string.toArgb())
    scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, colors.type.toArgb())
    scheme.setColor(EditorColorScheme.ANNOTATION, colors.annotation.toArgb())
    scheme.setColor(EditorColorScheme.HTML_TAG, colors.tag.toArgb())
    scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, colors.attribute.toArgb())
    scheme.setColor(EditorColorScheme.OPERATOR, colors.operator.toArgb())
    scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.searchMatch.toArgb())
    scheme.setColor(EditorColorScheme.TEXT_HIGHLIGHT_STRONG_BACKGROUND, colors.activeSearchMatch.toArgb())
}

@Composable
internal fun SoraEditorView(
    text: String,
    onTextChanged: (String) -> Unit,
    isReadOnly: Boolean,
    showLineNumbers: Boolean,
    wordWrap: Boolean,
    fontSizeSp: Float,
    language: SyntaxLanguage,
    colors: SyntaxColors,
    isDark: Boolean,
    searchQuery: String,
    searchCaseSensitive: Boolean,
    onEditorReady: (CodeEditor) -> Unit,
    onCursorChange: (line: Int, col: Int, selectedChars: Int, totalLines: Int) -> Unit,
    onSearchMatchesCountChange: (count: Int, activeIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorInstance by remember { mutableStateOf<CodeEditor?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(fontSizeSp)
                isWordwrap = wordWrap
                isEditable = !isReadOnly
                isLineNumberEnabled = showLineNumbers
                setPinLineNumber(false)

                applyColorScheme(colorScheme, colors, isDark)
                setEditorLanguage(ZibZipLanguage(language))
                setText(text)

                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    val cur = this.cursor
                    val selLen = if (cur.isSelected) (cur.right - cur.left).coerceAtLeast(0) else 0
                    onTextChanged(this.text.toString())
                    onCursorChange(cur.leftLine + 1, cur.leftColumn + 1, selLen, this.lineCount)
                }

                subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                    val cur = this.cursor
                    val selLen = if (cur.isSelected) (cur.right - cur.left).coerceAtLeast(0) else 0
                    onCursorChange(cur.leftLine + 1, cur.leftColumn + 1, selLen, this.lineCount)
                }

                subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
                    val searcher = this.searcher
                    if (searcher.hasQuery()) {
                        onSearchMatchesCountChange(
                            searcher.matchedPositionCount,
                            searcher.currentMatchedPositionIndex,
                        )
                    } else {
                        onSearchMatchesCountChange(0, -1)
                    }
                }

                editorInstance = this
                onEditorReady(this)
            }
        },
        update = { editor ->
            if (editor.isEditable != !isReadOnly) {
                editor.isEditable = !isReadOnly
            }
            if (editor.isWordwrap != wordWrap) {
                editor.isWordwrap = wordWrap
            }
            if (editor.isLineNumberEnabled != showLineNumbers) {
                editor.isLineNumberEnabled = showLineNumbers
            }
            applyColorScheme(editor.colorScheme, colors, isDark)
        },
    )

    LaunchedEffect(text, editorInstance) {
        val editor = editorInstance ?: return@LaunchedEffect
        if (editor.text.toString() != text) {
            editor.setText(text)
        }
    }

    LaunchedEffect(fontSizeSp, editorInstance) {
        editorInstance?.setTextSize(fontSizeSp)
    }

    LaunchedEffect(language, editorInstance) {
        editorInstance?.setEditorLanguage(ZibZipLanguage(language))
    }

    LaunchedEffect(searchQuery, searchCaseSensitive, editorInstance) {
        val searcher = editorInstance?.searcher ?: return@LaunchedEffect
        if (searchQuery.isNotEmpty()) {
            val options = EditorSearcher.SearchOptions(
                EditorSearcher.SearchOptions.TYPE_NORMAL,
                !searchCaseSensitive,
            )
            searcher.search(searchQuery, options)
        } else if (searcher.hasQuery()) {
            searcher.stopSearch()
            onSearchMatchesCountChange(0, -1)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            editorInstance?.release()
        }
    }
}
