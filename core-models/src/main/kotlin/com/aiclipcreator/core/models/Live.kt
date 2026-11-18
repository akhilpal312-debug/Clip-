package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

@Serializable
data class LiveStream(
    val id: String,
    val url: String,
    val title: String,
    val sourceType: LiveSourceType,
    val status: LiveStatus,
    val lastCheckedAt: Long,
    val error: String? = null,
    val isLive: Boolean = false,
    val viewerCount: Long? = null,
    val videoTitle: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val recordingPath: String? = null,
    val clipsFound: Int = 0
)

@Serializable
data class LiveValidation(
    val valid: Boolean,
    val sourceType: LiveSourceType?,
    val message: String,
    val resolvedUrl: String? = null,
    val isLive: Boolean = false,
    val title: String? = null
)

@Serializable
data class LiveCandidate(
    val streamId: String,
    val startMs: Long,
    val endMs: Long,
    val reason: String,
    val score: Float
)
