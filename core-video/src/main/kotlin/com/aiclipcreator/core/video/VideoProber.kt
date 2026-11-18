package com.aiclipcreator.core.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Reads container/track metadata without decoding video. */
class VideoProber(private val context: Context) {

    suspend fun probe(uri: Uri, knownSize: Long = 0L): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.let { frames ->
                        if (duration > 0) frames.toFloat() / (duration / 1000f) else null
                    } ?: 0f
                val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
                require(duration > 0) { "Unable to read video duration (unsupported or corrupt container)" }
                require(width > 0 && height > 0) { "Video track has no dimensions" }

                var audioRate = 0
                var audioChannels = 0
                var hasAudio = false
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    for (i in 0 until extractor.trackCount) {
                        val f = extractor.getTrackFormat(i)
                        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                            hasAudio = true
                            audioRate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
                            audioChannels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
                            break
                        }
                    }
                } finally {
                    runCatching { extractor.release() }
                }

                VideoMetadata(
                    durationMs = duration,
                    width = width,
                    height = height,
                    rotationDegrees = ((rotation % 360) + 360) % 360,
                    bitrate = bitrate,
                    frameRate = frameRate,
                    mimeType = mime,
                    hasAudio = hasAudio,
                    audioSampleRate = audioRate,
                    audioChannels = audioChannels,
                    sizeBytes = knownSize
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.onFailure { Logx.e("Probe failed for $uri", it) }
    }

    /** Grabs a representative frame and stores it as a JPEG thumbnail. */
    suspend fun thumbnail(uri: Uri, target: File, atMs: Long? = null, maxWidth: Int = 640): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frameTimeUs = (atMs ?: (duration / 4).coerceAtLeast(0L)) * 1000
                    var bitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap == null && duration > 0) {
                        bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    requireNotNull(bitmap) { "Unable to decode a thumbnail frame" }
                    val scale = (maxWidth.toFloat() / bitmap.width).coerceAtMost(1f)
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    } else bitmap
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    target
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

    suspend fun frameAt(uri: Uri, atMs: Long, maxWidth: Int = 720): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val bmp = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null && bmp.width > maxWidth) {
                    val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * maxWidth / bmp.width).coerceAtLeast(1), true)
                    bmp.recycle()
                    scaled
                } else bmp
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }
}
