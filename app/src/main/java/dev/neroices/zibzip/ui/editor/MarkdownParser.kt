package dev.neroices.zibzip.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.util.regex.Pattern

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletItem(val level: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class TaskItem(val checked: Boolean, val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

object MarkdownParser {

    private val HR_REGEX = Regex("""^([*\-_])(\s*\1){2,}$""")
    private val TASK_REGEX = Regex("""^[-*+]\s+\[([ xX])](\s+.*)?$""")
    private val BULLET_REGEX = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED_REGEX = Regex("""^(\s*)(\d+)\.\s+(.*)$""")

    fun parse(content: String): List<MarkdownBlock> {
        if (content.isBlank()) return emptyList()
        val lines = content.lines()
        val blocks = ArrayList<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. Skip empty lines
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 2. Fenced code block (``` or ~~~)
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val lang = trimmed.removePrefix(fence).trim().lowercase()
                val codeLines = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val codeLine = lines[i]
                    if (codeLine.trim().startsWith(fence)) {
                        i++
                        break
                    }
                    codeLines.add(codeLine)
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            // 3. Headings (# Heading)
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length
                if (level in 1..6 && trimmed.length > level && trimmed[level] == ' ') {
                    val headingText = trimmed.drop(level).trim()
                    blocks.add(MarkdownBlock.Heading(level, headingText))
                    i++
                    continue
                }
            }

            // 4. Horizontal Rule (---, ***, ___)
            if (trimmed.matches(HR_REGEX)) {
                blocks.add(MarkdownBlock.Divider)
                i++
                continue
            }

            // 5. Blockquotes (> ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    val qLine = lines[i].trim().removePrefix(">").trimStart()
                    quoteLines.add(qLine)
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n")))
                continue
            }

            // 6. Task lists (- [ ] or - [x])
            val taskMatch = TASK_REGEX.matchEntire(trimmed)
            if (taskMatch != null) {
                val checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
                val taskText = taskMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.TaskItem(checked, taskText))
                i++
                continue
            }

            // 7. Bullet lists (- item, * item, + item)
            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1].length / 2
                val text = bulletMatch.groupValues[2]
                blocks.add(MarkdownBlock.BulletItem(indent, text))
                i++
                continue
            }

            // 8. Numbered lists (1. item)
            val numberedMatch = NUMBERED_REGEX.matchEntire(line)
            if (numberedMatch != null) {
                val num = numberedMatch.groupValues[2]
                val text = numberedMatch.groupValues[3]
                blocks.add(MarkdownBlock.NumberedItem(num, text))
                i++
                continue
            }

            // 9. Tables (| Header 1 | Header 2 |)
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size) {
                val nextTrimmed = lines[i + 1].trim()
                if (nextTrimmed.startsWith("|") && nextTrimmed.contains("---")) {
                    val headers = parseTableRow(trimmed)
                    i += 2 // skip header and delimiter row
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                        rows.add(parseTableRow(lines[i].trim()))
                        i++
                    }
                    blocks.add(MarkdownBlock.Table(headers, rows))
                    continue
                }
            }

            // 10. Paragraph (accumulate consecutive text lines)
            val paragraphLines = ArrayList<String>()
            while (i < lines.size) {
                val pLine = lines[i]
                val pTrimmed = pLine.trim()
                if (pTrimmed.isEmpty() ||
                    pTrimmed.startsWith("```") ||
                    pTrimmed.startsWith("~~~") ||
                    (pTrimmed.startsWith("#") && pTrimmed.takeWhile { it == '#' }.length in 1..6 && pTrimmed.dropWhile { it == '#' }.startsWith(" ")) ||
                    pTrimmed.matches(HR_REGEX) ||
                    pTrimmed.startsWith(">") ||
                    TASK_REGEX.matches(pTrimmed) ||
                    BULLET_REGEX.matches(pLine) ||
                    NUMBERED_REGEX.matches(pLine) ||
                    (pTrimmed.startsWith("|") && pTrimmed.endsWith("|") && i + 1 < lines.size && lines[i + 1].trim().contains("---"))
                ) {
                    break
                }
                paragraphLines.add(pTrimmed)
                i++
            }
            if (paragraphLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
            }
        }

        return blocks
    }

    private fun parseTableRow(row: String): List<String> {
        val parts = row.split("|")
        if (parts.size <= 2) return emptyList()
        // Drop the first and last empty elements caused by leading and trailing '|'
        return parts.subList(1, parts.size - 1).map { it.trim() }
    }

    // Pattern for inline markdown tokens
    private val INLINE_PATTERN = Pattern.compile(
        "(`[^`\\n]+`)|" + // 1: code `...`
        "(\\[[^\\]\\n]+]\\([^)\\s]+\\))|" + // 2: link [label](url)
        "(!\\[[^\\]\\n]*]\\([^)\\s]+\\))|" + // 3: image ![alt](url)
        "(\\*{3}[^*\\n]+\\*{3})|" + // 4: bold-italic ***...***
        "(\\*{2}[^*\\n]+\\*{2})|" + // 5: bold **...**
        "(_{2}[^_\\n]+_{2})|" + // 6: bold __...__
        "(\\*[^*\\n]+\\*)|" + // 7: italic *...*
        "((?<!\\w)_(?!_)[^_\\n]+_(?!\\w))|" + // 8: italic _..._ (word-boundary safe)
        "(~~[^~\\n]+~~)" // 9: strikethrough ~~...~~
    )

    fun parseInline(
        text: String,
        primaryColor: Color,
        codeBackground: Color,
        codeColor: Color,
        searchQuery: String = "",
        searchCaseSensitive: Boolean = false,
        searchHighlightColor: Color? = null,
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")

        val builder = AnnotatedString.Builder()
        val matcher = INLINE_PATTERN.matcher(text)
        var lastIndex = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            // Append preceding plain text
            if (start > lastIndex) {
                builder.append(text.substring(lastIndex, start))
            }

            val token = matcher.group()
            when {
                // Group 1: Inline code `...`
                token.startsWith("`") && token.endsWith("`") -> {
                    val codeContent = token.removeSurrounding("`")
                    val codeStart = builder.length
                    builder.append(codeContent)
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                            color = codeColor,
                        ),
                        codeStart,
                        builder.length
                    )
                }

                // Group 3: Image ![alt](url)
                token.startsWith("![") && token.endsWith(")") -> {
                    val alt = token.substringAfter("![").substringBefore("]")
                    val imgStart = builder.length
                    builder.append("🖼 $alt")
                    builder.addStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = primaryColor,
                        ),
                        imgStart,
                        builder.length
                    )
                }

                // Group 2: Link [label](url)
                token.startsWith("[") && token.endsWith(")") -> {
                    val label = token.substringAfter("[").substringBefore("]")
                    val url = token.substringAfter("](").substringBefore(")")
                    val linkStart = builder.length
                    builder.append(label)
                    builder.addLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = primaryColor,
                                    textDecoration = TextDecoration.Underline,
                                )
                            )
                        ),
                        linkStart,
                        builder.length
                    )
                }

                // Group 4: Bold-Italic ***...***
                token.startsWith("***") && token.endsWith("***") -> {
                    val content = token.removeSurrounding("***")
                    val s = builder.length
                    builder.append(content)
                    builder.addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                        s,
                        builder.length
                    )
                }

                // Group 5: Bold **...**
                token.startsWith("**") && token.endsWith("**") -> {
                    val content = token.removeSurrounding("**")
                    val s = builder.length
                    builder.append(content)
                    builder.addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        s,
                        builder.length
                    )
                }

                // Group 6: Bold __...__
                token.startsWith("__") && token.endsWith("__") -> {
                    val content = token.removeSurrounding("__")
                    val s = builder.length
                    builder.append(content)
                    builder.addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        s,
                        builder.length
                    )
                }

                // Group 7: Italic *...*
                token.startsWith("*") && token.endsWith("*") -> {
                    val content = token.removeSurrounding("*")
                    val s = builder.length
                    builder.append(content)
                    builder.addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        s,
                        builder.length
                    )
                }

                // Group 8: Italic _..._
                token.startsWith("_") && token.endsWith("_") -> {
                    val content = token.removeSurrounding("_")
                    val s = builder.length
                    builder.append(content)
                    builder.addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        s,
                        builder.length
                    )
                }

                // Group 9: Strikethrough ~~...~~
                token.startsWith("~~") && token.endsWith("~~") -> {
                    val content = token.removeSurrounding("~~")
                    val s = builder.length
                    builder.append(content)
                    builder.addStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                        s,
                        builder.length
                    )
                }

                else -> {
                    builder.append(token)
                }
            }

            lastIndex = end
        }

        // Append any remaining text
        if (lastIndex < text.length) {
            builder.append(text.substring(lastIndex))
        }

        // Apply search query highlights if active
        if (searchQuery.isNotEmpty() && searchHighlightColor != null) {
            val builtString = builder.toAnnotatedString().text
            var searchStart = 0
            while (true) {
                val idx = builtString.indexOf(searchQuery, searchStart, ignoreCase = !searchCaseSensitive)
                if (idx == -1) break
                builder.addStyle(
                    SpanStyle(background = searchHighlightColor),
                    idx,
                    idx + searchQuery.length
                )
                searchStart = idx + searchQuery.length
            }
        }

        return builder.toAnnotatedString()
    }
}
