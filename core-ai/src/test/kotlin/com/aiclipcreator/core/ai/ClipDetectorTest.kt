package com.aiclipcreator.core.ai

import com.aiclipcreator.core.models.AudioFeatureFrame
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.ClipQuality
import com.aiclipcreator.core.models.FrameAnalysis
import com.aiclipcreator.core.models.RankingConfig
import com.aiclipcreator.core.models.SceneCut
import com.aiclipcreator.core.models.SignalKind
import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.models.TranscriptWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipDetectorTest {

    private fun segment(startMs: Long, endMs: Long, text: String) = TranscriptSegment(
        startMs = startMs,
        endMs = endMs,
        text = text,
        words = text.split(" ").mapIndexed { index, word ->
            val step = (endMs - startMs) / text.split(" ").size
            TranscriptWord(startMs + index * step, startMs + (index + 1) * step, word, 0.9f)
        }
    )

    private fun loudFrames(fromMs: Long, toMs: Long, baseline: Float = -40f, peak: Float = -18f): List<AudioFeatureFrame> {
        val frames = ArrayList<AudioFeatureFrame>()
        var t = fromMs
        while (t < toMs) {
            val loud = t in (fromMs + (toMs - fromMs) / 3)..(fromMs + 2 * (toMs - fromMs) / 3)
            frames += AudioFeatureFrame(
                timeMs = t,
                rmsDb = if (loud) peak else baseline,
                spectralFlux = if (loud) 0.12f else 0.01f,
                speechProbability = 0.8f
            )
            t += 100
        }
        return frames
    }

    @Test
    fun `detects a strong moment and marks it as best quality`() {
        val detector = ClipDetector()
        val transcript = listOf(
            segment(0, 3_000, "Welcome to the stream."),
            segment(20_000, 26_000, "This is unbelievable! I can't believe that actually happened, it was crazy."),
            segment(40_000, 43_000, "Anyway, moving on.")
        )
        val result = detector.detect(
            DetectionInput(
                jobId = "job",
                transcript = transcript,
                audioFrames = loudFrames(0, 60_000),
                frames = (0..500).map {
                    FrameAnalysis(
                        timeMs = it * 100L,
                        brightness = 0.5f,
                        sharpness = 1f,
                        motion = if (it in 200..260) 0.4f else 0.05f,
                        faceCount = if (it in 200..260) 1 else 0,
                        largestFaceArea = if (it in 200..260) 0.2f else 0f,
                        faceCenterX = 0.5f,
                        faceCenterY = 0.4f
                    )
                },
                sceneCuts = listOf(SceneCut(21_000, 0.6f), SceneCut(24_000, 0.5f)),
                totalDurationMs = 60_000,
                mode = ClipMode.BOTH
            )
        )
        assertTrue("expected at least one candidate", result.isNotEmpty())
        val top = result.maxByOrNull { it.engagement }!!
        assertTrue("score should clear balanced threshold", top.engagement >= RankingConfig().balancedThreshold)
        assertTrue("candidate should carry detection reasons", top.reasons.isNotEmpty())
        assertTrue(top.reasons.any { it.signal == SignalKind.DIALOGUE })
        assertTrue(top.startMs < top.endMs)
    }

    @Test
    fun `respects minimum and maximum clip duration`() {
        val config = RankingConfig(minClipMs = 15_000, maxClipMs = 30_000)
        val detector = ClipDetector()
        val transcript = (0..12).map { index ->
            segment(
                index * 8_000L,
                index * 8_000L + 7_500,
                "This is unbelievable! Number $index actually happened and it was crazy, you have to listen."
            )
        }
        val result = detector.detect(
            DetectionInput(
                jobId = "job",
                transcript = transcript,
                audioFrames = loudFrames(0, 100_000),
                totalDurationMs = 100_000,
                config = config
            )
        )
        assertTrue(result.isNotEmpty())
        result.forEach {
            assertTrue("clip too short: ${it.durationMs}", it.durationMs >= config.minClipMs - 1_000)
            assertTrue("clip too long: ${it.durationMs}", it.durationMs <= config.maxClipMs + 2_000)
        }
    }

    @Test
    fun `removes duplicate overlapping clips`() {
        val config = RankingConfig(maxOverlapRatio = 0.35f, mergeGapMs = 500)
        val detector = ClipDetector()
        val transcript = listOf(
            segment(10_000, 18_000, "This is the amazing part of the story, listen to this!"),
            segment(18_200, 26_000, "This is the amazing part of the story, listen to this!"),
            segment(60_000, 68_000, "A completely different moment that is also good.")
        )
        val result = detector.detect(
            DetectionInput(
                jobId = "job",
                transcript = transcript,
                audioFrames = loudFrames(0, 80_000),
                totalDurationMs = 80_000,
                config = config
            )
        )
        val sorted = result.sortedBy { it.startMs }
        for (i in 0 until sorted.size) {
            for (j in i + 1 until sorted.size) {
                val overlap = minOf(sorted[i].endMs, sorted[j].endMs) - maxOf(sorted[i].startMs, sorted[j].startMs)
                val shorter = minOf(sorted[i].durationMs, sorted[j].durationMs)
                assertTrue("clips overlap too much", overlap.toDouble() / shorter < 0.6)
            }
        }
    }

    @Test
    fun `falls back to audio only detection without speech`() {
        val detector = ClipDetector()
        val result = detector.detect(
            DetectionInput(
                jobId = "job",
                transcript = emptyList(),
                audioFrames = loudFrames(0, 90_000, baseline = -55f, peak = -10f),
                frames = emptyList(),
                totalDurationMs = 90_000,
                mode = ClipMode.BALANCED
            )
        )
        assertTrue("audio-only path should still find moments", result.isNotEmpty())
        assertTrue(result.all { it.transcript.isEmpty() })
    }

    @Test
    fun `best mode filters to best quality only`() {
        val detector = ClipDetector(config = RankingConfig(bestThreshold = 0.5f, balancedThreshold = 0.2f))
        val transcript = (0..6).map { index ->
            segment(index * 10_000L, index * 10_000L + 8_000, "Something worth watching here, number $index.")
        }
        val best = detector.detect(
            DetectionInput(
                jobId = "job",
                transcript = transcript,
                audioFrames = loudFrames(0, 80_000),
                totalDurationMs = 80_000,
                mode = ClipMode.BEST
            )
        )
        assertTrue(best.all { it.quality == ClipQuality.BEST })
    }
}
