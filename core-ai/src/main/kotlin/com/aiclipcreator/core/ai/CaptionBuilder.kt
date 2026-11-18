package com.aiclipcreator.core.ai

import com.aiclipcreator.core.models.CaptionCue
import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.models.TranscriptWord
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Turns transcript segments inside a clip range into synchronised caption cues and SRT.
 * Cues are rebuilt from the words the ASR actually produced — no text is added.
 */
object CaptionBuilder {

    private const val MAX_CUE_CHARS = 88
    private const val MAX_CUE_MS = 6_500L
    private const val MIN_CUE_MS = 900L

    fun cuesFor(
        transcript: List<TranscriptSegment>,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChars: Int = MAX_CUE_CHARS
    ): List<CaptionCue> {
        if (windowEndMs <= windowStartMs) return emptyList()
        val overlapping = transcript.filter { it.endMs > windowStartMs && it.startMs < windowEndMs }
        val cues = ArrayList<CaptionCue>()
        for (segment in overlapping) {
            val text = segment.text.trim()
            if (text.isEmpty()) continue
            val start = max(segment.startMs, windowStartMs)
            val end = minOf(segment.endMs, windowEndMs)
            if (end - start < 120) continue
            val words = segment.words.filter { it.endMs > windowStartMs && it.startMs < windowEndMs }
            if (words.isEmpty() || text.length <= maxChars) {
                cues += CaptionCue(
                    startMs = start - windowStartMs,
                    endMs = (end - windowStartMs).coerceAtLeast(start - windowStartMs + MIN_CUE_MS),
                    text = text,
                    words = words.map { it.rebase(windowStartMs) },
                    speaker = segment.speaker
                )
            } else {
                cues += splitWithWords(words, windowStartMs, windowEndMs, maxChars)
            }
        }
        return mergeShortCues(cues)
    }

    private fun splitWithWords(
        words: List<TranscriptWord>,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChars: Int
    ): List<CaptionCue> {
        val result = ArrayList<CaptionCue>()
        var buffer = ArrayList<TranscriptWord>()
        var charCount = 0
        fun flush() {
            if (buffer.isEmpty()) return
            val text = buffer.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank()) {
                val start = max(buffer.first().startMs, windowStartMs) - windowStartMs
                val end = (min(buffer.last().endMs, windowEndMs) - windowStartMs)
                result += CaptionCue(
                    startMs = start,
                    endMs = max(end, start + MIN_CUE_MS),
                    text = text,
                    words = buffer.map { it.rebase(windowStartMs) }
                )
            }
            buffer = ArrayList()
            charCount = 0
        }
        for (word in words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val projected = charCount + text.length + 1
            val tooLong = projected > maxChars
            val tooSlow = buffer.isNotEmpty() && (word.endMs - buffer.first().startMs) > MAX_CUE_MS
            val endsSentence = buffer.isNotEmpty() && text.lastOrNull() in setOf('.', '!', '?', '।')
            if (tooLong || tooSlow) flush()
            buffer.add(word)
            charCount += text.length + 1
            if (endsSentence || tooSlow) flush()
        }
        flush()
        return result
    }

    private fun mergeShortCues(cues: List<CaptionCue>): List<CaptionCue> {
        if (cues.isEmpty()) return cues
        val out = ArrayList<CaptionCue>()
        for (cue in cues) {
            val last = out.lastOrNull()
            if (last != null && cue.startMs - last.endMs < 250 &&
                (last.text.length + cue.text.length + 1) <= MAX_CUE_CHARS &&
                cue.endMs - last.startMs <= MAX_CUE_MS
            ) {
                out[out.size - 1] = last.copy(
                    endMs = max(cue.endMs, last.endMs),
                    text = "${last.text.trim()} ${cue.text.trim()}".trim(),
                    words = last.words + cue.words
                )
            } else {
                out += cue
            }
        }
        // Remove zero-length or overlapping cues.
        return out.mapIndexed { index, cue ->
            val nextStart = out.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
            if (cue.endMs > nextStart && nextStart > cue.startMs) cue.copy(endMs = nextStart) else cue
        }.filter { it.endMs > it.startMs }
    }

    fun toSrt(cues: List<CaptionCue>): String {
        if (cues.isEmpty()) return ""
        val builder = StringBuilder()
        var index = 1
        for (cue in cues) {
            builder.append(index++).append('\n')
            builder.append(srtTimestamp(cue.startMs)).append(" --> ").append(srtTimestamp(cue.endMs)).append('\n')
            builder.append(cue.text.trim()).append("\n\n")
        }
        return builder.toString().trimEnd() + "\n"
    }

    private fun srtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3_600_000
        val m = (safe % 3_600_000) / 60_000
        val s = (safe % 60_000) / 1000
        val milli = safe % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    private fun TranscriptWord.rebase(offsetMs: Long) = copy(
        startMs = (startMs - offsetMs).coerceAtLeast(0),
        endMs = (endMs - offsetMs).coerceAtLeast(0)
    )

    /** Re-times a cue proportionally after the user edits its text. */
    fun retimed(cue: CaptionCue, newText: String): CaptionCue {
        val words = newText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || cue.words.isEmpty()) return cue.copy(text = newText)
        val span = (cue.endMs - cue.startMs).coerceAtLeast(words.size.toLong() * 120)
        val perWord = span.toDouble() / words.size
        val rebuilt = words.mapIndexed { index, word ->
            TranscriptWord(
                startMs = cue.startMs + (perWord * index).roundToLong(),
                endMs = cue.startMs + (perWord * (index + 1)).roundToLong(),
                text = word
            )
        }
        return cue.copy(text = newText, words = rebuilt)
    }
}
