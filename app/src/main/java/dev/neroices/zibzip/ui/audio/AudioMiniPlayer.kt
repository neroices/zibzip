package dev.neroices.zibzip.ui.audio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.audio.AudioPlaybackState
import dev.neroices.zibzip.audio.AudioPlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AudioMiniPlayer(
    controller: AudioPlayerController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var lastNonNullState by remember { mutableStateOf<AudioPlaybackState?>(null) }
    if (state != null) {
        lastNonNullState = state
    }

    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) + fadeIn(animationSpec = tween(150)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing),
        ) + fadeOut(animationSpec = tween(150)),
        modifier = modifier,
    ) {
        val playbackState = state ?: lastNonNullState
        if (playbackState != null) {
            AudioMiniPlayerContent(
                state = playbackState,
                controller = controller,
            )
        }
    }
}

@Composable
private fun AudioMiniPlayerContent(
    state: AudioPlaybackState,
    controller: AudioPlayerController,
) {
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val offsetY = remember { Animatable(0f) }
    var dismissJob by remember { mutableStateOf<Job?>(null) }
    val dismissThresholdPx = with(density) { 60.dp.toPx() }
    val dismissTargetPx = with(density) { 130.dp.toPx() }

    LaunchedEffect(state.sessionId) {
        dismissJob?.cancel()
        offsetY.snapTo(0f)
    }

    var isScrubbing by remember(state.sessionId) { mutableStateOf(false) }
    var scrubPosMs by remember(state.sessionId) { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .graphicsLayer {
                alpha = if (dismissTargetPx > 0f) {
                    (1f - (offsetY.value / dismissTargetPx)).coerceIn(0f, 1f)
                } else 1f
            }
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .pointerInput(state.sessionId) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dismissJob?.cancel()
                        scope.launch {
                            val newY = (offsetY.value + dragAmount).coerceAtLeast(0f)
                            offsetY.snapTo(newY)
                        }
                    },
                    onDragEnd = {
                        if (offsetY.value > dismissThresholdPx) {
                            val targetSessionId = state.sessionId
                            dismissJob?.cancel()
                            dismissJob = scope.launch {
                                val targetDistance = maxOf(dismissTargetPx, offsetY.value + with(density) { 30.dp.toPx() })
                                offsetY.animateTo(
                                    targetValue = targetDistance,
                                    animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
                                )
                                controller.stopAndClose(targetSessionId)
                            }
                        } else {
                            dismissJob?.cancel()
                            dismissJob = scope.launch {
                                offsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        dismissJob?.cancel()
                        dismissJob = scope.launch {
                            offsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    },
                )
            },
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        val activeColor = state.track.albumAccentColor?.let { Color(it) } ?: colorScheme.primary
        val lyrics = state.track.lyrics
        val hasLyrics = lyrics.isNotEmpty()

        val currentPos = if (isScrubbing) scrubPosMs else state.positionMs
        val lyricsLookupPos = if (isScrubbing) scrubPosMs.toLong() else (state.positionMs + LYRICS_SYNC_LEAD_MS)
        val activeLyricIndex = remember(state.track.path, lyrics, lyricsLookupPos) {
            if (lyrics.isEmpty()) -1
            else lyrics.indexOfLast { it.timestampMs <= lyricsLookupPos }
        }

        val lyricDisplayState = remember(state.track.path, lyrics, lyricsLookupPos) {
            if (lyrics.isEmpty() || activeLyricIndex == -1) {
                LyricDisplayState(id = -1, text = "", isIdle = true, anchorTimeMs = 0L)
            } else {
                val currentLyric = lyrics[activeLyricIndex]
                val rawText = currentLyric.text.trim()
                val nextLyric = lyrics.getOrNull(activeLyricIndex + 1)
                val elapsedSinceLineMs = lyricsLookupPos - currentLyric.timestampMs

                val isInterlude = if (rawText.isEmpty()) {
                    true
                } else if (nextLyric != null) {
                    val gapMs = nextLyric.timestampMs - currentLyric.timestampMs
                    gapMs >= 9_000 && elapsedSinceLineMs >= 6_500
                } else {
                    elapsedSinceLineMs >= 7_000
                }

                if (isInterlude) {
                    val anchor = if (rawText.isEmpty()) currentLyric.timestampMs else currentLyric.timestampMs + 6_500L
                    LyricDisplayState(id = 10_000 + activeLyricIndex, text = "", isIdle = true, anchorTimeMs = anchor)
                } else {
                    LyricDisplayState(id = activeLyricIndex, text = rawText, isIdle = false, anchorTimeMs = currentLyric.timestampMs)
                }
            }
        }

        val idleTransition = rememberInfiniteTransition(label = "idle_lyrics")
        val idleAnimatedAlpha by idleTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "idle_alpha",
        )
        val idleAlpha = if (state.isPlaying) idleAnimatedAlpha else 0.6f

        val thumbSize = if (hasLyrics) 52.dp else 46.dp
        val thumbIconSize = if (hasLyrics) 28.dp else 24.dp

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(thumbSize)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.track.albumArt != null) {
                        Image(
                            bitmap = state.track.albumArt.asImageBitmap(),
                            contentDescription = "Album art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(thumbIconSize),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val displayTitle = state.track.title ?: state.track.name
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        color = colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .height(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val displaySubtitle = state.track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                        Text(
                            text = displaySubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .basicMarquee(),
                        )

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isScrubbing,
                            enter = fadeIn(animationSpec = tween(120)),
                            exit = fadeOut(animationSpec = tween(120)),
                        ) {
                            val clampedPos = if (state.durationMs > 0) scrubPosMs.coerceIn(0, state.durationMs) else scrubPosMs.coerceAtLeast(0)
                            val totalSec = clampedPos / 1000
                            val hours = totalSec / 3600
                            val minutes = (totalSec % 3600) / 60
                            val seconds = totalSec % 60
                            val formattedTime = if (hours > 0) {
                                String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                            } else {
                                String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.Black.copy(alpha = 0.85f),
                            ) {
                                Text(
                                    text = formattedTime,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (hasLyrics) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .height(16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            AnimatedContent(
                                targetState = lyricDisplayState,
                                transitionSpec = {
                                    val isForward = targetState.anchorTimeMs >= initialState.anchorTimeMs
                                    if (isForward) {
                                        (slideInVertically(animationSpec = tween(170, easing = FastOutSlowInEasing)) { height -> height } +
                                                fadeIn(animationSpec = tween(130)))
                                            .togetherWith(
                                                slideOutVertically(animationSpec = tween(170, easing = FastOutSlowInEasing)) { height -> -height } +
                                                        fadeOut(animationSpec = tween(130))
                                            )
                                    } else {
                                        (slideInVertically(animationSpec = tween(170, easing = FastOutSlowInEasing)) { height -> -height } +
                                                fadeIn(animationSpec = tween(130)))
                                            .togetherWith(
                                                slideOutVertically(animationSpec = tween(170, easing = FastOutSlowInEasing)) { height -> height } +
                                                        fadeOut(animationSpec = tween(130))
                                            )
                                    }
                                },
                                label = "lyrics_scroll",
                            ) { displayState ->
                                if (displayState.isIdle) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.graphicsLayer { alpha = idleAlpha },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = colorScheme.primary,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = "•••",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                letterSpacing = 1.5.sp,
                                            ),
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = displayState.text,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                        ),
                                        color = colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .basicMarquee(
                                                initialDelayMillis = 800,
                                                velocity = 32.dp,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = controller::previous,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous track",
                            tint = if (state.hasPrevious) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surfaceContainerHighest)
                            .clickable(onClick = controller::togglePlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(
                        onClick = controller::next,
                        enabled = state.hasNext,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next track",
                            tint = if (state.hasNext) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            WavyScrubberBar(
                durationMs = state.durationMs,
                positionMs = if (isScrubbing) scrubPosMs else state.positionMs,
                isPlaying = state.isPlaying,
                isScrubbing = isScrubbing,
                activeColor = activeColor,
                onScrubStart = {
                    isScrubbing = true
                    scrubPosMs = state.positionMs
                },
                onScrub = { newPos ->
                    scrubPosMs = newPos
                },
                onScrubEnd = { finalPos ->
                    controller.seekTo(finalPos)
                    isScrubbing = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(24.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val currentTimestamp = formatAudioTimestamp(if (isScrubbing) scrubPosMs else state.positionMs)
                val durationTimestamp = if (state.durationMs > 0) formatAudioTimestamp(state.durationMs) else "--:--"

                Text(
                    text = currentTimestamp,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = if (isScrubbing) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isScrubbing) colorScheme.primary else colorScheme.onSurfaceVariant,
                )

                Text(
                    text = durationTimestamp,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                    ),
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatAudioTimestamp(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}

@Composable
private fun WavyScrubberBar(
    durationMs: Int,
    positionMs: Int,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    activeColor: Color,
    onScrubStart: () -> Unit,
    onScrub: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    val progressFraction = if (durationMs > 0) {
        (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    val animatedActiveColor by animateColorAsState(
        targetValue = activeColor,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "active_color",
    )
    val trackColor = animatedActiveColor.copy(alpha = 0.28f)

    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")
    val phaseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val targetAmplitude = if (isPlaying && !isScrubbing && durationMs > 0) with(density) { 4.2.dp.toPx() } else 0f
    val animatedAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "wave_amplitude",
    )

    val thumbHeight by animateFloatAsState(
        targetValue = if (isScrubbing) with(density) { 18.dp.toPx() } else with(density) { 14.dp.toPx() },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thumb_height",
    )
    val thumbWidth by animateFloatAsState(
        targetValue = if (isScrubbing) with(density) { 5.dp.toPx() } else with(density) { 3.5.dp.toPx() },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thumb_width",
    )

    val waveLengthPx = with(density) { 22.dp.toPx() }
    val strokeWidthPx = with(density) { 3.5.dp.toPx() }

    Box(
        modifier = modifier
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toDouble()
                    fun calculateMs(x: Float): Int {
                        if (width <= 0.0 || durationMs <= 0) return 0
                        val fraction = (x.toDouble() / width).coerceIn(0.0, 1.0)
                        return (fraction * durationMs.toDouble()).roundToInt()
                    }

                    onScrubStart()
                    var currentMs = calculateMs(down.position.x)
                    onScrub(currentMs)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            onScrubEnd(currentMs)
                            break
                        }
                        if (change.position.x != change.previousPosition.x) {
                            change.consume()
                            currentMs = calculateMs(change.position.x)
                            onScrub(currentMs)
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val activeWidth = (size.width * progressFraction).coerceIn(0f, size.width)

            if (activeWidth < size.width) {
                drawLine(
                    color = trackColor,
                    start = Offset(activeWidth, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }

            if (activeWidth > 0f) {
                val path = Path()
                val phaseOffset = phaseFraction * waveLengthPx
                val waveStart = -phaseOffset - waveLengthPx
                val waveEnd = activeWidth + waveLengthPx
                val dist = waveLengthPx / 2f

                path.moveTo(waveStart, centerY)
                var currentX = waveStart
                var waveSign = 1f
                val initialLead = (currentX / waveLengthPx).coerceIn(0f, 1f)
                var currentAmp = initialLead * animatedAmplitude * waveSign

                while (currentX < waveEnd) {
                    waveSign = -waveSign
                    val nextX = currentX + dist
                    val midX = currentX + dist / 2f
                    val leadIn = (nextX / waveLengthPx).coerceIn(0f, 1f)
                    val nextAmp = leadIn * animatedAmplitude * waveSign

                    path.cubicTo(
                        midX, centerY + currentAmp,
                        midX, centerY + nextAmp,
                        nextX, centerY + nextAmp,
                    )
                    currentAmp = nextAmp
                    currentX = nextX
                }

                clipRect(left = 0f, top = 0f, right = activeWidth, bottom = size.height) {
                    drawPath(
                        path = path,
                        color = animatedActiveColor,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                    )
                }
            }

            drawLine(
                color = animatedActiveColor,
                start = Offset(activeWidth, centerY - thumbHeight / 2f),
                end = Offset(activeWidth, centerY + thumbHeight / 2f),
                strokeWidth = thumbWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private data class LyricDisplayState(
    val id: Int,
    val text: String,
    val isIdle: Boolean,
    val anchorTimeMs: Long,
)

private const val LYRICS_SYNC_LEAD_MS = 150L
