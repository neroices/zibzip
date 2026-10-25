package dev.neroices.zibzip.audio

import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
)

object LrcParser {

    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    fun parse(content: String): List<LyricLine> {
        val lines = content.lines()
        val result = ArrayList<LyricLine>()
        var globalOffsetMs = 0L

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[offset:", ignoreCase = true)) {
                val offsetVal = line.substringAfter(':').substringBefore(']').trim().toLongOrNull()
                if (offsetVal != null) {
                    globalOffsetMs = offsetVal
                }
                continue
            }

            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) continue

            val lyricText = line.replace(timeTagRegex, "").trim()

            for (match in matches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fracStr.length) {
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    3 -> fracStr.toLong()
                    else -> 0L
                }
                val totalMs = (min * 60_000L + sec * 1_000L + millis + globalOffsetMs).coerceAtLeast(0L)
                result.add(LyricLine(timestampMs = totalMs, text = lyricText))
            }
        }

        result.sortBy { it.timestampMs }
        return result
    }

    fun findAndParseForAudio(audioPath: String): List<LyricLine> {
        return try {
            val audioFile = File(audioPath)
            val parent = audioFile.parentFile ?: return emptyList()
            val baseName = audioFile.nameWithoutExtension

            val directFile = File(parent, "$baseName.lrc")
            val lrcFile = if (directFile.exists() && directFile.isFile) {
                directFile
            } else {
                parent.listFiles()?.firstOrNull { file ->
                    file.isFile && file.name.equals("$baseName.lrc", ignoreCase = true)
                }
            } ?: return emptyList()

            if (!lrcFile.canRead()) return emptyList()
            val content = readTextSafely(lrcFile)
            parse(content)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readTextSafely(file: File): String {
        return try {
            val bytes = file.readBytes()
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            try { file.readText() } catch (_: Throwable) { "" }
        }
    }
}
