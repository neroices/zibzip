package dev.neroices.zibzip.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import dev.neroices.zibzip.domain.StorageVolumeInfo
import java.io.File

/** Storage permission state and the list of mounted volumes for modern Android (API 30+). */
class StorageProvider(private val context: Context) {

    /** Modern Android uses "All files access" (`MANAGE_EXTERNAL_STORAGE`). */
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    val primaryRoot: File get() = Environment.getExternalStorageDirectory()

    /** Internal storage first, then any mounted removable volume. */
    fun volumes(withStats: Boolean = true): List<StorageVolumeInfo> {
        val result = LinkedHashMap<String, StorageVolumeInfo>()
        val primary = primaryRoot
        result[primary.path] = info("Internal storage", primary, false, withStats)

        val sm = context.getSystemService(StorageManager::class.java)
        for (v in sm?.storageVolumes.orEmpty()) {
            val dir = v.directory ?: continue
            if (v.isPrimary) continue
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            result.putIfAbsent(dir.path, info(v.getDescription(context) ?: "SD card", dir, v.isRemovable, withStats))
        }
        return result.values.toList()
    }

    private fun info(label: String, dir: File, removable: Boolean, withStats: Boolean) = StorageVolumeInfo(
        label = label,
        path = dir.path,
        isRemovable = removable,
        totalBytes = if (withStats) dir.totalSpace else 0L,
        freeBytes = if (withStats) dir.usableSpace else 0L,
    )
}
