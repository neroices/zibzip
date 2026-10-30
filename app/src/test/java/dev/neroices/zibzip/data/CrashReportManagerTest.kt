package dev.neroices.zibzip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun getPendingCrashReport_returnsNullWhenFileDoesNotExist() {
        val dir = tmp.newFolder()
        assertNull(CrashReportManager.getPendingCrashReport(dir))
    }

    @Test
    fun getPendingCrashReport_returnsContentAndClears() {
        val dir = tmp.newFolder()
        val crashFile = File(dir, "last_crash.txt")
        crashFile.writeText("sample crash report")

        val report = CrashReportManager.getPendingCrashReport(dir)
        assertEquals("sample crash report", report)

        CrashReportManager.clearPendingCrashReport(dir)
        assertFalse(crashFile.exists())
        assertNull(CrashReportManager.getPendingCrashReport(dir))
    }

    @Test
    fun buildCrashReport_containsDiagnosticsAndStackTrace() {
        val diagnostics = "--------- system\nOS : Linux"
        val exception = IllegalStateException("Test crash message")

        val report = CrashReportManager.buildCrashReport(diagnostics, exception)

        assertTrue(report.contains("--------- crash"))
        assertTrue(report.contains("FATAL EXCEPTION"))
        assertTrue(report.contains("OS : Linux"))
        assertTrue(report.contains("IllegalStateException: Test crash message"))
        assertTrue(report.contains("CrashReportManagerTest"))
    }

    @Test
    fun appLogger_recordsAndTruncatesToMaxEntries() {
        AppLogger.clear()
        repeat(120) { i ->
            AppLogger.log("Log event $i")
        }

        val logs = AppLogger.getRecentLogs()
        assertEquals(100, logs.size)
        assertTrue(logs.first().contains("Log event 20"))
        assertTrue(logs.last().contains("Log event 119"))
        AppLogger.clear()
    }

    @Test
    fun getLogFileName_matchesExpectedPattern() {
        val fileName = CrashReportManager.getLogFileName()
        assertTrue(fileName.startsWith("zibzip-log-"))
        assertTrue(fileName.endsWith(".txt"))
        val datePart = fileName.removePrefix("zibzip-log-").removeSuffix(".txt")
        assertEquals(8, datePart.length)
        assertTrue(datePart.all { it.isDigit() })
    }
}
