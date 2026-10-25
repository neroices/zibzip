package dev.neroices.zibzip.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.ui.openFile
import dev.neroices.zibzip.ui.shareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImageViewerArgs(
    val images: List<String>,
    val initialIndex: Int = 0,
)

data class ImageMetadata(
    val width: Int,
    val height: Int,
    val size: Long,
    val lastModified: Long,
)

/**
 * In-memory image loader and cache using platform ImageDecoder and BitmapFactory.
 */
object ImageLoader {
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 6).coerceIn(16 * 1024, 64 * 1024)

    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val metaCache = LruCache<String, ImageMetadata>(256)

    fun getMetadata(path: String): ImageMetadata? {
        val file = File(path)
        if (!file.exists()) return null
        val key = "$path:${file.lastModified()}"
        metaCache.get(key)?.let { return it }

        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            var w = options.outWidth
            var h = options.outHeight

            // Check EXIF rotation for swapped dimensions
            try {
                @Suppress("ExifInterface")
                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE
                ) {
                    val tmp = w
                    w = h
                    h = tmp
                }
            } catch (_: Throwable) {}

            val meta = ImageMetadata(
                width = w,
                height = h,
                size = file.length(),
                lastModified = file.lastModified(),
            )
            metaCache.put(key, meta)
            meta
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun loadBitmap(path: String, maxW: Int = 2560, maxH: Int = 2560): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return@withContext null
        val key = "$path:${file.lastModified()}"

        cache.get(key)?.let { return@withContext it }

        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sample = calculateSampleSize(info.size.width, info.size.height, maxW, maxH)
                    if (sample > 1) {
                        decoder.setTargetSampleSize(sample)
                    }
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                }
            } else {
                decodeFallback(file, maxW, maxH)
            }
        }.getOrElse {
            decodeFallback(file, maxW, maxH)
        }

        if (bitmap != null) {
            cache.put(key, bitmap)
        }
        bitmap
    }

    private fun decodeFallback(file: File, maxW: Int, maxH: Int): Bitmap? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxW, maxH)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            var bmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            try {
                @Suppress("ExifInterface")
                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees != 0f) {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    if (rotated != bmp) {
                        bmp.recycle()
                        bmp = rotated
                    }
                }
            } catch (_: Throwable) {}
            bmp
        }.getOrNull()
    }

    private fun calculateSampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        var sample = 1
        if (w > maxW || h > maxH) {
            val halfW = w / 2
            val halfH = h / 2
            while ((halfW / sample) >= maxW && (halfH / sample) >= maxH) {
                sample *= 2
            }
        }
        return sample
    }
}

/**
 * Fullscreen image viewer with zoom, pan, paging, and metadata display.
 */
@Composable
fun ImageViewerScreen(
    images: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    BackHandler(enabled = true) { onClose() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startIndex = initialIndex.coerceIn(0, images.size - 1)
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { images.size })

    var controlsVisible by remember { mutableStateOf(true) }
    var isCurrentZoomed by remember { mutableStateOf(false) }

    // Map to remember custom rotation degrees (0, 90, 180, 270) per image path
    var rotationMap by remember { mutableStateOf(mapOf<String, Float>()) }

    val currentPath = images.getOrNull(pagerState.currentPage) ?: ""
    val currentFile = remember(currentPath) { File(currentPath) }
    val currentMeta = remember(currentPath) { ImageLoader.getMetadata(currentPath) }
    val currentRotation = rotationMap[currentPath] ?: 0f

    // Preload next and previous images
    LaunchedEffect(pagerState.currentPage) {
        isCurrentZoomed = false
        val curr = pagerState.currentPage
        if (curr + 1 < images.size) {
            launch(Dispatchers.IO) { ImageLoader.loadBitmap(images[curr + 1]) }
        }
        if (curr - 1 >= 0) {
            launch(Dispatchers.IO) { ImageLoader.loadBitmap(images[curr - 1]) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C)),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isCurrentZoomed,
            modifier = Modifier.fillMaxSize(),
            key = { images[it] },
        ) { page ->
            val path = images[page]
            val rotation = rotationMap[path] ?: 0f

            ImageViewerPage(
                path = path,
                rotationDegrees = rotation,
                isCurrentPage = pagerState.currentPage == page,
                onZoomChanged = { zoomed ->
                    if (pagerState.currentPage == page) {
                        isCurrentZoomed = zoomed
                    }
                },
                onTap = { controlsVisible = !controlsVisible },
                onOpenExternal = { context.openFile(path) },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = currentFile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} of ${images.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }

                    // Share
                    IconButton(onClick = { context.shareFiles(listOf(currentPath)) }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share",
                            tint = Color.White,
                        )
                    }

                    // Open With External App
                    IconButton(onClick = { context.openFile(currentPath) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "Open with",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (currentMeta != null && currentMeta.width > 0 && currentMeta.height > 0) {
                            Text(
                                text = "${currentMeta.width} × ${currentMeta.height} • ${formatSize(currentMeta.size)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                        } else {
                            Text(
                                text = formatSize(currentFile.length()),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                        }

                        val dateStr = remember(currentMeta?.lastModified) {
                            val time = currentMeta?.lastModified ?: currentFile.lastModified()
                            if (time > 0) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
                            } else ""
                        }
                        if (dateStr.isNotEmpty()) {
                            Text(
                                text = dateStr,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f),
                            )
                        }
                    }

                    // Rotate 90 degrees button
                    FilledTonalIconButton(
                        onClick = {
                            val nextRot = (currentRotation + 90f) % 360f
                            rotationMap = rotationMap + (currentPath to nextRot)
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.RotateRight,
                            contentDescription = "Rotate 90°",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageViewerPage(
    path: String,
    rotationDegrees: Float,
    isCurrentPage: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(path) { mutableStateOf(true) }
    var isError by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        isLoading = true
        isError = false
        val b = ImageLoader.loadBitmap(path)
        if (b != null) {
            bitmap = b
            isLoading = false
        } else {
            isLoading = false
            isError = true
        }
    }

    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // When navigating away from this page, reset zoom and pan
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && scale.value > 1.05f) {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
            onZoomChanged(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(path, isError) {
                if (isError) return@pointerInput
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                onZoomChanged(false)
                                launch { scale.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
                                launch { offsetX.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                                launch { offsetY.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                            } else {
                                val targetScale = 2.8f
                                val cW = containerSize.width.toFloat()
                                val cH = containerSize.height.toFloat()
                                val b = bitmap
                                val bW = (b?.width ?: cW.toInt()).toFloat()
                                val bH = (b?.height ?: cH.toInt()).toFloat()
                                val fitScale = minOf(cW / bW, cH / bH).takeIf { it > 0f } ?: 1f
                                val fittedW = bW * fitScale
                                val fittedH = bH * fitScale
                                val maxOffX = maxOf(0f, (fittedW * targetScale - cW) / 2f)
                                val maxOffY = maxOf(0f, (fittedH * targetScale - cH) / 2f)

                                val targetOffX = ((cW / 2f) - tapOffset.x) * (targetScale - 1f)
                                val targetOffY = ((cH / 2f) - tapOffset.y) * (targetScale - 1f)

                                onZoomChanged(true)
                                launch { scale.animateTo(targetScale, tween(250, easing = FastOutSlowInEasing)) }
                                launch { offsetX.animateTo(targetOffX.coerceIn(-maxOffX, maxOffX), tween(250, easing = FastOutSlowInEasing)) }
                                launch { offsetY.animateTo(targetOffY.coerceIn(-maxOffY, maxOffY), tween(250, easing = FastOutSlowInEasing)) }
                            }
                        }
                    },
                )
            }
            .pointerInput(path, isError) {
                if (isError) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    scope.launch {
                        val currentScale = scale.value
                        val newScale = (currentScale * zoom).coerceIn(1f, 5f)
                        val cW = containerSize.width.toFloat()
                        val cH = containerSize.height.toFloat()
                        val b = bitmap
                        val bW = (b?.width ?: cW.toInt()).toFloat()
                        val bH = (b?.height ?: cH.toInt()).toFloat()
                        val fitScale = minOf(cW / bW, cH / bH).takeIf { it > 0f } ?: 1f
                        val fittedW = bW * fitScale
                        val fittedH = bH * fitScale
                        val maxOffX = maxOf(0f, (fittedW * newScale - cW) / 2f)
                        val maxOffY = maxOf(0f, (fittedH * newScale - cH) / 2f)

                        scale.snapTo(newScale)
                        if (newScale > 1.05f) {
                            onZoomChanged(true)
                            val newOffX = (offsetX.value + pan.x).coerceIn(-maxOffX, maxOffX)
                            val newOffY = (offsetY.value + pan.y).coerceIn(-maxOffY, maxOffY)
                            offsetX.snapTo(newOffX)
                            offsetY.snapTo(newOffY)
                        } else {
                            onZoomChanged(false)
                            offsetX.snapTo(0f)
                            offsetY.snapTo(0f)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(42.dp),
                )
            }

            isError || bitmap == null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        text = "Unable to preview image",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Button(onClick = onOpenExternal) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open with external app")
                    }
                }
            }

            else -> {
                val b = bitmap ?: return
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offsetX.value
                            translationY = offsetY.value
                            rotationZ = rotationDegrees
                        },
                )
            }
        }
    }
}
