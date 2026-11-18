package com.aiclipcreator.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aiclipcreator.core.common.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Decoded audio range ready to be re-encoded into a clip. */
data class PcmRange(
    val samples: ShortArray,
    val sampleRate: Int,
    val startMs: Long,
    val durationMs: Long
)

/**
 * Decodes a time range of the source audio into mono PCM at the requested sample rate.
 * Used by the renderer, where clips are short enough to hold in memory.
 */
class PcmExtractor(private val context: Context) {

    suspend fun decodeRange(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        targetSampleRate: Int = 44_100,
        maxDurationMs: Long = 15 * 60 * 1000L,
        cancelled: AtomicBoolean? = null
    ): Result<PcmRange> = withContext(Dispatchers.Default) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val effectiveEnd = if (endMs - startMs > maxDurationMs) startMs + maxDurationMs else endMs
            extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext Result.failure(IllegalStateException("No audio track in source"))

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = ArrayList<FloatArray>(1024)
            var totalFrames = 0
            val resampler = MonoResampler(srcRate, targetSampleRate)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var outChannels = srcChannels
            var isFloat = false
            val startUs = startMs * 1000
            val endUs = effectiveEnd * 1000

            while (!sawOutputEos) {
                currentCoroutineContext().ensureActive()
                if (cancelled?.get() == true) return@withContext Result.failure(IllegalStateException("Cancelled"))
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            isFloat = f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val presentUs = info.presentationTimeUs
                        if (info.size > 0 && presentUs >= startUs - 100_000) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val frames = if (isFloat) {
                                val fb = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val n = fb.remaining() / outChannels
                                val mono = FloatArray(n)
                                for (f in 0 until n) {
                                    var sum = 0f
                                    for (c in 0 until outChannels) sum += fb.get(f * outChannels + c)
                                    mono[f] = sum / outChannels
                                }
                                mono
                            } else {
                                val sb = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val n = sb.remaining() / outChannels
                                val mono = FloatArray(n)
                                for (f in 0 until n) {
                                    var sum = 0f
                                    for (c in 0 until outChannels) sum += sb.get(f * outChannels + c) / 32768f
                                    mono[f] = sum / outChannels
                                }
                                mono
                            }
                            // Skip samples that start before the requested range.
                            val skipFrames = (((startUs - presentUs) / 1_000_000.0) * srcRate).toInt().coerceAtLeast(0)
                            val usable = if (skipFrames >= frames.size) FloatArray(0) else frames.copyOfRange(skipFrames, frames.size)
                            val resampled = resampler.process(usable, usable.size)
                            if (resampled.isNotEmpty()) {
                                chunks += resampled
                                totalFrames += resampled.size
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            val samples = ShortArray(totalFrames)
            var offset = 0
            for (chunk in chunks) {
                for (s in chunk) {
                    if (offset >= samples.size) break
                    samples[offset++] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
            Result.success(
                PcmRange(
                    samples = samples,
                    sampleRate = targetSampleRate,
                    startMs = startMs,
                    durationMs = samples.size * 1000L / targetSampleRate
                )
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logx.e("PCM extraction failed", t)
            Result.failure(t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}
