package com.aiclipcreator.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.aiclipcreator.app.processing.ProcessingService
import com.aiclipcreator.app.processing.RecoveryWorker
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AiClipApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.processingStarter = { ProcessingService.start(this) }
        container.processingStopper = { ProcessingService.stop(this) }
        createNotificationChannels(this)
        container.refreshModels()
        RecoveryWorker.schedule(this)
        scope.launch {
            val recovered = runCatching { container.recoverInterruptedJobs() }.getOrDefault(0)
            if (recovered > 0) {
                Logx.i("Recovered $recovered interrupted job(s)")
            }
            val active = container.jobRepository.nextActive()
            if (active != null) {
                runCatching { ProcessingService.start(this@AiClipApplication) }
            }
        }
    }

    companion object {
        const val CHANNEL_PROCESSING = "processing"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_PROCESSING,
                "Clip processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress for video analysis and clip rendering"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
