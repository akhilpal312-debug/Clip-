package com.aiclipcreator.core.database

import com.aiclipcreator.core.models.AspectRatio
import com.aiclipcreator.core.models.CandidateMoment
import com.aiclipcreator.core.models.CaptionCue
import com.aiclipcreator.core.models.CaptionStyle
import com.aiclipcreator.core.models.Clip
import com.aiclipcreator.core.models.ClipMode
import com.aiclipcreator.core.models.ClipQuality
import com.aiclipcreator.core.models.DetectionCategory
import com.aiclipcreator.core.models.DetectionReason
import com.aiclipcreator.core.models.DurationMode
import com.aiclipcreator.core.models.ExportRecord
import com.aiclipcreator.core.models.JobCheckpoint
import com.aiclipcreator.core.models.JobState
import com.aiclipcreator.core.models.Language
import com.aiclipcreator.core.models.LiveSourceType
import com.aiclipcreator.core.models.LiveStatus
import com.aiclipcreator.core.models.LiveStream
import com.aiclipcreator.core.models.ProcessingJob
import com.aiclipcreator.core.models.ProcessingStage
import com.aiclipcreator.core.models.ProductionProfile
import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.models.TranscriptWord
import com.aiclipcreator.core.models.VideoMetadata
import com.aiclipcreator.core.models.VideoSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal val dbJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private inline fun <reified T> decodeOr(default: T, raw: String): T = try {
    dbJson.decodeFromString<T>(raw)
} catch (t: Throwable) {
    default
}

private fun parseEnum(name: String, fallback: JobState) =
    runCatching { JobState.valueOf(name) }.getOrDefault(fallback)

fun VideoEntity.toDomain() = VideoSource(
    id = id,
    uri = uri,
    displayName = displayName,
    metadata = VideoMetadata(
        durationMs = durationMs,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        bitrate = bitrate,
        frameRate = frameRate,
        mimeType = mimeType,
        hasAudio = hasAudio,
        audioSampleRate = audioSampleRate,
        audioChannels = audioChannels,
        sizeBytes = sizeBytes
    ),
    importedAt = importedAt,
    thumbnailUri = thumbnailUri,
    projectFolder = projectFolder
)

fun VideoSource.toEntity() = VideoEntity(
    id = id,
    uri = uri,
    displayName = displayName,
    durationMs = metadata.durationMs,
    width = metadata.width,
    height = metadata.height,
    rotationDegrees = metadata.rotationDegrees,
    bitrate = metadata.bitrate,
    frameRate = metadata.frameRate,
    mimeType = metadata.mimeType,
    hasAudio = metadata.hasAudio,
    audioSampleRate = metadata.audioSampleRate,
    audioChannels = metadata.audioChannels,
    sizeBytes = metadata.sizeBytes,
    importedAt = importedAt,
    thumbnailUri = thumbnailUri,
    projectFolder = projectFolder
)

fun JobEntity.toDomain() = ProcessingJob(
    id = id,
    videoId = videoId,
    videoName = videoName,
    sourceUri = sourceUri,
    state = parseEnum(state, JobState.QUEUED),
    stage = runCatching { ProcessingStage.valueOf(stage) }.getOrDefault(ProcessingStage.IDLE),
    progress = progress,
    priority = priority,
    mode = runCatching { ClipMode.valueOf(mode) }.getOrDefault(ClipMode.BOTH),
    profile = runCatching { ProductionProfile.valueOf(profile) }.getOrDefault(ProductionProfile.BALANCED),
    language = runCatching { Language.valueOf(language) }.getOrDefault(Language.AUTO),
    createdAt = createdAt,
    updatedAt = updatedAt,
    durationMs = durationMs,
    error = error,
    clipMode = runCatching { DurationMode.valueOf(durationMode) }.getOrDefault(DurationMode.AI_DECIDED),
    minClipMs = minClipMs,
    maxClipMs = maxClipMs,
    aspectRatio = runCatching { AspectRatio.valueOf(aspectRatio) }.getOrDefault(AspectRatio.PORTRAIT_9_16),
    liveSessionId = liveSessionId,
    checkpointStage = checkpointStage,
    lastProcessedMs = lastProcessedMs
)

fun ProcessingJob.toEntity() = JobEntity(
    id = id,
    videoId = videoId,
    videoName = videoName,
    sourceUri = sourceUri,
    state = state.name,
    stage = stage.name,
    progress = progress,
    priority = priority,
    mode = mode.name,
    profile = profile.name,
    language = language.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    durationMs = durationMs,
    error = error,
    durationMode = clipMode.name,
    minClipMs = minClipMs,
    maxClipMs = maxClipMs,
    aspectRatio = aspectRatio.name,
    liveSessionId = liveSessionId,
    checkpointStage = checkpointStage,
    lastProcessedMs = lastProcessedMs
)

fun CheckpointEntity.toDomain() = JobCheckpoint(
    jobId = jobId,
    stage = runCatching { ProcessingStage.valueOf(stage) }.getOrDefault(ProcessingStage.IDLE),
    lastProcessedMs = lastProcessedMs,
    payload = payload,
    updatedAt = updatedAt
)

fun JobCheckpoint.toEntity() = CheckpointEntity(
    jobId = jobId,
    stage = stage.name,
    lastProcessedMs = lastProcessedMs,
    payload = payload,
    updatedAt = updatedAt
)

fun TranscriptEntity.toDomain() = TranscriptSegment(
    startMs = startMs,
    endMs = endMs,
    text = text,
    language = language,
    words = decodeOr(emptyList<TranscriptWord>(), wordsJson),
    speaker = speaker
)

fun TranscriptSegment.toEntity(jobId: String) = TranscriptEntity(
    jobId = jobId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    language = language,
    speaker = speaker,
    wordsJson = dbJson.encodeToString(ListSerializer(TranscriptWord.serializer()), words)
)

fun CandidateEntity.toDomain() = CandidateMoment(
    id = id,
    jobId = jobId,
    startMs = startMs,
    endMs = endMs,
    transcript = transcript,
    reasons = decodeOr(emptyList<DetectionReason>(), reasonsJson),
    confidence = confidence,
    engagement = engagement,
    category = runCatching { DetectionCategory.valueOf(category) }.getOrDefault(DetectionCategory.GENERAL),
    suggestedOpeningMs = openingMs,
    suggestedEndingMs = endingMs,
    quality = runCatching { ClipQuality.valueOf(quality) }.getOrDefault(ClipQuality.BALANCED),
    mergedCount = mergedCount
)

fun CandidateMoment.toEntity() = CandidateEntity(
    id = id,
    jobId = jobId,
    startMs = startMs,
    endMs = endMs,
    transcript = transcript,
    reasonsJson = dbJson.encodeToString(ListSerializer(DetectionReason.serializer()), reasons),
    confidence = confidence,
    engagement = engagement,
    category = category.name,
    openingMs = suggestedOpeningMs,
    endingMs = suggestedEndingMs,
    quality = quality.name,
    mergedCount = mergedCount
)

fun ClipEntity.toDomain() = Clip(
    id = id,
    jobId = jobId,
    candidateId = candidateId,
    title = title,
    description = description,
    hashtags = decodeOr(emptyList<String>(), hashtagsJson),
    startMs = startMs,
    endMs = endMs,
    quality = runCatching { ClipQuality.valueOf(quality) }.getOrDefault(ClipQuality.BALANCED),
    aspectRatio = runCatching { AspectRatio.valueOf(aspectRatio) }.getOrDefault(AspectRatio.PORTRAIT_9_16),
    localFileUri = localFileUri,
    galleryUri = galleryUri,
    srt = srt,
    captionStyle = decodeOr(CaptionStyle(), captionStyleJson),
    createdAt = createdAt,
    sourceVideoId = sourceVideoId,
    sourceName = sourceName,
    exportedAt = exportedAt,
    transcript = transcript,
    isLive = isLive,
    textSource = textSource
)

fun Clip.toEntity() = ClipEntity(
    id = id,
    jobId = jobId,
    candidateId = candidateId,
    title = title,
    description = description,
    hashtagsJson = dbJson.encodeToString(ListSerializer(String.serializer()), hashtags),
    startMs = startMs,
    endMs = endMs,
    quality = quality.name,
    aspectRatio = aspectRatio.name,
    localFileUri = localFileUri,
    galleryUri = galleryUri,
    srt = srt,
    captionStyleJson = dbJson.encodeToString(CaptionStyle.serializer(), captionStyle),
    createdAt = createdAt,
    sourceVideoId = sourceVideoId,
    sourceName = sourceName,
    exportedAt = exportedAt,
    transcript = transcript,
    isLive = isLive,
    textSource = textSource
)

fun ExportEntity.toDomain() = ExportRecord(
    id = id,
    clipId = clipId,
    title = title,
    uri = uri,
    folder = folder,
    createdAt = createdAt,
    durationMs = durationMs
)

fun ExportRecord.toEntity() = ExportEntity(
    id = id,
    clipId = clipId,
    title = title,
    uri = uri,
    folder = folder,
    createdAt = createdAt,
    durationMs = durationMs
)

fun LiveEntity.toDomain() = LiveStream(
    id = id,
    url = url,
    title = title,
    sourceType = runCatching { LiveSourceType.valueOf(sourceType) }.getOrDefault(LiveSourceType.HLS),
    status = runCatching { LiveStatus.valueOf(status) }.getOrDefault(LiveStatus.IDLE),
    lastCheckedAt = lastCheckedAt,
    error = error,
    isLive = isLive,
    viewerCount = viewerCount,
    videoTitle = videoTitle,
    addedAt = addedAt,
    recordingPath = recordingPath,
    clipsFound = clipsFound
)

fun LiveStream.toEntity() = LiveEntity(
    id = id,
    url = url,
    title = title,
    sourceType = sourceType.name,
    status = status.name,
    lastCheckedAt = lastCheckedAt,
    error = error,
    isLive = isLive,
    viewerCount = viewerCount,
    videoTitle = videoTitle,
    addedAt = addedAt,
    recordingPath = recordingPath,
    clipsFound = clipsFound
)

@Suppress("unused")
internal fun parseCaptionCues(raw: String): List<CaptionCue> = decodeOr(emptyList(), raw)
