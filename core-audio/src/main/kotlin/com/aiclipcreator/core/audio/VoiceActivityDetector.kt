package com.aiclipcreator.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.min

data class SpeechSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Frame based voice activity detection.
 *
 * Combines short-time energy with zero-crossing rate against an adaptive noise floor estimated
 * from the quietest 20% of frames. A model based detector (Silero, ONNX) can be layered on top;
 * this DSP path is the always-available baseline and is what runs for long files.
 */
class VoiceActivityDetector(
    private val sampleRate: Int = AudioConfig.SAMPLE_RATE,
    private val frameMs: Int = 30
) {
    private val frameSize = (sampleRate * frameMs / 1000).coerceAtLeast(160)

    fun frameEnergiesDb(samples: FloatArray): FloatArray {
        val frames = samples.size / frameSize
        val out = FloatArray(frames)
        var i = 0
        while (i < frames) {
            var sum = 0.0
            val base = i * frameSize
            var j = 0
            while (j < frameSize) {
                val s = samples[base + j].toDouble()
                sum += s * s
                j++
            }
            val rms = sqrt(sum / frameSize)
            out[i] = (20.0 * log10(rms.coerceAtLeast(1e-7))).toFloat()
            i++
        }
        return out
    }

    fun frameZeroCrossings(samples: FloatArray): FloatArray {
        val frames = samples.size / frameSize
        val out = FloatArray(frames)
        var i = 0
        while (i < frames) {
            var crosses = 0
            val base = i * frameSize
            var j = 1
            while (j < frameSize) {
                if ((samples[base + j - 1] >= 0f) != (samples[base + j] >= 0f)) crosses++
                j++
            }
            out[i] = crosses.toFloat() / frameSize
            i++
        }
        return out
    }

    /** Returns speech regions, merging short gaps so sentences stay whole. */
    fun detect(
        samples: FloatArray,
        offsetMs: Long = 0L,
        minSpeechMs: Long = 250,
        minSilenceMs: Long = 320
    ): List<SpeechSegment> {
        if (samples.isEmpty()) return emptyList()
        val energies = frameEnergiesDb(samples)
        if (energies.isEmpty()) return emptyList()
        val crossings = frameZeroCrossings(samples)
        val sorted = energies.sortedArray()
        val noiseFloor = sorted[(sorted.size * 0.20).toInt().coerceIn(0, sorted.size - 1)]
        val peak = sorted.last()
        val threshold = max(noiseFloor + 7.5f, peak - 26f).coerceAtLeast(-58f)

        val speech = BooleanArray(energies.size) { i ->
            energies[i] > threshold && crossings[i] in 0.01f..0.42f
        }
        // Close single-frame dropouts inside a phrase.
        for (i in 1 until speech.size - 1) {
            if (!speech[i] && speech[i - 1] && speech[i + 1]) speech[i] = true
        }

        val segments = mutableListOf<SpeechSegment>()
        val maxSilenceFrames = (minSilenceMs / frameMs).toInt().coerceAtLeast(1)
        var startFrame = -1
        var lastSpeechFrame = -1
        for (i in speech.indices) {
            if (speech[i]) {
                if (startFrame < 0) startFrame = i
                lastSpeechFrame = i
            } else if (startFrame >= 0 && i - lastSpeechFrame > maxSilenceFrames) {
                val start = offsetMs + startFrame.toLong() * frameMs
                val end = offsetMs + (lastSpeechFrame + 1).toLong() * frameMs
                if (end - start >= minSpeechMs) segments += SpeechSegment(start, end)
                startFrame = -1
            }
        }
        if (startFrame >= 0) {
            val start = offsetMs + startFrame.toLong() * frameMs
            val end = offsetMs + (lastSpeechFrame + 1).toLong() * frameMs
            if (end - start >= minSpeechMs) segments += SpeechSegment(start, end)
        }
        return segments
    }

    /** Per-frame speech probability used by the ranking engine. */
    fun speechProbabilities(energies: FloatArray, crossings: FloatArray, threshold: Float): FloatArray {
        val out = FloatArray(energies.size)
        var i = 0
        while (i < energies.size) {
            val energyScore = ((energies[i] - threshold + 6f) / 24f).coerceIn(0f, 1f)
            val zcrScore = 1f - (abs(crossings[i] - 0.14f) / 0.28f).coerceIn(0f, 1f)
            out[i] = (energyScore * 0.75f + zcrScore * 0.25f).coerceIn(0f, 1f)
            i++
        }
        return out
    }

    /** Expands a speech region to a full sentence window for clip boundaries. */
    fun sentenceWindow(segments: List<SpeechSegment>, index: Int, maxMs: Long): Pair<Long, Long> {
        if (segments.isEmpty()) return 0L to 0L
        val seed = segments[index.coerceIn(0, segments.size - 1)]
        var start = seed.startMs
        var end = seed.endMs
        var i = index - 1
        while (i >= 0 && start - segments[i].startMs <= maxMs && segments[i].durationMs <= maxMs) {
            if (start - segments[i].startMs > 4_000) break
            start = segments[i].startMs
            i--
        }
        var j = index + 1
        while (j < segments.size && segments[j].endMs - end <= 2_000 && segments[j].endMs - start <= maxMs) {
            end = segments[j].endMs
            j++
        }
        return start to min(end, start + maxMs)
    }
}
