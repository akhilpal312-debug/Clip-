package com.aiclipcreator.core.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.SceneCut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class SampledFrame(
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

/**
 * Samples frames at an interval derived from the video length and the device profile, so a
 * five hour recording produces a bounded number of analyses instead of millions.
 */
class FrameSampler(private val context: Context) {

    fun intervalFor(durationMs: Long, budgetFrames: Int): Long {
        if (durationMs <= 0) return 2_000
        val ideal = durationMs / budgetFrames.coerceAtLeast(1)
        return ideal.coerceIn(1_500L, 20_000L)
    }

    suspend fun sample(
        uri: Uri,
        durationMs: Long,
        intervalMs: Long,
        targetWidth: Int = 320,
        maxFrames: Int = 4_000,
        onFrame: suspend (SampledFrame) -> Unit
    ) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            var t = 0L
            var count = 0
            while (t < durationMs && count < maxFrames) {
                currentCoroutineContext().ensureActive()
                val bitmap = runCatching {
                    retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (bitmap != null) {
                    val w = targetWidth.coerceAtMost(bitmap.width).coerceAtLeast(16)
                    val h = (bitmap.height * w / bitmap.width).coerceAtLeast(16)
                    val scaled = if (bitmap.width != w) android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
                    val pixels = IntArray(w * h)
                    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    onFrame(SampledFrame(t, w, h, pixels))
                    count++
                }
                t += intervalMs
            }
            Logx.i("Sampled $count frames every ${intervalMs}ms")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logx.e("Frame sampling failed", t)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** Detects hard cuts with an HSV-weighted colour histogram difference. */
object SceneDetector {

    fun histogram(pixels: IntArray, width: Int, height: Int, bins: Int = 16): FloatArray {
        val hist = FloatArray(bins * 2)
        val step = 4
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b)
            val lumBin = ((lum / 256.0) * bins).toInt().coerceIn(0, bins - 1)
            val chromaBin = (((r - b + 255) / 510.0) * bins).toInt().coerceIn(0, bins - 1)
            hist[lumBin] += 1f
            hist[bins + chromaBin] += 1f
            i += step
        }
        val total = (pixels.size / step).toFloat().coerceAtLeast(1f)
        for (k in hist.indices) hist[k] = hist[k] / total
        return hist
    }

    fun difference(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return (d / 2f).coerceIn(0f, 1f)
    }

    fun averageBrightness(pixels: IntArray): Float {
        var sum = 0.0
        var i = 0
        var n = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            i += 16
            n++
        }
        return (sum / n.coerceAtLeast(1)).toFloat()
    }

    fun sharpness(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3) return 0f
        var sum = 0.0
        var n = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val center = luminance(pixels[y * width + x])
                val right = luminance(pixels[y * width + x + 1])
                val down = luminance(pixels[(y + 1) * width + x])
                sum += abs(4 * center - right - down - luminance(pixels[y * width + x - 1]) - luminance(pixels[(y - 1) * width + x]))
                n++
                x += 2
            }
            y += 2
        }
        return (sum / n.coerceAtLeast(1) / 255.0 * 10.0).toFloat()
    }

    fun motion(a: FloatArray, b: FloatArray): Float = difference(a, b)

    private fun luminance(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    /** Turns per-frame diffs into scene change events. */
    fun cuts(scores: List<Pair<Long, Float>>, threshold: Float = 0.34f): List<SceneCut> {
        if (scores.isEmpty()) return emptyList()
        val sorted = scores.map { it.second }.sorted()
        val p80 = sorted[(sorted.size * 0.8).toInt().coerceIn(0, sorted.size - 1)]
        val effectiveThreshold = maxOf(threshold, p80 * 0.9f)
        val cuts = mutableListOf<SceneCut>()
        var lastCutMs = -10_000L
        for ((time, score) in scores) {
            if (score >= effectiveThreshold && time - lastCutMs > 1_200) {
                cuts += SceneCut(time, score)
                lastCutMs = time
            }
        }
        return cuts
    }
}
