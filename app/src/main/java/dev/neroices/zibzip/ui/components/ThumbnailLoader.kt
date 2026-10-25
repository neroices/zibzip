package dev.neroices.zibzip.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.neroices.zibzip.domain.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ThumbnailLoader {

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(16 * 1024, 64 * 1024)

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val noThumbnailCache = object : LruCache<String, Boolean>(2048) {}

    fun get(path: String, lastModified: Long): Bitmap? {
        return cache.get("$path:$lastModified")
    }

    fun hasNoThumbnail(path: String, lastModified: Long): Boolean {
        return noThumbnailCache.get("$path:$lastModified") == true
    }

    fun put(path: String, lastModified: Long, bitmap: Bitmap) {
        cache.put("$path:$lastModified", bitmap)
    }

    suspend fun loadThumbnail(context: Context, path: String, kind: FileKind, lastModified: Long): Bitmap? {
        val key = "$path:$lastModified"
        cache.get(key)?.let { return it }
        if (noThumbnailCache.get(key) == true) return null

        return withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return@withContext null

            val bitmap: Bitmap? = when (kind) {
                FileKind.Image -> decodeImageThumbnail(file, 96, 96)
                FileKind.Video -> decodeVideoThumbnail(file, 96, 96)
                FileKind.Apk -> decodeApkIcon(context, file)
                FileKind.Audio -> decodeAudioThumbnail(file, 96, 96)
                else -> null
            }

            if (bitmap != null) {
                cache.put(key, bitmap)
            } else {
                noThumbnailCache.put(key, true)
            }
            bitmap
        }
    }

    private fun decodeImageThumbnail(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, Size(targetW, targetH), null)
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.inSampleSize = calculateInSampleSize(opts, targetW, targetH)
                opts.inJustDecodeBounds = false
                opts.inPreferredConfig = Bitmap.Config.RGB_565
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeVideoThumbnail(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, Size(targetW, targetH), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(
                    file.absolutePath,
                    android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeApkIcon(context: Context, file: File): Bitmap? {
        return try {
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            val appInfo = pi.applicationInfo ?: return null
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            val iconDrawable = appInfo.loadIcon(pm) ?: return null
            drawableToBitmap(iconDrawable, 96, 96)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeAudioThumbnail(file: File, targetW: Int, targetH: Int): Bitmap? {
        try {
            return ThumbnailUtils.createAudioThumbnail(file, Size(targetW, targetH), null)
        } catch (_: Throwable) {}
        return decodeAudioEmbeddedPicture(file, targetW, targetH)
    }

    private fun decodeAudioEmbeddedPicture(file: File, targetW: Int, targetH: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val picBytes = retriever.embeddedPicture ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
            opts.inSampleSize = calculateInSampleSize(opts, targetW, targetH)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val b = drawable.bitmap
            if (b.width == width && b.height == height) return b
            return Bitmap.createScaledBitmap(b, width, height, true)
        }
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}

/**
 * Remembers a lightweight asynchronous thumbnail for a file row.
 */
@Composable
fun rememberThumbnail(path: String?, kind: FileKind, lastModified: Long): State<Bitmap?> {
    val context = LocalContext.current
    val state = remember(path, lastModified) {
        mutableStateOf(path?.let { ThumbnailLoader.get(it, lastModified) })
    }

    if (path != null && (kind == FileKind.Image || kind == FileKind.Video || kind == FileKind.Apk || kind == FileKind.Audio)) {
        LaunchedEffect(path, lastModified) {
            if (state.value == null && !ThumbnailLoader.hasNoThumbnail(path, lastModified)) {
                state.value = ThumbnailLoader.loadThumbnail(context, path, kind, lastModified)
            }
        }
    }

    return state
}
