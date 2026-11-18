package com.aiclipcreator.core.audio

import com.aiclipcreator.core.models.AudioFeatureFrame
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class AudioPeak(val timeMs: Long, val rmsDb: Float, val strength: Float)

/**
 * Cheap 4-band analysis over 100 ms windows. The band energies are produced by cascaded
 * one-pole low passes, which is enough to separate speech energy from broadband bursts such as
 * laughter, impacts or cheering without pulling a spectral model into the loop.
 */
class AudioAnalyzer(private val sampleRate: Int = AudioConfig.SAMPLE_RATE) {

    private val windowMs = 100
    private val windowSize = sampleRate * windowMs / 1000

    fun analyze(samples: FloatArray, offsetMs: Long = 0L): List<AudioFeatureFrame> {
        if (samples.isEmpty()) return emptyList()
        val frames = samples.size / windowSize
        val out = ArrayList<AudioFeatureFrame>(frames)
        fun smoothCoefficient(cutoff: Float): Float {
            val dt = 1.0 / sampleRate
            val rc = 1.0 / (2.0 * Math.PI * cutoff)
            return (dt / (rc + dt)).toFloat()
        }
        val alphaLow = smoothCoefficient(300f)
        val alphaMid = smoothCoefficient(1_200f)
        val alphaHigh = smoothCoefficient(3_500f)
        var lp1 = 0f
        var lp2 = 0f
        var lp3 = 0f
        var prevE1 = 0f
        var prevE2 = 0f
        var prevE3 = 0f
        for (f in 0 until frames) {
            val base = f * windowSize
            var sumSq = 0.0
            var sum1 = 0.0
            var sum2 = 0.0
            var sum3 = 0.0
            for (i in 0 until windowSize) {
                val x = samples[base + i]
                sumSq += x * x
                lp1 += alphaLow * (x - lp1)
                lp2 += alphaMid * (x - lp2)
                lp3 += alphaHigh * (x - lp3)
                sum1 += (lp1 - lp2).toDouble() * (lp1 - lp2)
                sum2 += (lp2 - lp3).toDouble() * (lp2 - lp3)
                sum3 += lp3.toDouble() * lp3
            }
            val rms = sqrt(sumSq / windowSize)
            val db = (20.0 * log10(rms.coerceAtLeast(1e-7))).toFloat()
            val e1 = sqrt(sum1 / windowSize).toFloat()
            val e2 = sqrt(sum2 / windowSize).toFloat()
            val e3 = sqrt(sum3 / windowSize).toFloat()
            val flux = max(0f, e1 - prevE1) + max(0f, e2 - prevE2) + max(0f, e3 - prevE3)
            prevE1 = e1; prevE2 = e2; prevE3 = e3
            out += AudioFeatureFrame(
                timeMs = offsetMs + f.toLong() * windowMs,
                rmsDb = db,
                spectralFlux = flux,
                speechProbability = 0f
            )
        }
        return out
    }

    /** Local maxima in the loudness envelope that stand out from the surrounding window. */
    fun peaks(frames: List<AudioFeatureFrame>, minGapMs: Long = 4_000): List<AudioPeak> {
        if (frames.size < 3) return emptyList()
        val window = 25 // 2.5 s each side
        val peaks = mutableListOf<AudioPeak>()
        for (i in 1 until frames.size - 1) {
            val cur = frames[i]
            if (cur.rmsDb < -34f) continue
            if (cur.rmsDb <= frames[i - 1].rmsDb || cur.rmsDb < frames[i + 1].rmsDb) continue
            val from = (i - window).coerceAtLeast(0)
            val to = (i + window).coerceAtMost(frames.size - 1)
            var localSum = 0f
            var count = 0
            for (j in from..to) {
                localSum += frames[j].rmsDb
                count++
            }
            val localMean = localSum / count.coerceAtLeast(1)
            val lift = cur.rmsDb - localMean
            if (lift < 7f) continue
            val strength = ((lift - 7f) / 14f).coerceIn(0f, 1f)
            val last = peaks.lastOrNull()
            if (last != null && cur.timeMs - last.timeMs < minGapMs) {
                if (strength > last.strength) peaks[peaks.size - 1] = AudioPeak(cur.timeMs, cur.rmsDb, strength)
            } else {
                peaks += AudioPeak(cur.timeMs, cur.rmsDb, strength)
            }
        }
        return peaks
    }

    /**
     * Bursts of fast, broadband energy repeated within a short window: the audio shape that
     * laughter, cheering and hype reactions share. Not treated as evidence on its own.
     */
    fun reactionBursts(frames: List<AudioFeatureFrame>): List<AudioPeak> {
        if (frames.size < 20) return emptyList()
        val peaks = peaks(frames, minGapMs = 1_500)
        val bursts = mutableListOf<AudioPeak>()
        var i = 0
        while (i < peaks.size) {
            var j = i + 1
            while (j < peaks.size && peaks[j].timeMs - peaks[j - 1].timeMs <= 2_200) j++
            if (j - i >= 3) {
                val span = peaks[j - 1].timeMs - peaks[i].timeMs
                if (span in 800..8_000) {
                    val strength = ((j - i) / 6f).coerceIn(0f, 1f)
                    bursts += AudioPeak(peaks[i].timeMs, peaks[i].rmsDb, strength)
                }
            }
            i = j
        }
        return bursts
    }

    fun loudestDb(frames: List<AudioFeatureFrame>): Float =
        frames.maxOfOrNull { it.rmsDb } ?: -60f

    fun averageDb(frames: List<AudioFeatureFrame>): Float {
        if (frames.isEmpty()) return -60f
        var sum = 0f
        for (f in frames) sum += f.rmsDb
        return sum / frames.size
    }

    /** Clipping check used to warn about source audio that is already distorted. */
    fun clippingRatio(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var clipped = 0
        for (s in samples) if (abs(s) > 0.995f) clipped++
        return clipped.toFloat() / samples.size
    }
}
