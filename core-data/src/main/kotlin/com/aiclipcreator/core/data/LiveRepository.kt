package com.aiclipcreator.core.data

import android.content.Context
import com.aiclipcreator.core.common.Formats
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.database.LiveDao
import com.aiclipcreator.core.database.toDomain
import com.aiclipcreator.core.database.toEntity
import com.aiclipcreator.core.models.LiveSourceType
import com.aiclipcreator.core.models.LiveStatus
import com.aiclipcreator.core.models.LiveStream
import com.aiclipcreator.core.models.LiveValidation
import com.aiclipcreator.core.network.YouTubeApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Live sources are limited to what Android can legitimately consume:
 * HLS playlists and progressive HTTP video that the user is authorised to record.
 * YouTube watch URLs are metadata-only and are explicitly reported as unsupported for recording.
 */
class LiveRepository(
    private val context: Context,
    private val dao: LiveDao,
    private val youtube: YouTubeApiClient
) {

    fun observeAll(): Flow<List<LiveStream>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun byId(id: String): LiveStream? = withContext(Dispatchers.IO) { dao.byId(id)?.toDomain() }

    suspend fun add(url: String, title: String, sourceType: LiveSourceType, apiKey: String = ""): LiveStream =
        withContext(Dispatchers.IO) {
            val validation = validate(url, apiKey)
            val stream = LiveStream(
                id = UUID.randomUUID().toString(),
                url = url.trim(),
                title = title.ifBlank { validation.title ?: url.trim() },
                sourceType = validation.sourceType ?: sourceType,
                status = when {
                    !validation.valid -> LiveStatus.UNSUPPORTED
                    validation.isLive -> LiveStatus.LIVE
                    else -> LiveStatus.OFFLINE
                },
                lastCheckedAt = System.currentTimeMillis(),
                error = validation.message.takeIf { !validation.valid },
                isLive = validation.isLive,
                videoTitle = validation.title,
                addedAt = System.currentTimeMillis()
            )
            dao.upsert(stream.toEntity())
            stream
        }

    suspend fun update(stream: LiveStream) = withContext(Dispatchers.IO) { dao.upsert(stream.toEntity()) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.delete(id) }

    suspend fun refresh(stream: LiveStream, apiKey: String = ""): LiveStream = withContext(Dispatchers.IO) {
        val validation = validate(stream.url, apiKey)
        val updated = stream.copy(
            status = when {
                !validation.valid -> LiveStatus.UNSUPPORTED
                validation.isLive -> LiveStatus.LIVE
                else -> LiveStatus.OFFLINE
            },
            lastCheckedAt = System.currentTimeMillis(),
            error = validation.message.takeIf { !validation.valid },
            isLive = validation.isLive,
            videoTitle = validation.title ?: stream.videoTitle,
            sourceType = validation.sourceType ?: stream.sourceType
        )
        dao.upsert(updated.toEntity())
        updated
    }

    suspend fun validate(url: String, apiKey: String = ""): LiveValidation = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return@withContext LiveValidation(false, null, "Enter a stream URL")
        val youtubeId = youtube.extractVideoId(trimmed)
        if (youtubeId != null && trimmed.contains("youtu")) {
            if (apiKey.isBlank()) {
                return@withContext LiveValidation(
                    valid = false,
                    sourceType = LiveSourceType.YOUTUBE,
                    message = "YouTube watch URLs are not raw video feeds. Add a YouTube Data API key in Settings " +
                        "to read live metadata, or paste the creator's HLS/RTMP-playback URL."
                )
            }
            val info = youtube.liveDetails(youtubeId, apiKey).getOrElse {
                return@withContext LiveValidation(
                    false,
                    LiveSourceType.YOUTUBE,
                    "YouTube lookup failed: ${it.message}"
                )
            }
            return@withContext LiveValidation(
                valid = false,
                sourceType = LiveSourceType.YOUTUBE,
                message = if (info.isLive) {
                    "“${info.title}” is live (${info.concurrentViewers ?: 0} viewers). Metadata only: recording " +
                        "YouTube playback is not supported. Use an authorised HLS URL from your own encoder."
                } else {
                    "Video is ${info.liveBroadcastContent ?: "not live"}. Metadata only: recording is not supported."
                },
                isLive = info.isLive,
                title = info.title
            )
        }
        val lower = trimmed.lowercase()
        val type = when {
            lower.contains(".m3u8") -> LiveSourceType.HLS
            lower.contains(".mp4") || lower.contains(".ts") || lower.contains(".m4s") -> LiveSourceType.PROGRESSIVE_HTTP
            lower.startsWith("rtmp") -> null
            else -> LiveSourceType.PROGRESSIVE_HTTP
        }
        if (type == null) {
            return@withContext LiveValidation(
                false,
                null,
                "RTMP ingestion is not supported on device. Publish your encoder to HLS and paste the .m3u8 URL."
            )
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return@withContext LiveValidation(false, null, "Only http(s) HLS or progressive video URLs are supported")
        }
        val head = withTimeoutOrNull(12_000) { youtube.headCheck(trimmed) }?.getOrNull()
        if (head == null) {
            return@withContext LiveValidation(false, type, "Could not reach the stream URL (network error)")
        }
        val (code, contentType) = head
        val looksLikePlaylist = contentType?.contains("mpegurl", true) == true
        LiveValidation(
            valid = code in 200..299,
            sourceType = if (looksLikePlaylist) LiveSourceType.HLS else type,
            message = when {
                code in 200..299 ->
                    "Stream reachable (HTTP $code${contentType?.let { ", $it" } ?: ""})"
                else -> "Stream responded with HTTP $code"
            },
            resolvedUrl = trimmed,
            isLive = code in 200..299
        )
    }

    /**
     * Records a live HTTP/HLS source into a single MP4 that the analysis pipeline can slice.
     * Returns the file path and the recorded duration when the recorder stops.
     */
    suspend fun recordWindow(stream: LiveStream, seconds: Int, destination: java.io.File): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                Logx.i("Recording ${seconds}s of ${stream.url} to ${destination.name}")
                val recorder = LiveRecorder(context)
                recorder.record(stream.url, destination, seconds * 1000L)
            }
        }

    fun humanSize(bytes: Long): String = Formats.bytes(bytes)
}
