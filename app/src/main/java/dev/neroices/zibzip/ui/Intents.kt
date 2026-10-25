package dev.neroices.zibzip.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.neroices.zibzip.domain.FileTypes
import dev.neroices.zibzip.zibzipApp
import java.io.File

private fun Context.uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

private fun mimeOf(file: File): String {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    if (ext == "apk") return "application/vnd.android.package-archive"
    val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (systemMime != null) return systemMime
    if (FileTypes.isTextFile(file)) return "text/plain"
    return "*/*"
}

private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "apk") return "application/vnd.android.package-archive"
    val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (systemMime != null) return systemMime
    if (FileTypes.isTextFile(name)) return "text/plain"
    return "*/*"
}

/** Directly prompts Android Package Installer to install [path]. */
fun Context.installApk(path: String) {
    val file = File(path)
    if (!file.exists()) {
        zibzipApp.toast("APK file no longer exists")
        return
    }
    try {
        val uri = uriFor(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        zibzipApp.toast("No package installer found on this device")
    } catch (e: SecurityException) {
        // If unknown sources permission is blocked
        try {
            val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(manageIntent)
            zibzipApp.toast("Please allow installing apps from zibzip")
        } catch (_: Exception) {
            zibzipApp.toast("Permission required to install APKs")
        }
    } catch (e: Exception) {
        zibzipApp.toast("Unable to open installer: ${e.message}")
    }
}

/** Opens [path] in whichever app the user has chosen for that type. */
fun Context.openFile(path: String) {
    val file = File(path)
    if (!file.exists()) {
        zibzipApp.toast("File no longer exists")
        return
    }
    if (file.extension.equals("apk", ignoreCase = true)) {
        installApk(path)
        return
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        zibzipApp.toast("No app found to open this file")
    } catch (e: IllegalArgumentException) {
        zibzipApp.toast("This file can't be shared with other apps")
    }
}

fun Context.shareFiles(paths: List<String>) {
    val files = paths.map(::File).filter { it.isFile }
    if (files.isEmpty()) return
    try {
        val uris = ArrayList(files.map { uriFor(it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(mimeOf(files[0]))
                .putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        zibzipApp.toast("No app available to share with")
    } catch (e: IllegalArgumentException) {
        zibzipApp.toast("These files can't be shared")
    }
}

/** Opens the system screen where the user grants "All files access" to this app. */
fun Context.openAllFilesAccessSettings() {
    val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(appIntent)
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
