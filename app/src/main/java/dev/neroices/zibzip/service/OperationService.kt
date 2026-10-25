package dev.neroices.zibzip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.neroices.zibzip.MainActivity
import dev.neroices.zibzip.R
import dev.neroices.zibzip.domain.OpState
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service keeping concurrent background file operations alive.
 * Features speed display (MB/s), percentage tracking, and cancellation.
 */
class OperationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    zibzipApp.runner.cancel(taskId)
                } else {
                    zibzipApp.runner.cancelAll()
                }
            }
            ACTION_CANCEL_ALL -> {
                zibzipApp.runner.cancelAll()
            }
        }

        createChannel()
        val currentTasks = zibzipApp.runner.tasks.value
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(currentTasks),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

        job?.cancel()
        job = scope.launch {
            zibzipApp.runner.tasks.collect { tasks ->
                if (tasks.isEmpty()) {
                    stopSelf()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastNotify >= 300) {
                        lastNotify = now
                        val notification = buildNotification(tasks)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        zibzipApp.runner.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(tasks: List<OpState.Running>): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (tasks.isEmpty()) {
            builder.setContentTitle(getString(R.string.app_name))
            return builder.build()
        }

        if (tasks.size == 1) {
            val task = tasks.first()
            val speedText = if (task.speedBytesPerSec > 0) "${formatSize(task.speedBytesPerSec)}/s" else ""
            val progressParts = listOfNotNull(
                "${task.percentage}%",
                speedText.takeIf { it.isNotEmpty() },
                task.current.takeIf { it.isNotEmpty() },
            )
            val cancelIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, OperationService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_TASK_ID, task.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            builder
                .setContentTitle(task.title)
                .setContentText(progressParts.joinToString(" · ").ifEmpty { null })
                .addAction(0, getString(android.R.string.cancel), cancelIntent)

            val fraction = task.fraction
            if (fraction != null) {
                builder.setProgress(1000, (fraction * 1000).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            // Multiple concurrent operations
            val cancelAllIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, OperationService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val inbox = NotificationCompat.InboxStyle()
            var totalPct = 0
            var knownPctCount = 0

            for (task in tasks) {
                val speed = if (task.speedBytesPerSec > 0) " · ${formatSize(task.speedBytesPerSec)}/s" else ""
                inbox.addLine("${task.title}: ${task.percentage}%$speed")
                if (task.total > 0) {
                    totalPct += task.percentage
                    knownPctCount++
                }
            }

            builder
                .setContentTitle("${tasks.size} operations running")
                .setContentText(tasks.joinToString(", ") { it.title })
                .setStyle(inbox)
                .addAction(0, "Cancel all", cancelAllIntent)

            if (knownPctCount > 0) {
                val avgPct = totalPct / tasks.size
                builder.setProgress(100, avgPct, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }

        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File operations",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "operations"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "dev.neroices.zibzip.CANCEL"
        private const val ACTION_CANCEL_ALL = "dev.neroices.zibzip.CANCEL_ALL"
        private const val EXTRA_TASK_ID = "extra_task_id"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, OperationService::class.java))
            } catch (e: IllegalStateException) {
                // Background start restriction
            }
        }

        @Suppress("unused")
        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
