package dev.neroices.zibzip.domain

import java.util.Locale

/** What to do when an extracted file already exists in the destination. */
enum class ConflictPolicy { KeepBoth, Overwrite, Skip }

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = if (value >= 100 || unit == 0 && value >= 10) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unit])
}

/** Human readable message for any failure surfaced to the UI. */
fun Throwable.userMessage(): String = when (this) {
    is ZibzipException.InsufficientSpace ->
        if (needed > 0) "Not enough free space (need ${formatSize(needed)}, ${formatSize(available)} available)"
        else "Not enough free space"
    is ZibzipException -> message ?: "Operation failed"
    is SecurityException -> "Permission denied"
    else -> message?.takeIf { it.isNotBlank() } ?: (javaClass.simpleName.ifBlank { "Unexpected error" })
}

fun itemsLabel(count: Int): String = if (count == 1) "1 item" else "$count items"
