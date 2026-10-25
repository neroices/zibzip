package dev.neroices.zibzip.ui.editor

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import dev.neroices.zibzip.domain.FileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    @Test
    fun testLanguageDetection() {
        assertEquals(SyntaxLanguage.Kotlin, SyntaxLanguage.detect("MainActivity.kt"))
        assertEquals(SyntaxLanguage.Kotlin, SyntaxLanguage.detect("build.gradle.kts"))
        assertEquals(SyntaxLanguage.Java, SyntaxLanguage.detect("App.java"))
        assertEquals(SyntaxLanguage.Python, SyntaxLanguage.detect("script.py"))
        assertEquals(SyntaxLanguage.Rust, SyntaxLanguage.detect("main.rs"))
        assertEquals(SyntaxLanguage.JavaScript, SyntaxLanguage.detect("index.js"))
        assertEquals(SyntaxLanguage.TypeScript, SyntaxLanguage.detect("types.ts"))
        assertEquals(SyntaxLanguage.Html, SyntaxLanguage.detect("index.html"))
        assertEquals(SyntaxLanguage.Xml, SyntaxLanguage.detect("AndroidManifest.xml"))
        assertEquals(SyntaxLanguage.Json, SyntaxLanguage.detect("package.json"))
        assertEquals(SyntaxLanguage.Yaml, SyntaxLanguage.detect("docker-compose.yml"))
        assertEquals(SyntaxLanguage.Toml, SyntaxLanguage.detect("Cargo.toml"))
        assertEquals(SyntaxLanguage.Markdown, SyntaxLanguage.detect("README.md"))
        assertEquals(SyntaxLanguage.Shell, SyntaxLanguage.detect("build.sh"))
        assertEquals(SyntaxLanguage.Shell, SyntaxLanguage.detect("Dockerfile"))
        assertEquals(SyntaxLanguage.Properties, SyntaxLanguage.detect(".gitignore"))
        assertEquals(SyntaxLanguage.Properties, SyntaxLanguage.detect(".env"))
        assertEquals(SyntaxLanguage.Lrc, SyntaxLanguage.detect("Caramelldansen.lrc"))
        assertEquals(SyntaxLanguage.PlainText, SyntaxLanguage.detect("notes.txt"))
        assertEquals(SyntaxLanguage.PlainText, SyntaxLanguage.detect("unknown.xyz"))
    }

    @Test
    fun testFileTypesIsTextFile() {
        assertTrue(FileTypes.isTextFile("file.txt"))
        assertTrue(FileTypes.isTextFile("readme.md"))
        assertTrue(FileTypes.isTextFile("data.json"))
        assertTrue(FileTypes.isTextFile("main.kt"))
        assertTrue(FileTypes.isTextFile("script.py"))
        assertTrue(FileTypes.isTextFile("Dockerfile"))
        assertTrue(FileTypes.isTextFile(".gitignore"))
        assertTrue(FileTypes.isTextFile(".env"))
        assertTrue(FileTypes.isTextFile("CMakeLists.txt"))
        assertTrue(FileTypes.isTextFile("Caramelldansen.lrc"))
        assertTrue(FileTypes.isTextFile("lyrics.LRC"))
        assertTrue(FileTypes.isTextFile("console-ramoops-0"))
        assertTrue(FileTypes.isTextFile("console-ramoops-1"))
        assertTrue(FileTypes.isTextFile("pmsg-ramoops-0"))
        assertTrue(FileTypes.isTextFile("dmesg-ramoops-0"))
        assertTrue(FileTypes.isTextFile("last_kmsg"))
        assertTrue(FileTypes.isTextFile("dmesg"))
        assertTrue(FileTypes.isTextFile("hosts"))

        // Binary files should return false
        assertFalse(FileTypes.isTextFile("photo.jpg"))
        assertFalse(FileTypes.isTextFile("movie.mp4"))
        assertFalse(FileTypes.isTextFile("archive.zip"))
        assertFalse(FileTypes.isTextFile("document.pdf"))
        assertFalse(FileTypes.isTextFile("app.apk"))
    }

    @Test
    fun testHighlightSyntaxKeywordsAndStrings() {
        val colorScheme = darkColorScheme()
        val colors = createSyntaxColors(isDark = true, colorScheme = colorScheme)

        val kotlinCode = """
            package test
            // this is a line comment
            val message = "Hello world"
            val count = 42
        """.trimIndent()

        val highlighted = highlightSyntax(
            text = kotlinCode,
            language = SyntaxLanguage.Kotlin,
            colors = colors,
        )

        val spans = highlighted.spanStyles
        assertTrue("Expected non-empty span styles for highlighted tokens", spans.isNotEmpty())

        // Verify comment is colored
        val commentSpan = spans.find { it.item.color == colors.comment }
        assertNotNull("Expected comment style span", commentSpan)

        // Verify string is colored
        val stringSpan = spans.find { it.item.color == colors.string }
        assertNotNull("Expected string style span", stringSpan)

        // Verify keyword is colored
        val keywordSpan = spans.find { it.item.color == colors.keyword }
        assertNotNull("Expected keyword style span", keywordSpan)

        // Verify number is colored
        val numberSpan = spans.find { it.item.color == colors.number }
        assertNotNull("Expected number style span", numberSpan)
    }

    @Test
    fun testSearchHighlighting() {
        val colorScheme = darkColorScheme()
        val colors = createSyntaxColors(isDark = true, colorScheme = colorScheme)

        val text = "Apple Banana Apple Orange"
        val highlighted = highlightSyntax(
            text = text,
            language = SyntaxLanguage.PlainText,
            colors = colors,
            searchQuery = "Apple",
            searchCaseSensitive = false,
            activeMatchIndex = 1,
        )

        val spans = highlighted.spanStyles
        val searchSpans = spans.filter { it.item.background == colors.searchMatch }
        val activeSpans = spans.filter { it.item.background == colors.activeSearchMatch }

        assertEquals("Expected 1 inactive search match", 1, searchSpans.size)
        assertEquals("Expected 1 active search match", 1, activeSpans.size)
    }
}
