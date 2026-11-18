package com.aiclipcreator.app.processing

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aiclipcreator.app.AiClipApplication
import com.aiclipcreator.app.MainActivity
import com.aiclipcreator.app.R
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that drains the persistent job queue. Analysis is long-running by nature,
 * so it must be visible to the user and survive the app being backgrounded.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("Preparing analysis", 0f))
        val container = (application as AiClipApplication).container
        if (worker?.isActive != true) {
            worker = scope.launch {
                runCatching { container.recoverInterruptedJobs() }
                while (isActive) {
                    val more = container.processNextQueuedJob()
                    if (!more) break
                }
                stopSelf()
            }
        }
        if (notificationJob?.isActive != true) {
            notificationJob = scope.launch {
                container.jobRepository.observeActive().collectLatest { jobs ->
                    val job = jobs.firstOrNull { it.state.name == "RUNNING" } ?: jobs.firstOrNull()
                    if (job != null) {
                        val text = "${job.stage.label} · ${(job.progress * 100).toInt()}%" +
                            if (job.durationMs > 0) " · ${Formats.duration(job.durationMs)}" else ""
                        notify(buildNotification(job.videoName, job.progress, text))
                    } else {
                        notify(buildNotification("Waiting for jobs", 0f))
                    }
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        progress: Float,
        text: String = "Local AI analysis"
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProcessingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, AiClipApplication.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_stat_clip)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
        if (progress > 0f) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        Logx.i("Processing service stopping")
        worker?.cancel()
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.aiclipcreator.app.action.STOP_PROCESSING"

        fun start(context: Context) {
            val intent = Intent(context, ProcessingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logx.w("Unable to start processing service: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ProcessingService::class.java)) }
        }
    }
}
