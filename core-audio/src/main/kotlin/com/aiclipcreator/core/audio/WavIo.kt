package com.aiclipcreator.core.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Writes 16-bit PCM mono WAV incrementally. Nothing is buffered in memory beyond one block,
 * so multi-hour sources stay safe.
 */
class WavWriter(
    val file: File,
    val sampleRate: Int = AudioConfig.SAMPLE_RATE
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val byteBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

    init {
        file.parentFile?.mkdirs()
        raf.setLength(0)
        writeHeader(0)
    }

    fun write(samples: FloatArray, length: Int = samples.size) {
        if (length <= 0) return
        byteBuffer.clear()
        var i = 0
        while (i < length) {
            val chunk = min(length - i, byteBuffer.remaining() / 2)
            var j = 0
            while (j < chunk) {
                val v = (samples[i + j].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                byteBuffer.putShort(v)
                j++
            }
            dataBytes += chunk * 2L
            raf.write(byteBuffer.array(), 0, byteBuffer.position())
            byteBuffer.clear()
            i += chunk
        }
    }

    fun writeShorts(samples: ShortArray, length: Int = samples.size) {
        if (length <= 0) return
        byteBuffer.clear()
        var i = 0
        while (i < length) {
            val chunk = min(length - i, byteBuffer.remaining() / 2)
            var j = 0
            while (j < chunk) {
                byteBuffer.putShort(samples[i + j])
                j++
            }
            dataBytes += chunk * 2L
            raf.write(byteBuffer.array(), 0, byteBuffer.position())
            byteBuffer.clear()
            i += chunk
        }
    }

    val durationMs: Long get() = dataBytes * 1000L / (sampleRate.toLong() * 2L)

    private fun writeHeader(dataSize: Long) {
        raf.seek(0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())
        raf.write(header.array())
    }

    override fun close() {
        try {
            writeHeader(dataBytes)
        } finally {
            raf.close()
        }
    }
}

/** Random access reader for windows of the extracted PCM. */
class WavReader(val file: File, val sampleRate: Int = AudioConfig.SAMPLE_RATE) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val totalSamples: Long
    val durationMs: Long

    init {
        // Skip any header bytes that may precede the data chunk.
        val header = ByteArray(44)
        raf.readFully(header)
        val dataSize = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        totalSamples = if (dataSize > 0) dataSize / 2 else (file.length() - 44) / 2
        durationMs = totalSamples * 1000L / sampleRate
    }

    fun readWindow(startSample: Long, count: Int): FloatArray {
        val available = (totalSamples - startSample).coerceAtLeast(0).toInt()
        val toRead = min(count, available)
        val out = FloatArray(count)
        if (toRead <= 0) return out
        val bytes = ByteArray(toRead * 2)
        raf.seek(44 + startSample * 2)
        var read = 0
        while (read < bytes.size) {
            val n = raf.read(bytes, read, bytes.size - read)
            if (n <= 0) break
            read += n
        }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var i = 0
        while (i < toRead) {
            out[i] = (shorts.get(i).toInt() / 32768f)
            i++
        }
        return out
    }

    fun readShortsWindow(startSample: Long, count: Int): ShortArray {
        val available = (totalSamples - startSample).coerceAtLeast(0).toInt()
        val toRead = min(count, available)
        val out = ShortArray(toRead)
        if (toRead <= 0) return out
        val bytes = ByteArray(toRead * 2)
        raf.seek(44 + startSample * 2)
        var read = 0
        while (read < bytes.size) {
            val n = raf.read(bytes, read, bytes.size - read)
            if (n <= 0) break
            read += n
        }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var i = 0
        while (i < toRead) {
            out[i] = shorts.get(i)
            i++
        }
        return out
    }

    override fun close() = raf.close()
}

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val WHISPER_WINDOW_SAMPLES = 30 * SAMPLE_RATE
    const val WHISPER_WINDOW_MS = 30_000L
}
