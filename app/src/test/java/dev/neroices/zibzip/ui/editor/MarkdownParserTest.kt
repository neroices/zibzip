package dev.neroices.zibzip.ui.editor

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun parse_headings() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
            ###### Heading 6
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "Heading 1"), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "Heading 2"), blocks[1])
        assertEquals(MarkdownBlock.Heading(3, "Heading 3"), blocks[2])
        assertEquals(MarkdownBlock.Heading(6, "Heading 6"), blocks[3])
    }

    @Test
    fun parse_code_blocks() {
        val markdown = """
            ```kotlin
            val x = 10
            println(x)
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        val codeBlock = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val x = 10\nprintln(x)", codeBlock.code)
    }

    @Test
    fun parse_blockquotes_and_dividers() {
        val markdown = """
            > This is a quote
            > second line of quote
            
            ---
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(2, blocks.size)
        val quote = blocks[0] as MarkdownBlock.Blockquote
        assertEquals("This is a quote\nsecond line of quote", quote.text)
        assertEquals(MarkdownBlock.Divider, blocks[1])
    }

    @Test
    fun parse_lists_and_tasks() {
        val markdown = """
            - Bullet 1
            - Bullet 2
            1. Numbered 1
            2. Numbered 2
            - [ ] Task pending
            - [x] Task done
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.BulletItem)
        assertEquals("Bullet 1", (blocks[0] as MarkdownBlock.BulletItem).text)
        assertTrue(blocks[2] is MarkdownBlock.NumberedItem)
        assertEquals("1", (blocks[2] as MarkdownBlock.NumberedItem).number)
        assertTrue(blocks[4] is MarkdownBlock.TaskItem)
        assertEquals(false, (blocks[4] as MarkdownBlock.TaskItem).checked)
        assertEquals("Task pending", (blocks[4] as MarkdownBlock.TaskItem).text)
        assertTrue(blocks[5] is MarkdownBlock.TaskItem)
        assertEquals(true, (blocks[5] as MarkdownBlock.TaskItem).checked)
        assertEquals("Task done", (blocks[5] as MarkdownBlock.TaskItem).text)
    }

    @Test
    fun parse_tables() {
        val markdown = """
            | Item | Quantity | Price |
            | :--- | :------: | ----: |
            | Apple | 10 | $1.00 |
            | Orange | 5 | $2.50 |
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(listOf("Item", "Quantity", "Price"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Apple", "10", "$1.00"), table.rows[0])
        assertEquals(listOf("Orange", "5", "$2.50"), table.rows[1])
    }

    @Test
    fun parse_inline_markdown() {
        val text = "This is **bold**, *italic*, `code`, and [Link](https://example.com) with ~~strikethrough~~."
        val annotated = MarkdownParser.parseInline(
            text = text,
            primaryColor = Color.Blue,
            codeBackground = Color.LightGray,
            codeColor = Color.Black,
        )

        val rawResult = annotated.text
        assertEquals("This is bold, italic, code, and Link with strikethrough.", rawResult)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
