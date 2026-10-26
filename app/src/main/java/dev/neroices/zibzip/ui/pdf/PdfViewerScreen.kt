package dev.neroices.zibzip.ui.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.ui.openFile
import dev.neroices.zibzip.ui.shareFiles
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class PdfViewerArgs(
    val path: String,
)

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Success(val pageCount: Int, val dimensions: List<Pair<Int, Int>>) : PdfLoadState
    data class PasswordProtected(val message: String) : PdfLoadState
    data class Error(val message: String) : PdfLoadState
}

private val InvertNightColorMatrix = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f, -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f,
        )
    )
)

@Composable
fun PdfViewerScreen(
    path: String,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }

    var loadState by remember { mutableStateOf<PdfLoadState>(PdfLoadState.Loading) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }

    val pdfDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }

    val bitmapCache = remember {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb / 6).coerceIn(16 * 1024, 64 * 1024)
        object : LruCache<Int, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

    LaunchedEffect(path) {
        loadState = PdfLoadState.Loading
        withContext(pdfDispatcher) {
            try {
                if (!file.exists() || !file.canRead()) {
                    loadState = PdfLoadState.Error("File does not exist or cannot be read.")
                    return@withContext
                }

                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(descriptor)
                pfd = descriptor
                pdfRenderer = renderer

                val count = renderer.pageCount
                if (count == 0) {
                    loadState = PdfLoadState.Error("This PDF document contains no pages.")
                    return@withContext
                }

                val firstPage = renderer.openPage(0)
                val defaultDim = Pair(firstPage.width, firstPage.height)
                firstPage.close()

                val dims = if (count <= 30) {
                    List(count) { i ->
                        if (i == 0) {
                            defaultDim
                        } else {
                            val page = renderer.openPage(i)
                            val pair = Pair(page.width, page.height)
                            page.close()
                            pair
                        }
                    }
                } else {
                    List(count) { defaultDim }
                }

                loadState = PdfLoadState.Success(count, dims)
            } catch (e: SecurityException) {
                loadState = PdfLoadState.PasswordProtected(
                    "This PDF is password protected. You can open it in an external PDF app with password support."
                )
            } catch (e: Exception) {
                loadState = PdfLoadState.Error(e.message ?: "Failed to open PDF document.")
            }
        }
    }

    DisposableEffect(path) {
        onDispose {
            val renderer = pdfRenderer
            val descriptor = pfd
            context.zibzipApp.appScope.launch(pdfDispatcher) {
                try {
                    renderer?.close()
                    descriptor?.close()
                    bitmapCache.evictAll()
                } catch (_: Throwable) {}
            }
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(pdfDispatcher) {
        coroutineContext.ensureActive()
        bitmapCache.get(pageIndex)?.let { return@withContext it }
        val renderer = pdfRenderer ?: return@withContext null

        try {
            val page = renderer.openPage(pageIndex)
            try {
                val pw = page.width
                val ph = page.height
                val targetHeight = ((targetWidthPx.toFloat() / pw) * ph).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(pageIndex, bitmap)
                bitmap
            } finally {
                page.close()
            }
        } catch (e: Throwable) {
            android.util.Log.e("PdfViewer", "Failed to render page $pageIndex", e)
            null
        }
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var nightMode by rememberSaveable { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(lazyListState.firstVisibleItemIndex, containerSize.width, loadState) {
        val s = loadState as? PdfLoadState.Success ?: return@LaunchedEffect
        val widthPx = containerSize.width.coerceIn(720, 1440)
        val currentIndex = lazyListState.firstVisibleItemIndex
        val pagesToPreload = listOf(
            currentIndex,
            currentIndex + 1,
            currentIndex + 2,
            currentIndex - 1,
        ).filter { it in 0 until s.pageCount }

        for (p in pagesToPreload) {
            if (bitmapCache.get(p) == null) {
                launch(Dispatchers.IO) {
                    renderPage(p, widthPx)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12)),
    ) {
        when (val state = loadState) {
            is PdfLoadState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        text = "Loading PDF…",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is PdfLoadState.PasswordProtected -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    Text(
                        text = "Password Protected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )

                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(onClick = { context.openFile(path) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open in external viewer")
                    }
                }
            }

            is PdfLoadState.Error -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    Text(
                        text = "Unable to open PDF",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )

                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(onClick = { context.openFile(path) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open in external app")
                    }
                }
            }

            is PdfLoadState.Success -> {
                val isZoomed by remember { derivedStateOf { scale.value > 1.05f } }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .onSizeChanged { containerSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = { tapOffset ->
                                    scope.launch {
                                        val currentlyZoomed = scale.value > 1.15f
                                        val targetScale = if (currentlyZoomed) 1f else 2.5f
                                        val cW = if (containerSize.width > 0) containerSize.width.toFloat() else 1080f
                                        val cH = if (containerSize.height > 0) containerSize.height.toFloat() else 1920f
                                        val maxOffX = ((cW * targetScale - cW) / 2f).coerceAtLeast(0f)
                                        val maxOffY = ((cH * targetScale - cH) / 2f).coerceAtLeast(0f)
                                        val targetOffX = if (currentlyZoomed) {
                                            0f
                                        } else {
                                            (((cW / 2f) - tapOffset.x) * (targetScale - 1f)).coerceIn(-maxOffX, maxOffX)
                                        }
                                        val targetOffY = if (currentlyZoomed) {
                                            0f
                                        } else {
                                            (((cH / 2f) - tapOffset.y) * (targetScale - 1f)).coerceIn(-maxOffY, maxOffY)
                                        }

                                        launch { scale.animateTo(targetScale, tween(260, easing = FastOutSlowInEasing)) }
                                        launch { offsetX.animateTo(targetOffX, tween(260, easing = FastOutSlowInEasing)) }
                                        launch { offsetY.animateTo(targetOffY, tween(260, easing = FastOutSlowInEasing)) }
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectZoomPanGestures(
                                isZoomed = { scale.value > 1.05f },
                                onGesture = { pan, zoom ->
                                    val currentScale = scale.value
                                    val newScale = (currentScale * zoom).coerceIn(1f, 4f)
                                    val cW = if (containerSize.width > 0) containerSize.width.toFloat() else 1080f
                                    val cH = if (containerSize.height > 0) containerSize.height.toFloat() else 1920f
                                    val maxOffX = ((cW * newScale - cW) / 2f).coerceAtLeast(0f)
                                    val maxOffY = ((cH * newScale - cH) / 2f).coerceAtLeast(0f)

                                    val newOffX = if (newScale > 1.05f) (offsetX.value + pan.x).coerceIn(-maxOffX, maxOffX) else 0f
                                    val newOffY = if (newScale > 1.05f) (offsetY.value + pan.y).coerceIn(-maxOffY, maxOffY) else 0f

                                    scope.launch {
                                        scale.snapTo(newScale)
                                        offsetX.snapTo(newOffX)
                                        offsetY.snapTo(newOffY)
                                    }
                                },
                                onGestureEnd = {
                                    if (scale.value < 1.08f && scale.value != 1f) {
                                        scope.launch {
                                            launch { scale.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
                                            launch { offsetX.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
                                            launch { offsetY.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
                                        }
                                    }
                                },
                            )
                        },
                ) {
                    val density = LocalDensity.current
                    val containerWidthPx = with(density) { maxWidth.roundToPx() }
                    val renderWidthPx = containerWidthPx.coerceIn(720, 1440)

                    LazyColumn(
                        state = lazyListState,
                        userScrollEnabled = !isZoomed,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                                translationX = offsetX.value
                                translationY = offsetY.value
                            },
                        contentPadding = PaddingValues(
                            top = 76.dp,
                            bottom = 92.dp,
                            start = 12.dp,
                            end = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(
                            count = state.pageCount,
                            key = { it },
                        ) { pageIndex ->
                            val dims = state.dimensions.getOrElse(pageIndex) { Pair(612, 792) }
                            val aspectRatio = dims.first.toFloat() / dims.second.toFloat()

                            PdfPageItem(
                                pageIndex = pageIndex,
                                aspectRatio = aspectRatio,
                                targetWidthPx = renderWidthPx,
                                nightMode = nightMode,
                                cachedBitmap = bitmapCache.get(pageIndex),
                                onRender = { idx, targetW -> renderPage(idx, targetW) },
                            )
                        }
                    }
                }

                PageIndicatorPill(
                    lazyListState = lazyListState,
                    pageCount = state.pageCount,
                    visible = controlsVisible,
                    onClick = { showJumpDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                )

                if (showJumpDialog) {
                    val initialPage = (lazyListState.firstVisibleItemIndex + 1).coerceIn(1, state.pageCount)
                    JumpToPageDialog(
                        pageCount = state.pageCount,
                        currentPage = initialPage,
                        onDismiss = { showJumpDialog = false },
                        onJump = { targetPage ->
                            showJumpDialog = false
                            scope.launch {
                                if (scale.value > 1.05f) {
                                    launch { scale.animateTo(1f, tween(200)) }
                                    launch { offsetX.animateTo(0f, tween(200)) }
                                    launch { offsetY.animateTo(0f, tween(200)) }
                                }
                                lazyListState.scrollToItem((targetPage - 1).coerceIn(0, state.pageCount - 1))
                            }
                        },
                    )
                }
            }
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
                            listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
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
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when (val s = loadState) {
                            is PdfLoadState.Success -> "${s.pageCount} pages · ${formatSize(file.length())}"
                            else -> formatSize(file.length())
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }

                    IconButton(onClick = { nightMode = !nightMode }) {
                        Icon(
                            imageVector = if (nightMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                            contentDescription = "Toggle Night Mode",
                            tint = Color.White,
                        )
                    }

                    if (loadState is PdfLoadState.Success) {
                        IconButton(onClick = { showJumpDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.FormatListNumbered,
                                contentDescription = "Jump to page",
                                tint = Color.White,
                            )
                        }
                    }

                    IconButton(onClick = { context.shareFiles(listOf(path)) }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share",
                            tint = Color.White,
                        )
                    }

                    IconButton(onClick = { context.openFile(path) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "Open in external app",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun PointerInputScope.detectZoomPanGestures(
    isZoomed: () -> Boolean,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: () -> Unit = {},
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val pointerCount = event.changes.size
                if (!isZoomed() && pointerCount < 2) {
                    continue
                }

                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange

                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val panMotion = pan.getDistance()

                    if (zoomMotion > touchSlop || panMotion > touchSlop || isZoomed()) {
                        pastTouchSlop = true
                    }
                }

                if (pastTouchSlop) {
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(panChange, zoomChange)
                    }
                    event.changes.forEach {
                        if (it.positionChanged()) {
                            it.consume()
                        }
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })

        if (pastTouchSlop) {
            onGestureEnd()
        }
    }
}

@Composable
private fun PdfPageItem(
    pageIndex: Int,
    aspectRatio: Float,
    targetWidthPx: Int,
    nightMode: Boolean,
    cachedBitmap: Bitmap?,
    onRender: suspend (Int, Int) -> Bitmap?,
) {
    var pageBitmap by remember(pageIndex) { mutableStateOf(cachedBitmap) }
    var isLoading by remember(pageIndex) { mutableStateOf(cachedBitmap == null) }

    LaunchedEffect(cachedBitmap) {
        if (cachedBitmap != null && pageBitmap == null) {
            pageBitmap = cachedBitmap
            isLoading = false
        }
    }

    LaunchedEffect(pageIndex, targetWidthPx) {
        if (pageBitmap == null) {
            isLoading = true
            val bmp = onRender(pageIndex, targetWidthPx)
            pageBitmap = bmp
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(if (nightMode) Color(0xFF1E1E24) else Color.White)
            .aspectRatio(aspectRatio),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = pageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.FillWidth,
                colorFilter = if (nightMode) InvertNightColorMatrix else null,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun PageIndicatorPill(
    lazyListState: LazyListState,
    pageCount: Int,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPage by remember {
        derivedStateOf {
            (lazyListState.firstVisibleItemIndex + 1).coerceIn(1, pageCount)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.78f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            modifier = Modifier
                .shadow(8.dp, CircleShape)
                .clickable(onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Page $currentPage of $pageCount",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun JumpToPageDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var textValue by remember { mutableStateOf(currentPage.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Page") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        textValue = digits
                        digits.toIntOrNull()?.let {
                            sliderValue = it.coerceIn(1, pageCount).toFloat()
                        }
                    },
                    label = { Text("Page (1 - $pageCount)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (pageCount > 1) {
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it
                            textValue = it.toInt().toString()
                        },
                        valueRange = 1f..pageCount.toFloat(),
                        steps = (pageCount - 2).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val page = textValue.toIntOrNull() ?: sliderValue.toInt()
                    onJump(page.coerceIn(1, pageCount))
                },
            ) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
