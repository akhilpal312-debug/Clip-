package com.aiclipcreator.app.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiclipcreator.app.AiClipApplication
import com.aiclipcreator.core.common.Logx
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net: re-queues interrupted jobs and restarts the processing service if work is
 * waiting. WorkManager persists this across reboots.
 */
class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = runCatching {
            (applicationContext as AiClipApplication).container
        }.getOrNull() ?: return Result.success()
        return try {
            val recovered = container.recoverInterruptedJobs()
            val next = container.jobRepository.nextActive()
            if (next != null) {
                runCatching { ProcessingService.start(applicationContext) }
                    .onFailure { Logx.w("Recovery could not start service: ${it.message}") }
            }
            Logx.i("Recovery worker: $recovered interrupted, next=${next?.videoName}")
            Result.success()
        } catch (t: Throwable) {
            Logx.w("Recovery worker failed: ${t.message}")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "aiclip-recovery"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
