package com.aiclipcreator.core.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.aiclipcreator.core.models.DeviceCapabilities
import com.aiclipcreator.core.models.ProductionProfile

/**
 * Hardware inspection used to pick inference profiles, thread counts and model variants.
 * Nothing here is hard-coded per device: values come from the platform.
 */
object DeviceProfiler {

    fun capabilities(context: Context): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramMb = mi.totalMem / (1024L * 1024L)
        val freeRamMb = mi.availMem / (1024L * 1024L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val profile = when {
            ramMb <= 3_500 || cores <= 4 -> ProductionProfile.LITE
            ramMb <= 7_000 -> ProductionProfile.BALANCED
            else -> ProductionProfile.ADVANCED
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return DeviceCapabilities(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            manufacturer = Build.MANUFACTURER,
            sdkInt = Build.VERSION.SDK_INT,
            cores = cores,
            ramMb = ramMb,
            freeRamMb = freeRamMb,
            profile = profile,
            supportsNpu = Build.VERSION.SDK_INT >= 31 && abi.startsWith("arm64"),
            abi = abi,
            thermalStatus = thermalStatus(context)
        )
    }

    fun thermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } catch (t: Throwable) {
            Logx.w("Thermal status unavailable: ${t.message}")
            0
        }
    }

    fun freeStorageBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (t: Throwable) {
        0L
    }

    fun totalStorageBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).totalBytes
    } catch (t: Throwable) {
        0L
    }

    /** Whisper thread count: leave one core for the UI. */
    fun inferenceThreads(caps: DeviceCapabilities): Int =
        (caps.cores - 1).coerceIn(1, 6)

    fun batchSize(caps: DeviceCapabilities): Int = when (caps.profile) {
        ProductionProfile.LITE -> 1
        ProductionProfile.BALANCED -> 2
        ProductionProfile.ADVANCED -> 4
    }
}
