package com.aiclipcreator.core.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.aiclipcreator.core.common.Logx
import java.io.File
import java.nio.ByteBuffer

/**
 * Records a live HTTP/HLS stream into an MP4 by copying the elementary streams.
 *
 * Uses the platform extractor (which supports HLS and progressive HTTP) and the muxer, so the
 * recording keeps the original codecs without a re-encode. The recursive HLS playlists are
 * extended automatically by MediaExtractor for as long as the source stays live.
 */
class LiveRecorder(private val context: Context) {

    fun record(url: String, destination: File, durationMs: Long): Long {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(url, emptyMap())
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                val track = muxer.addTrack(format)
                trackMap[i] = track
            }
            check(trackMap.isNotEmpty()) { "Stream exposes no audio or video tracks" }
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val startedAt = System.currentTimeMillis()
            var firstPts = -1L
            while (System.currentTimeMillis() - startedAt < durationMs) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    Thread.sleep(120)
                    continue
                }
                val track = trackMap[extractor.sampleTrackIndex] ?: run {
                    extractor.advance()
                    return@run null
                } ?: continue
                val pts = extractor.sampleTime
                if (firstPts < 0) firstPts = pts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (pts - firstPts).coerceAtLeast(0)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            started = false
            Logx.i("Recorded ${destination.length() / 1024} KB of live stream")
            return durationMs
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }
}
