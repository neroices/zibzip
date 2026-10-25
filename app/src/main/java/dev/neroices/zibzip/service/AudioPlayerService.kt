package dev.neroices.zibzip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import dev.neroices.zibzip.MainActivity
import dev.neroices.zibzip.R
import dev.neroices.zibzip.audio.AudioPlaybackState
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class AudioPlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSession(this, "ZibzipMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    zibzipApp.audioPlayer.resume()
                }

                override fun onPause() {
                    zibzipApp.audioPlayer.pause()
                }

                override fun onSkipToNext() {
                    zibzipApp.audioPlayer.next()
                }

                override fun onSkipToPrevious() {
                    zibzipApp.audioPlayer.previous()
                }

                override fun onSeekTo(pos: Long) {
                    zibzipApp.audioPlayer.seekTo(pos.toInt())
                }

                override fun onStop() {
                    zibzipApp.audioPlayer.stopAndClose()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> zibzipApp.audioPlayer.togglePlayPause()
            ACTION_PREV -> zibzipApp.audioPlayer.previous()
            ACTION_NEXT -> zibzipApp.audioPlayer.next()
            ACTION_CLOSE -> {
                zibzipApp.audioPlayer.stopAndClose()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val currentState = zibzipApp.audioPlayer.state.value
        if (currentState == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        updateMediaSession(currentState)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(currentState),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )

        job?.cancel()
        job = scope.launch {
            var lastTrackPath: String? = null
            var lastTrackTitle: String? = null
            var lastAlbumArt: Bitmap? = null
            var lastAccentColor: Int? = null
            var lastIsPlaying: Boolean? = null
            var lastReportedPos: Int = 0
            var lastReportedTime: Long = 0L

            zibzipApp.audioPlayer.state.collect { state ->
                if (state == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }

                val now = SystemClock.elapsedRealtime()
                val expectedPos = if (lastIsPlaying == true) {
                    (lastReportedPos + (now - lastReportedTime)).toInt()
                } else {
                    lastReportedPos
                }
                val isSignificantSeek = abs(state.positionMs - expectedPos) > 1500
                val trackChanged = state.track.path != lastTrackPath ||
                    state.track.albumArt != lastAlbumArt ||
                    state.track.title != lastTrackTitle ||
                    state.track.albumAccentColor != lastAccentColor
                val playStateChanged = state.isPlaying != lastIsPlaying

                if (trackChanged || playStateChanged || isSignificantSeek) {
                    lastTrackPath = state.track.path
                    lastTrackTitle = state.track.title
                    lastAlbumArt = state.track.albumArt
                    lastAccentColor = state.track.albumAccentColor
                    lastIsPlaying = state.isPlaying
                    lastReportedPos = state.positionMs
                    lastReportedTime = now

                    updateMediaSession(state)
                    val notification = buildNotification(state)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private fun updateMediaSession(state: AudioPlaybackState) {
        val session = mediaSession ?: return

        val title = state.track.title ?: state.track.name
        val artist = state.track.artist ?: state.track.subtitle

        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, state.track.subtitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.toLong())

        state.track.albumArt?.let { art ->
            val scaled = scaleForBinder(art)
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, scaled)
        }
        session.setMetadata(metaBuilder.build())

        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO

        if (state.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT

        val stateInt = if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (state.isPlaying) 1.0f else 0.0f
        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(stateInt, state.positionMs.toLong(), speed, SystemClock.elapsedRealtime())
            .build()
        session.setPlaybackState(pbState)
    }

    private fun buildNotification(state: AudioPlaybackState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, AudioPlayerService::class.java).apply { this.action = action }
            return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val prevIntent = createActionPendingIntent(ACTION_PREV, 1)
        val playPauseIntent = createActionPendingIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = createActionPendingIntent(ACTION_NEXT, 3)
        val closeIntent = createActionPendingIntent(ACTION_CLOSE, 4)

        val playPauseIconRes = if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (state.isPlaying) "Pause" else "Play"

        val style = Notification.MediaStyle().apply {
            mediaSession?.let { setMediaSession(it.sessionToken) }
            setShowActionsInCompactView(0, 1, 2)
        }

        val title = state.track.title ?: state.track.name
        val artist = state.track.artist ?: state.track.subtitle

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_audio_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        state.track.albumAccentColor?.let { color ->
            builder.setColor(color)
            builder.setColorized(true)
        }

        state.track.albumArt?.let { art ->
            builder.setLargeIcon(scaleForBinder(art))
        }

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Previous",
                prevIntent,
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, playPauseIconRes),
                playPauseTitle,
                playPauseIntent,
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next",
                nextIntent,
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Close",
                closeIntent,
            ).build()
        )

        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Audio Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Music and audio playback controls"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun scaleForBinder(art: Bitmap): Bitmap {
        val maxDim = 256
        return if (art.width > maxDim || art.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(art.width, art.height)
            val targetW = (art.width * scale).toInt().coerceAtLeast(1)
            val targetH = (art.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(art, targetW, targetH, true)
        } else {
            art
        }
    }

    companion object {
        const val CHANNEL_ID = "zibzip_audio_playback"
        const val NOTIFICATION_ID = 4040

        const val ACTION_PLAY_PAUSE = "dev.neroices.zibzip.action.AUDIO_PLAY_PAUSE"
        const val ACTION_PREV = "dev.neroices.zibzip.action.AUDIO_PREV"
        const val ACTION_NEXT = "dev.neroices.zibzip.action.AUDIO_NEXT"
        const val ACTION_CLOSE = "dev.neroices.zibzip.action.AUDIO_CLOSE"

        fun start(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java)
            context.stopService(intent)
        }
    }
}
