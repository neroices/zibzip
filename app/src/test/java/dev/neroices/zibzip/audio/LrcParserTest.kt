package dev.neroices.zibzip.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parseStandardLrc() {
        val lrc = """
            [ti:Caramelldansen]
            [ar:Caramell]
            [00:10.50]Vi undrar, är ni redo att vara med?
            [00:13.20]Armarna upp, nu ska ni få se
            [00:32.00]Dansa med oss, klappa era händer
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(3, parsed.size)
        assertEquals(10500L, parsed[0].timestampMs)
        assertEquals("Vi undrar, är ni redo att vara med?", parsed[0].text)

        assertEquals(13200L, parsed[1].timestampMs)
        assertEquals("Armarna upp, nu ska ni få se", parsed[1].text)

        assertEquals(32000L, parsed[2].timestampMs)
        assertEquals("Dansa med oss, klappa era händer", parsed[2].text)
    }

    @Test
    fun parseMultipleTimestampsOnSingleLine() {
        val lrc = """
            [00:10.00][00:20.00]Repeated chorus
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(2, parsed.size)
        assertEquals(10000L, parsed[0].timestampMs)
        assertEquals("Repeated chorus", parsed[0].text)

        assertEquals(20000L, parsed[1].timestampMs)
        assertEquals("Repeated chorus", parsed[1].text)
    }

    @Test
    fun parseWithOffset() {
        val lrc = """
            [offset:500]
            [00:01.00]Delayed by 500ms
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(1, parsed.size)
        assertEquals(1500L, parsed[0].timestampMs)
        assertEquals("Delayed by 500ms", parsed[0].text)
    }

    @Test
    fun parseEmptyOrCorruptedContent() {
        val parsed = LrcParser.parse("")
        assertTrue(parsed.isEmpty())

        val onlyHeaders = """
            [ti:Title]
            [ar:Artist]
        """.trimIndent()
        assertTrue(LrcParser.parse(onlyHeaders).isEmpty())
    }

    @Test
    fun loadLyrics_rejectsOversizedFile() {
        val tempFolder = java.io.File(System.getProperty("java.io.tmpdir"), "lrc_test_${System.nanoTime()}").apply { mkdirs() }
        val audio = java.io.File(tempFolder, "song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oversizedLrc = java.io.File(tempFolder, "song.lrc")
        val writer = oversizedLrc.bufferedWriter()
        val chunk = "[00:01.00] Line text padding\n".repeat(20_000)
        writer.write(chunk)
        writer.flush()
        writer.close()

        val result = LrcParser.findAndParseForAudio(audio.absolutePath)
        assertTrue(result.isEmpty())
        tempFolder.deleteRecursively()
    }

    @Test
    fun findAndParseForAudio_supportsCaseInsensitiveMatching() {
        val tempFolder = java.io.File(System.getProperty("java.io.tmpdir"), "lrc_test_case_${System.nanoTime()}").apply { mkdirs() }
        val audio = java.io.File(tempFolder, "track.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val lrc = java.io.File(tempFolder, "TRACK.LRC").apply {
            writeText("[00:05.00]Case insensitive lyrics\n")
        }

        val result = LrcParser.findAndParseForAudio(audio.absolutePath)
        assertEquals(1, result.size)
        assertEquals(5000L, result[0].timestampMs)
        assertEquals("Case insensitive lyrics", result[0].text)

        tempFolder.deleteRecursively()
    }
}
