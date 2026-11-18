package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

@Serializable
data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<TranscriptWord> = emptyList(),
    val speaker: String? = null
)

@Serializable
data class CaptionStyle(
    val fontFamily: String = "SANS_SERIF",
    val fontSizeSp: Int = 18,
    val bold: Boolean = true,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val outlineColor: Int = 0xFF000000.toInt(),
    val backgroundColor: Int = 0x66000000,
    val uppercase: Boolean = false,
    val verticalPosition: Float = 0.82f
)

@Serializable
data class ClipText(
    val title: String,
    val description: String,
    val hashtags: List<String> = emptyList(),
    val source: String = "extractive"
)

@Serializable
data class Clip(
    val id: String,
    val jobId: String,
    val candidateId: String,
    val title: String,
    val description: String,
    val hashtags: List<String> = emptyList(),
    val startMs: Long,
    val endMs: Long,
    val quality: ClipQuality,
    val aspectRatio: AspectRatio,
    val localFileUri: String,
    val galleryUri: String? = null,
    val srt: String = "",
    val captionStyle: CaptionStyle = CaptionStyle(),
    val createdAt: Long,
    val sourceVideoId: String,
    val sourceName: String,
    val exportedAt: Long? = null,
    val transcript: String = "",
    val isLive: Boolean = false,
    val textSource: String = "extractive"
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
data class ExportRecord(
    val id: String,
    val clipId: String,
    val title: String,
    val uri: String,
    val folder: String,
    val createdAt: Long,
    val durationMs: Long
)

@Serializable
data class GalleryClip(
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val durationMs: Long
)

@Serializable
data class RenderResult(
    val outputUri: String,
    val durationMs: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val reEncoded: Boolean
)
