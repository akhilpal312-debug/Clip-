package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

/** Metadata read from the media container; never requires decoding the whole file. */
@Serializable
data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val bitrate: Int = 0,
    val frameRate: Float = 0f,
    val mimeType: String = "video/mp4",
    val hasAudio: Boolean = true,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val sizeBytes: Long = 0L
) {
    /** Width/height after rotation is applied. */
    val displayWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val displayHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

@Serializable
data class VideoSource(
    val id: String,
    val uri: String,
    val displayName: String,
    val metadata: VideoMetadata,
    val importedAt: Long,
    val thumbnailUri: String? = null,
    val projectFolder: String = "Projects"
)

@Serializable
data class ImportedFile(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long
)
