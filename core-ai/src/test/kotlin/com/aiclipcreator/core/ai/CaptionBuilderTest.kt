package com.aiclipcreator.core.ai

import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.models.TranscriptWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionBuilderTest {

    private val words = listOf(
        TranscriptWord(1_000, 1_400, "This"),
        TranscriptWord(1_400, 1_800, "is"),
        TranscriptWord(1_800, 2_300, "a"),
        TranscriptWord(2_300, 3_000, "test."),
        TranscriptWord(3_100, 3_600, "Second"),
        TranscriptWord(3_600, 4_200, "sentence.")
    )

    private val segment = TranscriptSegment(
        startMs = 1_000,
        endMs = 4_200,
        text = "This is a test. Second sentence.",
        words = words
    )

    @Test
    fun `cues are rebased to the clip window`() {
        val cues = CaptionBuilder.cuesFor(listOf(segment), windowStartMs = 1_000, windowEndMs = 4_200)
        assertTrue(cues.isNotEmpty())
        assertEquals(0, cues.first().startMs)
        assertTrue(cues.all { it.startMs >= 0 && it.endMs > it.startMs })
    }

    @Test
    fun `cues never exceed the clip window`() {
        val cues = CaptionBuilder.cuesFor(listOf(segment), windowStartMs = 2_000, windowEndMs = 4_000)
        assertTrue(cues.all { it.endMs <= 2_000 })
    }

    @Test
    fun `srt is well formed`() {
        val cues = CaptionBuilder.cuesFor(listOf(segment), 0, 10_000)
        val srt = CaptionBuilder.toSrt(cues)
        assertTrue(srt.contains("-->"))
        assertTrue(srt.startsWith("1"))
    }

    @Test
    fun `editing text re-times words without inventing content`() {
        val cue = CaptionBuilder.cuesFor(listOf(segment), 0, 10_000).first()
        val edited = CaptionBuilder.retimed(cue, "Completely different words")
        assertEquals("Completely different words", edited.text)
        assertEquals(3, edited.words.size)
        assertEquals(cue.startMs, edited.words.first().startMs)
    }

    @Test
    fun `long text is split into readable cues`() {
        val longWords = (1..40).map { index ->
            TranscriptWord(index * 200L, index * 200L + 180, "word$index")
        }
        val longSegment = TranscriptSegment(
            startMs = 0,
            endMs = 8_000,
            text = longWords.joinToString(" ") { it.text },
            words = longWords
        )
        val cues = CaptionBuilder.cuesFor(listOf(longSegment), 0, 9_000, maxChars = 88)
        assertTrue(cues.size > 1)
        assertTrue(cues.all { it.text.length <= 120 })
        // No words are lost when splitting.
        val rebuilt = cues.joinToString(" ") { it.text }.split(" ").filter { it.isNotBlank() }
        assertEquals(40, rebuilt.size)
    }
}
