package com.aiclipcreator.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AudioPipelineTest {

    private val sampleRate = 16_000

    private fun tone(seconds: Double, freq: Double = 220.0, amplitude: Double = 0.3): FloatArray {
        val count = (sampleRate * seconds).toInt()
        return FloatArray(count) { i -> (amplitude * sin(2 * Math.PI * freq * i / sampleRate)).toFloat() }
    }

    private fun silence(seconds: Double): FloatArray = FloatArray((sampleRate * seconds).toInt())

    @Test
    fun `resampler converts 48k to 16k with the right length`() {
        val resampler = MonoResampler(48_000, 16_000)
        val input = FloatArray(48_000) { 0.1f }
        val output = resampler.process(input, input.size)
        assertTrue("expected roughly a third of the samples", output.size in 15_800..16_200)
        assertTrue(output.all { kotlin.math.abs(it - 0.1f) < 0.001f })
    }

    @Test
    fun `resampler preserves 16k audio`() {
        val resampler = MonoResampler(16_000, 16_000)
        val input = tone(0.5)
        val output = resampler.process(input, input.size)
        assertEquals(input.size, output.size)
        assertEquals(input[100], output[100], 0.0001f)
    }

    @Test
    fun `vad finds speech and ignores silence`() {
        val speech = tone(1.0)
        val silenceBlock = silence(1.0)
        val samples = speech + silenceBlock + tone(1.2)
        val detector = VoiceActivityDetector()
        val segments = detector.detect(samples)
        assertTrue("expected speech regions", segments.isNotEmpty())
        val total = segments.sumOf { it.durationMs }
        assertTrue("speech coverage should be meaningful: $total", total in 1_500..2_800)
        // The silent gap must not be reported as speech.
        assertTrue(segments.none { it.startMs in 1_050..1_950 && it.durationMs > 900 })
    }

    @Test
    fun `audio analyzer produces frames and detects peaks`() {
        val analyzer = AudioAnalyzer()
        val quiet = FloatArray(sampleRate * 3) { 0.001f }
        val loud = tone(1.0, amplitude = 0.8)
        val frames = analyzer.analyze(quiet + loud + quiet)
        assertTrue(frames.size > 30)
        val peaks = analyzer.peaks(frames, minGapMs = 1_000)
        assertTrue("loud section should register a peak", peaks.isNotEmpty())
        val loudest = frames.maxByOrNull { it.rmsDb }!!.rmsDb
        assertTrue(loudest > -12f)
    }

    @Test
    fun `wav writer and reader round trip`() {
        val file = java.io.File.createTempFile("audio", ".wav")
        try {
            val samples = tone(0.25)
            WavWriter(file, sampleRate).use { writer -> writer.write(samples) }
            WavReader(file, sampleRate).use { reader ->
                assertEquals(4_000, reader.totalSamples.toInt())
                val read = reader.readWindow(0, 4_000)
                assertEquals(samples.size, read.size)
                assertEquals(samples[500], read[500], 0.001f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `normalizer lifts quiet audio toward target peak`() {
        val samples = ShortArray(1_000) { 1_000 }
        val gain = AudioNormalizer.gainFor(samples)
        assertTrue(gain > 4f)
        val loud = ShortArray(1_000) { 30_000 }
        assertEquals(1f, AudioNormalizer.gainFor(loud), 0.6f)
    }
}
