package com.aiclipcreator.core.video

import com.aiclipcreator.core.models.AspectRatio
import com.aiclipcreator.core.models.ProductionProfile
import com.aiclipcreator.core.models.VideoMetadata

/** Output geometry and bitrate, never larger than the source. */
data class ExportGeometry(
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val frameRate: Int,
    val upscaled: Boolean
)

object ExportPresets {

    fun resolve(
        aspect: AspectRatio,
        source: VideoMetadata,
        profile: ProductionProfile,
        landscapeLongEdgeCap: Int = 1920
    ): ExportGeometry {
        val srcW = source.displayWidth.coerceAtLeast(2)
        val srcH = source.displayHeight.coerceAtLeast(2)
        val srcLong = maxOf(srcW, srcH)
        val cap = when (profile) {
            ProductionProfile.LITE -> 720
            ProductionProfile.BALANCED -> 1024
            ProductionProfile.ADVANCED -> 1440
        }
        // For portrait/square targets the long edge is the height.
        val targetLong = when (aspect) {
            AspectRatio.PORTRAIT_9_16 -> minOf(cap, 1920)
            AspectRatio.SQUARE_1_1 -> minOf(cap, 1440)
            AspectRatio.LANDSCAPE_16_9 -> minOf(cap, landscapeLongEdgeCap)
        }
        val aspectRatio = aspect.ratio
        var width: Int
        var height: Int
        if (aspectRatio >= 1f) {
            width = targetLong
            height = (width / aspectRatio).toInt()
        } else {
            height = targetLong
            width = (height * aspectRatio).toInt()
        }
        width = (width / 2) * 2
        height = (height / 2) * 2
        val upscaled = width > srcW && height > srcH
        if (upscaled) {
            // Keep the aspect but clamp so we never inflate source pixels.
            val scale = minOf(srcW.toFloat() / width, srcH.toFloat() / height)
            width = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
            height = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        }
        val pixels = width.toLong() * height
        // ~4.5 bits per pixel per second, clamped to practical H.264 levels.
        val bitrate = (pixels * 4.5).toInt().coerceIn(1_200_000, 12_000_000)
        val fps = source.frameRate.takeIf { it in 10f..61f }?.toInt() ?: 30
        return ExportGeometry(
            width = width.coerceAtLeast(2),
            height = height.coerceAtLeast(2),
            videoBitrate = bitrate,
            audioBitrate = 128_000,
            frameRate = fps,
            upscaled = false
        )
    }

    fun targetSize(aspect: AspectRatio, source: VideoMetadata, profile: ProductionProfile): Pair<Int, Int> {
        val g = resolve(aspect, source, profile)
        return g.width to g.height
    }
}
