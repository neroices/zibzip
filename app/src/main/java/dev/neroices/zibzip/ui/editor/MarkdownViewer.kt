package dev.neroices.zibzip.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.zibzipApp

@Composable
fun MarkdownViewer(
    content: String,
    colors: SyntaxColors,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    searchCaseSensitive: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val blocks = remember(content) { MarkdownParser.parse(content) }

    val codeBg = colorScheme.surfaceContainerHigh
    val codeColor = colorScheme.onSurfaceVariant
    val primaryColor = colorScheme.primary
    val searchHighlightColor = colors.searchMatch

    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = blocks,
                key = { index, _ -> index },
            ) { _, block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        HeadingView(
                            block = block,
                            primaryColor = primaryColor,
                            codeBg = codeBg,
                            codeColor = codeColor,
                            searchQuery = searchQuery,
                            searchCaseSensitive = searchCaseSensitive,
                            searchHighlightColor = searchHighlightColor,
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        val inlineText = remember(block.text, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
                            MarkdownParser.parseInline(
                                text = block.text,
                                primaryColor = primaryColor,
                                codeBackground = codeBg,
                                codeColor = codeColor,
                                searchQuery = searchQuery,
                                searchCaseSensitive = searchCaseSensitive,
                                searchHighlightColor = searchHighlightColor,
                            )
                        }
                        Text(
                            text = inlineText,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is MarkdownBlock.Blockquote -> {
                        BlockquoteView(
                            block = block,
                            primaryColor = primaryColor,
                            codeBg = codeBg,
                            codeColor = codeColor,
                            searchQuery = searchQuery,
                            searchCaseSensitive = searchCaseSensitive,
                            searchHighlightColor = searchHighlightColor,
                        )
                    }

                    is MarkdownBlock.CodeBlock -> {
                        CodeBlockView(
                            block = block,
                            colors = colors,
                            searchQuery = searchQuery,
                            searchCaseSensitive = searchCaseSensitive,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Code snippet", block.code))
                                context.zibzipApp.toast("Copied code to clipboard")
                            },
                        )
                    }

                    is MarkdownBlock.BulletItem -> {
                        val inlineText = remember(block.text, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
                            MarkdownParser.parseInline(
                                text = block.text,
                                primaryColor = primaryColor,
                                codeBackground = codeBg,
                                codeColor = codeColor,
                                searchQuery = searchQuery,
                                searchCaseSensitive = searchCaseSensitive,
                                searchHighlightColor = searchHighlightColor,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (block.level * 16).dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "•",
                                color = primaryColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = inlineText,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 20.sp,
                                color = colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MarkdownBlock.NumberedItem -> {
                        val inlineText = remember(block.text, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
                            MarkdownParser.parseInline(
                                text = block.text,
                                primaryColor = primaryColor,
                                codeBackground = codeBg,
                                codeColor = codeColor,
                                searchQuery = searchQuery,
                                searchCaseSensitive = searchCaseSensitive,
                                searchHighlightColor = searchHighlightColor,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "${block.number}.",
                                color = primaryColor,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .widthIn(min = 22.dp)
                                    .padding(end = 6.dp),
                            )
                            Text(
                                text = inlineText,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 20.sp,
                                color = colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MarkdownBlock.TaskItem -> {
                        val inlineText = remember(block.text, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
                            MarkdownParser.parseInline(
                                text = block.text,
                                primaryColor = primaryColor,
                                codeBackground = codeBg,
                                codeColor = codeColor,
                                searchQuery = searchQuery,
                                searchCaseSensitive = searchCaseSensitive,
                                searchHighlightColor = searchHighlightColor,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (block.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                contentDescription = if (block.checked) "Completed" else "Incomplete",
                                tint = if (block.checked) primaryColor else colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(end = 8.dp),
                            )
                            Text(
                                text = inlineText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (block.checked) colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    is MarkdownBlock.Table -> {
                        TableView(
                            block = block,
                            primaryColor = primaryColor,
                            codeBg = codeBg,
                            codeColor = codeColor,
                            searchQuery = searchQuery,
                            searchCaseSensitive = searchCaseSensitive,
                            searchHighlightColor = searchHighlightColor,
                        )
                    }

                    is MarkdownBlock.Divider -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadingView(
    block: MarkdownBlock.Heading,
    primaryColor: Color,
    codeBg: Color,
    codeColor: Color,
    searchQuery: String,
    searchCaseSensitive: Boolean,
    searchHighlightColor: Color,
) {
    val colorScheme = MaterialTheme.colorScheme
    val (style, topPadding) = when (block.level) {
        1 -> MaterialTheme.typography.headlineMedium to 12.dp
        2 -> MaterialTheme.typography.titleLarge to 10.dp
        3 -> MaterialTheme.typography.titleMedium to 8.dp
        4 -> MaterialTheme.typography.titleSmall to 6.dp
        else -> MaterialTheme.typography.bodyLarge to 4.dp
    }
    val fontWeight = if (block.level <= 3) FontWeight.Bold else FontWeight.SemiBold

    val inlineText = remember(block.text, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
        MarkdownParser.parseInline(
            text = block.text,
            primaryColor = primaryColor,
            codeBackground = codeBg,
            codeColor = codeColor,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
            searchHighlightColor = searchHighlightColor,
        )
    }

    Column(modifier = Modifier.padding(top = topPadding)) {
        Text(
            text = inlineText,
            style = style,
            fontWeight = fontWeight,
            color = colorScheme.onSurface,
        )
        if (block.level <= 2) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun BlockquoteView(
    block: MarkdownBlock.Blockquote,
    primaryColor: Color,
    codeBg: Color,
    codeColor: Color,
    searchQuery: String,
    searchCaseSensitive: Boolean,
    searchHighlightColor: Color,
) {
    val colorScheme = MaterialTheme.colorScheme
    val inlineText = remember(block.text, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
        MarkdownParser.parseInline(
            text = block.text,
            primaryColor = primaryColor,
            codeBackground = codeBg,
            codeColor = codeColor,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
            searchHighlightColor = searchHighlightColor,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(primaryColor, RoundedCornerShape(2.dp)),
        )
        Text(
            text = inlineText,
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CodeBlockView(
    block: MarkdownBlock.CodeBlock,
    colors: SyntaxColors,
    searchQuery: String,
    searchCaseSensitive: Boolean,
    onCopy: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val lang = remember(block.language) {
        if (block.language.isEmpty()) SyntaxLanguage.PlainText
        else SyntaxLanguage.entries.find { it.name.equals(block.language, ignoreCase = true) }
            ?: SyntaxLanguage.detect("snippet.${block.language}")
    }

    val highlightedCode = remember(block.code, lang, colors, searchQuery, searchCaseSensitive) {
        highlightSyntax(
            text = block.code,
            language = lang,
            colors = colors,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = colorScheme.surfaceContainerLow,
    ) {
        Column {
            // Header with language tag and copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (block.language.isNotEmpty()) block.language.uppercase() else "TEXT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                )
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy code",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Code content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = highlightedCode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.editorText,
                )
            }
        }
    }
}

@Composable
private fun TableView(
    block: MarkdownBlock.Table,
    primaryColor: Color,
    codeBg: Color,
    codeColor: Color,
    searchQuery: String,
    searchCaseSensitive: Boolean,
    searchHighlightColor: Color,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = colorScheme.surfaceContainerLow,
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Table header
                Row(
                    modifier = Modifier
                        .background(colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    for (header in block.headers) {
                        val headerInline = remember(header, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
                            MarkdownParser.parseInline(
                                text = header,
                                primaryColor = primaryColor,
                                codeBackground = codeBg,
                                codeColor = codeColor,
                                searchQuery = searchQuery,
                                searchCaseSensitive = searchCaseSensitive,
                                searchHighlightColor = searchHighlightColor,
                            )
                        }
                        Text(
                            text = headerInline,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface,
                            modifier = Modifier
                                .widthIn(min = 90.dp)
                                .padding(horizontal = 8.dp),
                        )
                    }
                }

                HorizontalDivider(color = colorScheme.outlineVariant)

                // Table rows
                for ((rowIdx, row) in block.rows.withIndex()) {
                    val rowBg = if (rowIdx % 2 == 1) colorScheme.surfaceContainer.copy(alpha = 0.35f) else Color.Transparent
                    Row(
                        modifier = Modifier
                            .background(rowBg)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        for ((colIdx, cell) in row.withIndex()) {
                            val cellInline = remember(cell, primaryColor, codeBg, codeColor, searchQuery, searchCaseSensitive) {
                                MarkdownParser.parseInline(
                                    text = cell,
                                    primaryColor = primaryColor,
                                    codeBackground = codeBg,
                                    codeColor = codeColor,
                                    searchQuery = searchQuery,
                                    searchCaseSensitive = searchCaseSensitive,
                                    searchHighlightColor = searchHighlightColor,
                                )
                            }
                            Text(
                                text = cellInline,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurface,
                                modifier = Modifier
                                    .widthIn(min = 90.dp)
                                    .padding(horizontal = 8.dp),
                            )
                        }
                    }
                    if (rowIdx < block.rows.lastIndex) {
                        HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}
