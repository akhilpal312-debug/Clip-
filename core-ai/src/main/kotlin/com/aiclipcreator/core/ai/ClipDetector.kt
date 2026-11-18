package com.aiclipcreator.core.ai

import com.aiclipcreator.core.models.AudioFeatureFrame
import com.aiclipcreator.core.models.CandidateMoment
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.ClipQuality
import com.aiclipcreator.core.models.DetectionCategory
import com.aiclipcreator.core.models.DetectionReason
import com.aiclipcreator.core.models.FrameAnalysis
import com.aiclipcreator.core.models.RankingConfig
import com.aiclipcreator.core.models.SceneCut
import com.aiclipcreator.core.models.SignalKind
import com.aiclipcreator.core.models.TranscriptSegment
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectionInput(
    val jobId: String,
    val transcript: List<TranscriptSegment> = emptyList(),
    val sceneCuts: List<SceneCut> = emptyList(),
    val audioFrames: List<AudioFeatureFrame> = emptyList(),
    val frames: List<FrameAnalysis> = emptyList(),
    val totalDurationMs: Long,
    val mode: ClipMode = ClipMode.BOTH,
    val config: RankingConfig = RankingConfig()
)

/**
 * Multi-signal moment detection. Loudness and faces are only two weak components of a weighted
 * score; dialogue content, contextual completeness, pacing, scene activity and reaction cues all
 * contribute. Every candidate records the reasons and weights that produced it.
 */
class ClipDetector(private val config: RankingConfig = RankingConfig()) {

    private val emphasisWords = setOf(
        "amazing", "unbelievable", "crazy", "insane", "shocking", "unreal", "incredible",
        "actually", "honestly", "literally", "never", "always", "best", "worst", "secret",
        "mistake", "important", "problem", "solution", "listen", "watch", "look", "wait",
        "wow", "damn", "omg", "haha", "lol", "funny", "joke", "scared", "angry", "love", "hate",
        "kya", "kaise", "kyun", "mazaa", "maza", "pagal", "bacha", "bhai", "yaar", "dekho",
        "suno", "sach", "galat", "sahi", "kamaal", "dhamaka", "azaad", "zabardast", "bekaar"
    )

    private val laughterPattern = Regex("(haha+|hehe+|lol|lmao|\\[laugh[s]?]|😄|😂)", RegexOption.IGNORE_CASE)
    private val questionWords = Regex("\\b(kya|kaise|kyun|kyon|why|how|what|who|when|where|kaun|kab)\\b", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("[.!?।…]$")

    fun detect(input: DetectionInput): List<CandidateMoment> {
        val cfg = input.config
        if (input.totalDurationMs <= 0) return emptyList()
        val base = if (input.transcript.isNotEmpty()) {
            detectFromTranscript(input, cfg)
        } else {
            detectFromAudio(input, cfg)
        }
        if (base.isEmpty()) return emptyList()
        val merged = merge(base, cfg)
        val ranked = merged.sortedByDescending { it.engagement }
        val qualified = ranked.mapNotNull { candidate ->
            val quality = when {
                candidate.engagement >= cfg.bestThreshold -> ClipQuality.BEST
                candidate.engagement >= cfg.balancedThreshold -> ClipQuality.BALANCED
                else -> null
            } ?: return@mapNotNull null
            candidate.copy(quality = quality)
        }
        val deduped = dedupe(qualified, cfg)
        val limited = when (input.mode) {
            ClipMode.BEST -> deduped.filter { it.quality == ClipQuality.BEST }
            ClipMode.BALANCED, ClipMode.BOTH -> deduped
        }
        return limited.sortedBy { it.startMs }
    }

    // ---------------------------------------------------------------- transcript path

    private fun detectFromTranscript(input: DetectionInput, cfg: RankingConfig): List<CandidateMoment> {
        val segments = input.transcript
            .filter { it.text.isNotBlank() && it.noSpeechProbability < 0.75f }
            .sortedBy { it.startMs }
        if (segments.isEmpty()) return detectFromAudio(input, cfg)

        val groups = groupSentences(segments, cfg.maxClipMs)
        val globalMeanDb = input.audioFrames.map { it.rmsDb }.average().takeIf { !it.isNaN() } ?: -40.0
        val peakStrength = peakStrengthIndex(input.audioFrames)
        val out = ArrayList<CandidateMoment>(groups.size)

        for (group in groups) {
            val rawStart = group.first().startMs
            val rawEnd = group.last().endMs
            if (rawEnd - rawStart < 1_000) continue
            val text = group.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            if (text.length < 12) continue

            val window = expandForContext(segments, group, rawStart, rawEnd, cfg)
            val start = window.first
            val end = min(window.second, input.totalDurationMs)
            val durationMs = end - start
            if (durationMs < cfg.minClipMs || durationMs > cfg.maxClipMs + 2_000) continue

            val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
            val seconds = (end - start) / 1000.0
            val wordsPerSecond = wordCount / max(seconds, 1.0)
            val lowered = text.lowercase()
            val tokens = lowered.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            val emphasisHits = tokens.count { it in emphasisWords }
            val emphasisRatio = (emphasisHits / max(tokens.size, 1).toDouble()).coerceAtMost(0.35)
            val hasLaughter = laughterPattern.containsMatchIn(text)
            val isQuestion = text.contains('?') || questionWords.containsMatchIn(lowered)
            val completeThought = sentenceEnd.containsMatchIn(text) || seconds >= 8.0

            val audioSlice = input.audioFrames.filter { it.timeMs in start..end }
            val meanDb = audioSlice.map { it.rmsDb }.average().takeIf { !it.isNaN() } ?: globalMeanDb
            val energyLift = ((meanDb - globalMeanDb) / 12.0).coerceIn(0.0, 1.0)
            val peak = peakStrength.fractionIn(start, end)
            val cuts = input.sceneCuts.count { it.timeMs in start..end }
            val motion = input.frames.filter { it.timeMs in start..end }.map { it.motion }
                .average().takeIf { !it.isNaN() } ?: 0.0
            val faceSlice = input.frames.filter { it.timeMs in start..end }
            val facePresence = faceSlice.map { f -> (f.faceCount.coerceAtMost(2) * 0.25 + f.largestFaceArea * 1.5) }
                .average().takeIf { !it.isNaN() } ?: 0.0
            val sceneScore = (cuts / 2.0).coerceAtMost(1.0)
            val pacingScore = (1.0 - abs(wordsPerSecond - 2.9) / 3.2).coerceIn(0.0, 1.0)
            val dialogueScore = ((wordsPerSecond / 3.4).coerceIn(0.0, 1.0) * 0.6 +
                (emphasisRatio / 0.18).coerceIn(0.0, 1.0) * 0.4)
            val laughterScore = if (hasLaughter) 1.0 else reactionProximity(input.audioFrames, start, end)

            val reasons = mutableListOf<DetectionReason>()
            fun add(signal: SignalKind, raw: Double, weight: Float, detail: String) {
                if (raw > 0.05) reasons += DetectionReason(signal, (raw * weight).toFloat(), detail)
            }
            add(SignalKind.DIALOGUE, dialogueScore, cfg.dialogueWeight, "Speech density ${"%.1f".format(wordsPerSecond)} words/s")
            add(SignalKind.AUDIO_PEAK, max(energyLift, peak), cfg.audioPeakWeight, "Loudness ${meanDb.toInt()} dB")
            add(SignalKind.SCENE_CHANGE, sceneScore, cfg.sceneChangeWeight, "$cuts scene cuts")
            add(SignalKind.MOTION, motion, cfg.motionWeight, "Visual motion ${"%.2f".format(motion)}")
            add(SignalKind.FACE, facePresence.coerceIn(0.0, 1.0), cfg.faceWeight, "Faces in frame")
            add(SignalKind.LAUGHTER, laughterScore, cfg.laughterWeight, if (hasLaughter) "Laughter cue in speech" else "Reaction burst")
            add(SignalKind.QUESTION, if (isQuestion) 1.0 else 0.0, cfg.questionWeight, "Question / hook")
            add(SignalKind.COMPLETE_THOUGHT, if (completeThought) 1.0 else 0.0, cfg.completeThoughtWeight, "Self-contained statement")
            add(SignalKind.PACING, pacingScore, cfg.pacingWeight, "Pacing ${"%.1f".format(wordsPerSecond)} wps")

            // Only signals that can actually be measured for this candidate are weighted, so a
            // silent-camera podcast is not punished for having no scene cuts.
            val hasFrames = input.frames.isNotEmpty()
            val weightSum = cfg.dialogueWeight + cfg.audioPeakWeight +
                (if (hasFrames) cfg.sceneChangeWeight + cfg.motionWeight + cfg.faceWeight else 0f) +
                cfg.laughterWeight + cfg.questionWeight + cfg.completeThoughtWeight + cfg.pacingWeight
            val score = (
                dialogueScore * cfg.dialogueWeight +
                    max(energyLift, peak) * cfg.audioPeakWeight +
                    (if (hasFrames) sceneScore * cfg.sceneChangeWeight else 0.0) +
                    (if (hasFrames) motion * cfg.motionWeight else 0.0) +
                    (if (hasFrames) facePresence.coerceIn(0.0, 1.0) * cfg.faceWeight else 0.0) +
                    laughterScore * cfg.laughterWeight +
                    (if (isQuestion) 1.0 else 0.0) * cfg.questionWeight +
                    (if (completeThought) 1.0 else 0.0) * cfg.completeThoughtWeight +
                    pacingScore * cfg.pacingWeight
                ) / weightSum

            val category = categoryFor(text, hasLaughter, isQuestion, emphasisHits, cuts)
            val speechCoverage = group.sumOf { it.endMs - it.startMs }.toDouble() / durationMs
            val confidence = (score * 0.75 + speechCoverage.coerceIn(0.0, 1.0) * 0.25).coerceIn(0.0, 1.0)
            out += CandidateMoment(
                id = UUID.randomUUID().toString(),
                jobId = input.jobId,
                startMs = start,
                endMs = end,
                transcript = text,
                reasons = reasons,
                confidence = confidence.toFloat(),
                engagement = score.coerceIn(0.0, 1.0).toFloat(),
                category = category,
                suggestedOpeningMs = rawStart,
                suggestedEndingMs = rawEnd
            )
        }
        return out
    }

    // ---------------------------------------------------------------- audio-only path

    private fun detectFromAudio(input: DetectionInput, cfg: RankingConfig): List<CandidateMoment> {
        if (input.audioFrames.isEmpty()) return emptyList()
        val windowMs = 25_000L
        val step = 10_000L
        val globalMean = input.audioFrames.map { it.rmsDb }.average()
        val out = ArrayList<CandidateMoment>()
        var t = 0L
        while (t + windowMs <= input.totalDurationMs) {
            val slice = input.audioFrames.filter { it.timeMs in t..(t + windowMs) }
            if (slice.size > 5) {
                val peak = slice.maxOf { it.rmsDb }
                val energyLift = ((peak - globalMean) / 12.0).coerceIn(0.0, 1.0)
                val flux = slice.map { it.spectralFlux }.max()
                val cuts = input.sceneCuts.count { it.timeMs in t..(t + windowMs) }
                val motion = input.frames.filter { it.timeMs in t..(t + windowMs) }
                    .map { it.motion }.average().takeIf { it.isNaN() } ?: 0.0
                val hasFrames = input.frames.isNotEmpty()
                val sceneScore = (cuts / 2.0).coerceAtMost(1.0)
                val weightSum = 0.5 + 0.25 + (if (hasFrames) 0.15 + 0.1 else 0.0)
                val score = (
                    energyLift * 0.5 +
                        (flux / 0.25).coerceIn(0.0, 1.0) * 0.25 +
                        (if (hasFrames) sceneScore * 0.15 + motion * 0.1 else 0.0)
                    ) / weightSum
                if (score >= cfg.balancedThreshold) {
                    out += CandidateMoment(
                        id = UUID.randomUUID().toString(),
                        jobId = input.jobId,
                        startMs = t,
                        endMs = t + windowMs,
                        transcript = "",
                        reasons = listOf(
                            DetectionReason(SignalKind.AUDIO_PEAK, (energyLift * cfg.audioPeakWeight).toFloat(), "Peak loudness ${peak.toInt()} dB"),
                            DetectionReason(SignalKind.MOTION, (motion * cfg.motionWeight).toFloat(), "Visual motion"),
                            DetectionReason(SignalKind.SCENE_CHANGE, (cuts / 2.0 * cfg.sceneChangeWeight).toFloat().coerceAtMost(1f), "$cuts scene cuts")
                        ),
                        confidence = score.toFloat(),
                        engagement = score.toFloat(),
                        category = DetectionCategory.ACTION,
                        suggestedOpeningMs = t,
                        suggestedEndingMs = t + windowMs
                    )
                }
            }
            t += step
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun groupSentences(segments: List<TranscriptSegment>, maxMs: Long): List<List<TranscriptSegment>> {
        val groups = ArrayList<MutableList<TranscriptSegment>>()
        var current = ArrayList<TranscriptSegment>()
        var currentStart = 0L
        for (segment in segments) {
            val gap = if (current.isEmpty()) 0 else segment.startMs - current.last().endMs
            val wouldBe = segment.endMs - currentStart
            if (current.isNotEmpty() && (gap > 900 || wouldBe > maxMs * 0.75)) {
                groups += current
                current = ArrayList()
            }
            if (current.isEmpty()) currentStart = segment.startMs
            current.add(segment)
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /** Extends a sentence group so clips start and end on natural boundaries. */
    private fun expandForContext(
        segments: List<TranscriptSegment>,
        group: List<TranscriptSegment>,
        rawStart: Long,
        rawEnd: Long,
        cfg: RankingConfig
    ): Pair<Long, Long> {
        var start = rawStart
        var end = rawEnd
        val index = segments.indexOfFirst { it.startMs == rawStart }
        // Add one preceding sentence when it flows directly into this one.
        if (index > 0) {
            val previous = segments[index - 1]
            if (rawStart - previous.endMs <= 700 && (end - previous.startMs) <= cfg.maxClipMs) {
                start = previous.startMs
            }
        }
        var cursor = segments.indexOfLast { it.startMs == group.last().startMs } + 1
        while (cursor in segments.indices && segments[cursor].startMs - end <= 900) {
            if (segments[cursor].endMs - start > cfg.maxClipMs) break
            end = segments[cursor].endMs
            cursor++
        }
        // Guarantee the minimum clip length without cutting mid-sentence where possible.
        if (end - start < cfg.minClipMs) {
            end = min(start + cfg.minClipMs, start + cfg.maxClipMs)
        }
        if (end - start > cfg.maxClipMs) end = start + cfg.maxClipMs
        return start to end
    }

    /** Merges overlapping neighbours while preserving the stronger score. */
    private fun merge(candidates: List<CandidateMoment>, cfg: RankingConfig): List<CandidateMoment> {
        if (candidates.size <= 1) return candidates
        val sorted = candidates.sortedBy { it.startMs }
        val merged = ArrayList<CandidateMoment>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            val gap = next.startMs - current.endMs
            val overlap = (min(current.endMs, next.endMs) - max(current.startMs, next.startMs)).toDouble()
            val overlapRatio = overlap / min(current.endMs - current.startMs, next.endMs - next.startMs).coerceAtLeast(1)
            if (gap <= cfg.mergeGapMs || overlapRatio >= cfg.maxOverlapRatio) {
                val keepNext = next.engagement > current.engagement
                val start = min(current.startMs, next.startMs)
                val end = min(max(current.endMs, next.endMs), start + cfg.maxClipMs)
                current = (if (keepNext) next else current).copy(
                    startMs = start,
                    endMs = end,
                    transcript = if (keepNext) next.transcript else current.transcript,
                    reasons = (current.reasons + next.reasons)
                        .groupBy { it.signal }
                        .map { (signal, list) -> list.maxBy { it.weight } }
                        .sortedByDescending { it.weight },
                    mergedCount = current.mergedCount + next.mergedCount,
                    suggestedOpeningMs = min(current.suggestedOpeningMs, next.suggestedOpeningMs),
                    suggestedEndingMs = max(current.suggestedEndingMs, next.suggestedEndingMs)
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    /** Drops candidates that mostly repeat an already accepted higher ranked moment. */
    private fun dedupe(candidates: List<CandidateMoment>, cfg: RankingConfig): List<CandidateMoment> {
        val accepted = ArrayList<CandidateMoment>()
        for (candidate in candidates.sortedByDescending { it.engagement }) {
            val duplicate = accepted.any { existing ->
                val overlap = (min(existing.endMs, candidate.endMs) - max(existing.startMs, candidate.startMs))
                    .coerceAtLeast(0)
                val shorter = min(existing.endMs - existing.startMs, candidate.endMs - candidate.startMs)
                    .coerceAtLeast(1)
                overlap.toDouble() / shorter >= cfg.maxOverlapRatio
            }
            if (!duplicate) accepted += candidate
        }
        return accepted
    }

    private fun categoryFor(
        text: String,
        hasLaughter: Boolean,
        isQuestion: Boolean,
        emphasisHits: Int,
        cuts: Int
    ): DetectionCategory {
        val lowered = text.lowercase()
        return when {
            hasLaughter || lowered.contains("funny") || lowered.contains("joke") -> DetectionCategory.FUNNY
            lowered.contains("game") || lowered.contains("level") || lowered.contains("boss") -> DetectionCategory.GAMING
            lowered.contains("because") || lowered.contains("learn") || lowered.contains("explain") ||
                lowered.contains("samjhao") || lowered.contains("matlab") -> DetectionCategory.EDUCATIONAL
            lowered.contains("feel") || lowered.contains("scared") || lowered.contains("love") ||
                lowered.contains("cried") || lowered.contains("emotional") -> DetectionCategory.EMOTIONAL
            lowered.contains("story") || lowered.contains("happened") || lowered.contains("once") -> DetectionCategory.STORY
            isQuestion -> DetectionCategory.REACTION
            emphasisHits >= 2 -> DetectionCategory.EMPHASIS
            cuts >= 2 -> DetectionCategory.ACTION
            else -> DetectionCategory.INSIGHT
        }
    }

    private fun peakStrengthIndex(frames: List<AudioFeatureFrame>): PeakIndex {
        if (frames.isEmpty()) return PeakIndex(emptyList(), frames)
        val sorted = frames.map { it.rmsDb }.sorted()
        val p75 = sorted[(sorted.size * 0.75).toInt().coerceIn(0, sorted.size - 1)]
        val peaks = ArrayList<Pair<Long, Float>>()
        for (i in 1 until frames.size - 1) {
            val cur = frames[i]
            if (cur.rmsDb < p75 + 2f) continue
            if (cur.rmsDb > frames[i - 1].rmsDb && cur.rmsDb >= frames[i + 1].rmsDb) {
                val localMean = (0..10).mapNotNull { k -> frames.getOrNull(i - k)?.rmsDb }.average()
                val strength = ((cur.rmsDb - localMean) / 12.0).coerceIn(0.0, 1.0).toFloat()
                peaks.add(cur.timeMs to strength)
            }
        }
        return PeakIndex(peaks, frames)
    }

    private class PeakIndex(val peaks: List<Pair<Long, Float>>, val frames: List<AudioFeatureFrame>) {
        fun fractionIn(startMs: Long, endMs: Long): Double {
            if (peaks.isEmpty()) return 0.0
            val inside = peaks.filter { it.first in startMs..endMs }
            return if (inside.isEmpty()) {
                val nearest = peaks.minByOrNull { abs(it.first - (startMs + endMs) / 2) } ?: return 0.0
                if (abs(nearest.first - (startMs + endMs) / 2) < 4_000) nearest.second * 0.5 else 0.0
            } else {
                (inside.maxOf { it.second }).toDouble()
            }
        }
    }

    /** Whether a reaction burst (repeated loud peaks) sits inside the window. */
    private fun reactionProximity(frames: List<AudioFeatureFrame>, startMs: Long, endMs: Long): Double {
        if (frames.size < 10) return 0.0
        val slice = frames.filter { it.timeMs in startMs..endMs }
        if (slice.size < 8) return 0.0
        val mean = slice.map { it.rmsDb }.average()
        val loud = slice.count { it.rmsDb > mean + 5 }
        val fluxSpikes = slice.count { it.spectralFlux > 0.06 }
        return ((loud / 6.0).coerceAtMost(1.0) * 0.6 + (fluxSpikes / 8.0).coerceAtMost(1.0) * 0.4)
    }
}
