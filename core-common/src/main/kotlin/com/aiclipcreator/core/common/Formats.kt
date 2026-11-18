package com.aiclipcreator.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formats {

    /** 1:23:45 for long media, 12:34 for short media. */
    fun duration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun mb(bytes: Long): Long = bytes / (1024L * 1024L)

    fun percent(value: Float): String = String.format(Locale.US, "%d%%", (value.coerceIn(0f, 1f) * 100).toInt())

    fun score(value: Float): String = String.format(Locale.US, "%.2f", value)

    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun shortDate(epochMs: Long): String =
        SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(epochMs))

    fun fileName(prefix: String, quality: String, epochMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(epochMs))
        val safePrefix = prefix.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(40).ifBlank { "Clip" }
        return "${safePrefix}_${quality}_$stamp.mp4"
    }

    /** SRT timestamp: 00:00:01,200 */
    fun srtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val msPart = safe % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msPart)
    }
}
