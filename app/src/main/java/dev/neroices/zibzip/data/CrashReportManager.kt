package dev.neroices.zibzip.data

import android.content.Context
import android.os.Build
import dev.neroices.zibzip.BuildConfig
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object CrashReportManager {
    private const val CRASH_FILE_NAME = "last_crash.txt"

    fun getLogFileName(): String {
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        return "zibzip-log-$date.txt"
    }

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildCrashReport(throwable)
                File(context.filesDir, CRASH_FILE_NAME).writeText(report)
            } catch (_: Throwable) {
                // Ensure handler failures never block process termination.
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun getPendingCrashReport(context: Context): String? = getPendingCrashReport(context.filesDir)

    fun getPendingCrashReport(filesDir: File): String? {
        val file = File(filesDir, CRASH_FILE_NAME)
        return if (file.exists() && file.length() > 0) {
            runCatching { file.readText() }.getOrNull()
        } else {
            null
        }
    }

    fun clearPendingCrashReport(context: Context) = clearPendingCrashReport(context.filesDir)

    fun clearPendingCrashReport(filesDir: File) {
        runCatching { File(filesDir, CRASH_FILE_NAME).delete() }
    }

    fun buildDiagnostics(): String {
        val buildType = if (BuildConfig.DEBUG) "debug" else "release"
        val timestamp = Instant.now().toString()
        return """
            --------- system
            Application: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) [$buildType]
            Android OS : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Device     : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})
            ABIs       : ${Build.SUPPORTED_ABIS.joinToString(", ")}
            Timestamp  : $timestamp
        """.trimIndent()
    }

    fun buildCrashReport(throwable: Throwable): String {
        return buildCrashReport(buildDiagnostics(), throwable)
    }

    fun buildCrashReport(diagnostics: String, throwable: Throwable): String {
        val stackTrace = throwable.stackTraceToString().trimEnd()
        val recentLogs = AppLogger.getRecentLogs()
        val eventsSection = if (recentLogs.isEmpty()) {
            ""
        } else {
            "\n--------- events\n" + recentLogs.joinToString("\n")
        }
        return """
            $diagnostics
            --------- crash
            FATAL EXCEPTION: ${Thread.currentThread().name}
            Process: ${BuildConfig.APPLICATION_ID}
            $stackTrace$eventsSection
        """.trimIndent()
    }

    fun buildFullDiagnosticReport(): String {
        val diagnostics = buildDiagnostics()
        val recentLogs = AppLogger.getRecentLogs()
        return buildString {
            append(diagnostics)
            append("\n--------- events\n")
            if (recentLogs.isEmpty()) {
                append("(no recent events recorded)\n")
            } else {
                recentLogs.forEach { appendLine(it) }
            }
        }
    }
}
