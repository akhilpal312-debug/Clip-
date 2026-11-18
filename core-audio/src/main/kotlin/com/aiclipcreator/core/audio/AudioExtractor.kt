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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class ExtractedAudio(
    val file: File,
    val sampleRate: Int,
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int
)

/**
 * Decodes the first audio track of a video into 16 kHz mono 16-bit PCM (the format whisper.cpp
 * consumes). Streaming: each decoded buffer is mixed down and resampled immediately, so memory
 * usage does not scale with video length.
 */
class AudioExtractor(private val context: Context) {

    suspend fun extract(
        sourceUri: Uri,
        output: File,
        onProgress: (Float) -> Unit = {}
    ): Result<ExtractedAudio> = withContext(Dispatchers.Default) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        val writerLock = Any()
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext Result.failure(IllegalStateException("This video has no audio track"))

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            extractor.selectTrack(trackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val wav = WavWriter(output, AudioConfig.SAMPLE_RATE)
            val resampler = MonoResampler(sourceSampleRate, AudioConfig.SAMPLE_RATE)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var isFloatPcm = false
            var outChannels = sourceChannels
            val silence = ShortArray(0)
            var wroteAny = false
            val startedAt = System.currentTimeMillis()

            while (!sawOutputEos) {
                currentCoroutineContext().ensureActive()
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            isFloatPcm = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val mixed = if (isFloatPcm) {
                                val fb = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val frames = fb.remaining() / outChannels
                                val mono = FloatArray(frames)
                                var f = 0
                                while (f < frames) {
                                    var sum = 0f
                                    var c = 0
                                    while (c < outChannels) {
                                        sum += fb.get(f * outChannels + c)
                                        c++
                                    }
                                    mono[f] = sum / outChannels
                                    f++
                                }
                                mono
                            } else {
                                val sb = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val frames = sb.remaining() / outChannels
                                val mono = FloatArray(frames)
                                var f = 0
                                while (f < frames) {
                                    var sum = 0f
                                    var c = 0
                                    while (c < outChannels) {
                                        sum += sb.get(f * outChannels + c) / 32768f
                                        c++
                                    }
                                    mono[f] = sum / outChannels
                                    f++
                                }
                                mono
                            }
                            val resampled = resampler.process(mixed, mixed.size)
                            if (resampled.isNotEmpty()) {
                                synchronized(writerLock) { wav.write(resampled) }
                                wroteAny = true
                            }
                            if (trackDurationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / trackDurationUs).coerceIn(0f, 1f)
                                onProgress(p)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            codec.stop()
            codec.release()
            codec = null
            synchronized(writerLock) { wav.close() }
            extractor.release()
            extractor = null
            if (!wroteAny) {
                return@withContext Result.failure(IllegalStateException("Audio track decoded to zero samples"))
            }
            Logx.i(
                "Audio extracted in ${System.currentTimeMillis() - startedAt} ms " +
                    "(${output.length()} bytes, src ${sourceSampleRate}Hz x$sourceChannels)"
            )
            Result.success(
                ExtractedAudio(
                    file = output,
                    sampleRate = AudioConfig.SAMPLE_RATE,
                    durationMs = WavReader(output).use { it.durationMs },
                    sourceSampleRate = sourceSampleRate,
                    sourceChannels = sourceChannels
                )
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logx.e("Audio extraction failed", t)
            Result.failure(t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** True when the file contains at least one audio track. */
    fun hasAudio(context: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).any {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }
}

/**
 * Streaming linear resampler with a one-sample carry so block boundaries do not click.
 */
class MonoResampler(private val inRate: Int, private val outRate: Int) {
    private var position = 0.0
    private var carry = 0f
    private var started = false

    fun process(input: FloatArray, length: Int): FloatArray {
        if (length <= 0) return FloatArray(0)
        if (inRate == outRate) return input.copyOf(length)
        if (!started) {
            // Seed the interpolation with the first real sample so output[0] is not silence.
            carry = input[0]
            started = true
        }
        val total = length + 1
        val step = inRate.toDouble() / outRate.toDouble()
        val capacity = ((total / step).toInt() + 2).coerceAtLeast(1)
        val out = FloatArray(capacity)
        var oi = 0
        var p = position
        while (p < total - 1 && oi < capacity) {
            val i0 = p.toInt()
            val frac = (p - i0).toFloat()
            val s0 = if (i0 == 0) carry else input[i0 - 1]
            val s1 = if (i0 + 1 == 0) carry else input[(i0 + 1 - 1).coerceAtMost(length - 1)]
            out[oi] = s0 + (s1 - s0) * frac
            oi++
            p += step
        }
        position = p - (total - 1)
        carry = input[length - 1]
        return if (oi == capacity) out else out.copyOf(oi)
    }
}

/** Peak-normalises PCM in place; returns the gain applied. */
object AudioNormalizer {
    fun gainFor(samples: ShortArray, targetPeak: Float = 0.89f): Float {
        var peak = 0
        for (s in samples) {
            val v = abs(s.toInt())
            if (v > peak) peak = v
        }
        if (peak == 0) return 1f
        return (targetPeak * 32767f / peak).coerceIn(0.25f, 8f)
    }
}
