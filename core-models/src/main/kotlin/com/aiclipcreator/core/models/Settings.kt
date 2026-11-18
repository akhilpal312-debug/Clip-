package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

/** Weighting for the multi-signal clip detector. All weights are relative. */
@Serializable
data class RankingConfig(
    val dialogueWeight: Float = 1.0f,
    val audioPeakWeight: Float = 0.55f,
    val sceneChangeWeight: Float = 0.35f,
    val motionWeight: Float = 0.40f,
    val faceWeight: Float = 0.35f,
    val laughterWeight: Float = 0.80f,
    val questionWeight: Float = 0.45f,
    val completeThoughtWeight: Float = 0.60f,
    val pacingWeight: Float = 0.30f,
    val bestThreshold: Float = 0.62f,
    val balancedThreshold: Float = 0.42f,
    val maxOverlapRatio: Float = 0.35f,
    val mergeGapMs: Long = 2_500L,
    val minClipMs: Long = 12_000L,
    val maxClipMs: Long = 90_000L,
    val maxClipsBest: Int = 0,
    val maxClipsBalanced: Int = 0
)

@Serializable
data class UserSettings(
    val profile: ProductionProfile = ProductionProfile.BALANCED,
    val language: Language = Language.AUTO,
    val clipMode: ClipMode = ClipMode.BOTH,
    val clipCountMode: ClipCountMode = ClipCountMode.ALL_QUALIFIED,
    val customClipCount: Int = 6,
    val durationMode: DurationMode = DurationMode.AI_DECIDED,
    val minClipMs: Long = 12_000L,
    val maxClipMs: Long = 90_000L,
    val aspectRatio: AspectRatio = AspectRatio.PORTRAIT_9_16,
    val burnCaptions: Boolean = true,
    val normalizeAudio: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle(),
    val ranking: RankingConfig = RankingConfig(),
    val modelFamiliesEnabled: Set<String> = setOf("speech", "vision", "audio", "language"),
    val autoExportToGallery: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    val youtubeApiKey: String = "",
    val thermalThrottle: Boolean = true,
    val liveSegmentSeconds: Int = 60
)

@Serializable
data class StorageReport(
    val freeBytes: Long,
    val totalBytes: Long,
    val modelsBytes: Long,
    val workingBytes: Long,
    val exportsBytes: Long
)

@Serializable
data class DeviceCapabilities(
    val model: String,
    val manufacturer: String,
    val sdkInt: Int,
    val cores: Int,
    val ramMb: Long,
    val freeRamMb: Long,
    val profile: ProductionProfile,
    val supportsNpu: Boolean,
    val abi: String,
    val thermalStatus: Int = 0
)
