package dev.neroices.zibzip.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.runtime.Immutable
import dev.neroices.zibzip.domain.FileTypes
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.service.AudioPlayerService
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
data class AudioTrack(
    val path: String,
    val name: String,
    val subtitle: String,
    val size: Long,
    val title: String? = null,
    val artist: String? = null,
    val albumArt: Bitmap? = null,
    val albumAccentColor: Int? = null,
    val lyrics: List<LyricLine> = emptyList(),
)

@Immutable
data class AudioPlaybackState(
    val track: AudioTrack,
    val isPlaying: Boolean,
    val positionMs: Int,
    val durationMs: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val queueIndex: Int,
    val queueSize: Int,
    val sessionId: Long = 0L,
)

class AudioPlayerController(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var tickerJob: Job? = null
    private var metadataJob: Job? = null

    private var queue: List<AudioTrack> = emptyList()
    private var currentIndex: Int = -1
    private var currentSessionId: Long = 0L

    private val _state = MutableStateFlow<AudioPlaybackState?>(null)
    val state: StateFlow<AudioPlaybackState?> = _state.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    fun playFile(path: String, folderAudios: List<String>? = null) {
        val target = File(path)
        if (!target.exists()) return

        val tracks = if (!folderAudios.isNullOrEmpty()) {
            folderAudios.map { p ->
                val f = File(p)
                AudioTrack(
                    path = p,
                    name = f.name,
                    subtitle = formatSize(f.length()),
                    size = f.length(),
                )
            }
        } else {
            val parent = target.parentFile
            val siblings = parent?.listFiles()
                ?.filter { !it.isDirectory && FileTypes.isAudioFile(it.name) }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?: listOf(target)

            siblings.map { f ->
                AudioTrack(
                    path = f.absolutePath,
                    name = f.name,
                    subtitle = formatSize(f.length()),
                    size = f.length(),
                )
            }
        }

        val idx = tracks.indexOfFirst { it.path == target.absolutePath }.coerceAtLeast(0)
        playQueue(tracks, idx)
    }

    fun playQueue(tracks: List<AudioTrack>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        queue = tracks
        currentIndex = index
        playTrack(queue[currentIndex])
    }

    private fun playTrack(track: AudioTrack) {
        currentSessionId++
        releasePlayer()
        requestAudioFocus()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(track.path)
                prepare()
                start()
            }
            mediaPlayer = player

            player.setOnCompletionListener { mp ->
                if (mp != mediaPlayer) return@setOnCompletionListener
                if (currentIndex < queue.size - 1) {
                    next()
                } else {
                    try { mp.seekTo(0) } catch (_: Exception) {}
                    _state.value = _state.value?.copy(isPlaying = false, positionMs = 0)
                    stopTicker()
                    AudioPlayerService.start(context)
                }
            }

            player.setOnErrorListener { mp, _, _ ->
                if (mp != mediaPlayer) return@setOnErrorListener true
                context.zibzipApp.toast("Error playing ${track.name}")
                stopAndClose()
                true
            }

            updateState(isPlaying = true)
            startTicker()
            loadMetadataAsync(track)
            AudioPlayerService.start(context)
        } catch (e: Exception) {
            context.zibzipApp.toast("Failed to play: ${e.message}")
            stopAndClose()
        }
    }

    private fun loadMetadataAsync(track: AudioTrack) {
        metadataJob?.cancel()
        metadataJob = scope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(track.path)
                val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                val title = if (!rawTitle.isNullOrEmpty()) rawTitle else null
                val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
                val artist = if (!rawArtist.isNullOrEmpty()) rawArtist else null

                val picBytes = retriever.embeddedPicture
                val albumArt: Bitmap? = if (picBytes != null) {
                    try {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
                        var sample = 1
                        val maxDim = 384
                        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                            sample *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, decodeOpts)
                    } catch (_: Throwable) {
                        null
                    }
                } else null

                val accentColor: Int? = if (albumArt != null) {
                    extractAccentColor(albumArt)
                } else null

                val lyrics = LrcParser.findAndParseForAudio(track.path)

                if (isActive) {
                    withContext(Dispatchers.Main.immediate) {
                        val current = _state.value
                        if (current != null && current.track.path == track.path && mediaPlayer != null) {
                            val updatedTrack = current.track.copy(
                                title = title,
                                artist = artist,
                                albumArt = albumArt,
                                albumAccentColor = accentColor,
                                lyrics = lyrics,
                            )
                            if (currentIndex in queue.indices && queue[currentIndex].path == track.path) {
                                val newQueue = queue.toMutableList()
                                newQueue[currentIndex] = updatedTrack.copy(albumArt = null)
                                queue = newQueue
                            }
                            _state.value = current.copy(track = updatedTrack)
                            AudioPlayerService.start(context)
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun extractAccentColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0xFF42A5F5.toInt()

        var bestColor = 0
        var bestScore = -1f

        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        val hsv = FloatArray(3)

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha < 128) continue

                android.graphics.Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]

                if (value in 0.15f..0.98f && saturation in 0.20f..1.0f) {
                    val score = saturation * 2.5f + value
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = pixel
                    }
                }
            }
        }

        val targetColor = if (bestScore > 0f) {
            bestColor
        } else {
            val center = bitmap.getPixel(width / 2, height / 2)
            if ((center and 0xFFFFFF) != 0) center else 0xFF42A5F5.toInt()
        }

        android.graphics.Color.colorToHSV(targetColor, hsv)
        hsv[1] = hsv[1].coerceIn(0.40f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.80f, 0.98f)
        return android.graphics.Color.HSVToColor(0xFF, hsv)
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                updateState(isPlaying = false)
                stopTicker()
                AudioPlayerService.start(context)
            }
        }
    }

    fun resume() {
        requestAudioFocus()
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                updateState(isPlaying = true)
                startTicker()
                AudioPlayerService.start(context)
            }
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let { player ->
            val target = positionMs.coerceIn(0, player.duration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(target)
            }
            _state.value = _state.value?.copy(positionMs = target)
            AudioPlayerService.start(context)
        }
    }

    fun next() {
        if (currentIndex < queue.size - 1) {
            currentIndex++
            playTrack(queue[currentIndex])
        }
    }

    fun previous() {
        val player = mediaPlayer
        if (player != null && player.currentPosition > 3000) {
            seekTo(0)
        } else if (currentIndex > 0) {
            currentIndex--
            playTrack(queue[currentIndex])
        } else {
            seekTo(0)
        }
    }

    fun stopAndClose(targetSessionId: Long? = null) {
        if (targetSessionId != null && targetSessionId != currentSessionId) {
            return
        }
        releasePlayer()
        abandonAudioFocus()
        stopTicker()
        queue = emptyList()
        currentIndex = -1
        _state.value = null
        AudioPlayerService.stop(context)
    }

    private fun updateState(isPlaying: Boolean) {
        val player = mediaPlayer ?: return
        if (currentIndex in queue.indices) {
            val track = queue[currentIndex]
            val duration = try { player.duration } catch (_: Exception) { 0 }
            val pos = try { player.currentPosition } catch (_: Exception) { 0 }
            _state.value = AudioPlaybackState(
                track = track,
                isPlaying = isPlaying,
                positionMs = pos,
                durationMs = duration,
                hasPrevious = currentIndex > 0 || pos > 3000,
                hasNext = currentIndex < queue.size - 1,
                queueIndex = currentIndex,
                queueSize = queue.size,
                sessionId = currentSessionId,
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val pos = try { player.currentPosition } catch (_: Exception) { 0 }
                        _state.value = _state.value?.copy(positionMs = pos)
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun releasePlayer() {
        metadataJob?.cancel()
        metadataJob = null
        try {
            mediaPlayer?.let { player ->
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
                try {
                    if (player.isPlaying) player.stop()
                } catch (_: Throwable) {}
                player.release()
            }
        } catch (_: Throwable) {}
        mediaPlayer = null
    }
}
