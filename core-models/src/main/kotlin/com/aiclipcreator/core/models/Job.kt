package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

@Serializable
data class ProcessingJob(
    val id: String,
    val videoId: String,
    val videoName: String,
    val sourceUri: String,
    val state: JobState,
    val stage: ProcessingStage,
    val progress: Float,
    val priority: Int,
    val mode: ClipMode,
    val profile: ProductionProfile,
    val language: Language,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long = 0L,
    val error: String? = null,
    val clipMode: DurationMode = DurationMode.AI_DECIDED,
    val minClipMs: Long = 12_000L,
    val maxClipMs: Long = 90_000L,
    val aspectRatio: AspectRatio = AspectRatio.PORTRAIT_9_16,
    val liveSessionId: String? = null,
    val checkpointStage: String? = null,
    val lastProcessedMs: Long = 0L
) {
    val isActive: Boolean
        get() = state == JobState.RUNNING || state == JobState.QUEUED || state == JobState.PAUSED
}

@Serializable
data class JobCheckpoint(
    val jobId: String,
    val stage: ProcessingStage,
    val lastProcessedMs: Long,
    val payload: String,
    val updatedAt: Long
)

@Serializable
data class QueueSummary(
    val total: Int,
    val active: Int,
    val completed: Int,
    val failed: Int,
    val overallProgress: Float
)
