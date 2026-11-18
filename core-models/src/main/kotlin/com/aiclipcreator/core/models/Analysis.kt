package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val probability: Float = 0f
)

@Serializable
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String = "en",
    val words: List<TranscriptWord> = emptyList(),
    val speaker: String? = null,
    val noSpeechProbability: Float = 0f
)

@Serializable
data class SceneCut(val timeMs: Long, val score: Float)

@Serializable
data class AudioFeatureFrame(
    val timeMs: Long,
    val rmsDb: Float,
    val spectralFlux: Float,
    val speechProbability: Float
)

@Serializable
data class FrameAnalysis(
    val timeMs: Long,
    val brightness: Float,
    val sharpness: Float,
    val motion: Float,
    val faceCount: Int,
    val largestFaceArea: Float,
    val faceCenterX: Float,
    val faceCenterY: Float,
    val labels: List<String> = emptyList(),
    val activity: Float = 0f
)

@Serializable
data class DetectionReason(
    val signal: SignalKind,
    val weight: Float,
    val detail: String
)

@Serializable
data class CandidateMoment(
    val id: String,
    val jobId: String,
    val startMs: Long,
    val endMs: Long,
    val transcript: String,
    val reasons: List<DetectionReason>,
    val confidence: Float,
    val engagement: Float,
    val category: DetectionCategory,
    val suggestedOpeningMs: Long = 0L,
    val suggestedEndingMs: Long = 0L,
    val quality: ClipQuality = ClipQuality.BALANCED,
    val mergedCount: Int = 1
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
data class AnalysisResult(
    val jobId: String,
    val transcript: List<TranscriptSegment>,
    val candidates: List<CandidateMoment>,
    val language: String,
    val speechCoverage: Float,
    val analyzedMs: Long
)
