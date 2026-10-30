package dev.neroices.zibzip.data

import android.os.Process
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AppLogger {
    private const val MAX_ENTRIES = 100
    private val buffer = ArrayDeque<String>(MAX_ENTRIES)
    private val lock = Any()
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun log(message: String, throwable: Throwable? = null, level: Char = if (throwable != null) 'E' else 'I') {
        val timestamp = runCatching { LocalDateTime.now().format(formatter) }.getOrDefault("00-00 00:00:00.000")
        val pid = runCatching { Process.myPid() }.getOrDefault(0)
        val tid = runCatching { Process.myTid() }.getOrDefault(0)
        val pidStr = pid.toString().padStart(5)
        val tidStr = tid.toString().padStart(5)
        val prefix = "$timestamp $pidStr $tidStr $level zibzip  : "

        val entry = buildString {
            append(prefix).append(message)
            if (throwable != null) {
                append('\n')
                append(throwable.stackTraceToString().trimEnd())
            }
        }
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    fun getRecentLogs(): List<String> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}
