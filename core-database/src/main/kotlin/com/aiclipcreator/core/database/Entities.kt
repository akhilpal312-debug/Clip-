package com.aiclipcreator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val bitrate: Int,
    val frameRate: Float,
    val mimeType: String,
    val hasAudio: Boolean,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val sizeBytes: Long,
    val importedAt: Long,
    val thumbnailUri: String?,
    val projectFolder: String
)

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val videoId: String,
    val videoName: String,
    val sourceUri: String,
    val state: String,
    val stage: String,
    val progress: Float,
    val priority: Int,
    val mode: String,
    val profile: String,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    val error: String?,
    val durationMode: String,
    val minClipMs: Long,
    val maxClipMs: Long,
    val aspectRatio: String,
    val liveSessionId: String?,
    val checkpointStage: String?,
    val lastProcessedMs: Long
)

@Entity(tableName = "checkpoints")
data class CheckpointEntity(
    @PrimaryKey val jobId: String,
    val stage: String,
    val lastProcessedMs: Long,
    val payload: String,
    val updatedAt: Long
)

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val jobId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String,
    val speaker: String?,
    val wordsJson: String
)

@Entity(tableName = "candidates")
data class CandidateEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val startMs: Long,
    val endMs: Long,
    val transcript: String,
    val reasonsJson: String,
    val confidence: Float,
    val engagement: Float,
    val category: String,
    val openingMs: Long,
    val endingMs: Long,
    val quality: String,
    val mergedCount: Int
)

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val candidateId: String,
    val title: String,
    val description: String,
    val hashtagsJson: String,
    val startMs: Long,
    val endMs: Long,
    val quality: String,
    val aspectRatio: String,
    val localFileUri: String,
    val galleryUri: String?,
    val srt: String,
    val captionStyleJson: String,
    val createdAt: Long,
    val sourceVideoId: String,
    val sourceName: String,
    val exportedAt: Long?,
    val transcript: String,
    val isLive: Boolean,
    val textSource: String
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val state: String,
    val progress: Float,
    val downloadedBytes: Long,
    val localPath: String?,
    val error: String?,
    val selfTestPassed: Boolean,
    val updatedAt: Long
)

@Entity(tableName = "exports")
data class ExportEntity(
    @PrimaryKey val id: String,
    val clipId: String,
    val title: String,
    val uri: String,
    val folder: String,
    val createdAt: Long,
    val durationMs: Long
)

@Entity(tableName = "live_streams")
data class LiveEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val sourceType: String,
    val status: String,
    val lastCheckedAt: Long,
    val error: String?,
    val isLive: Boolean,
    val viewerCount: Long?,
    val videoTitle: String?,
    val addedAt: Long,
    val recordingPath: String?,
    val clipsFound: Int
)
