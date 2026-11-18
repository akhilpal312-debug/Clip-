package com.aiclipcreator.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.AudioFeatureFrame
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD (v5, ONNX) through ONNX Runtime. The 512-sample frame state is kept between calls
 * exactly as the model expects. If the session fails for any reason the caller falls back to the
 * DSP detector in core-audio.
 */
class SileroVad : Closeable {

    private companion object {
        const val FRAME = 512
        const val SAMPLE_RATE = 16_000L
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var state = FloatArray(2 * 1 * 128)
    private val frameBuffer = FloatArray(FRAME)

    @Volatile
    var failureReason: String? = null
        private set

    val isReady: Boolean get() = session != null

    fun load(modelFile: File, threads: Int = 2): Result<Unit> = runCatching {
        close()
        require(modelFile.exists() && modelFile.length() > 1024) { "VAD model missing" }
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads.coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = environment.createSession(modelFile.absolutePath, options)
        env = environment
        state = FloatArray(2 * 1 * 128)
        Logx.i("Silero VAD loaded")
        Unit
    }.onFailure { failureReason = it.message }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }

    /** Speech probability for exactly one 32 ms frame (512 samples at 16 kHz). */
    fun probability(samples: FloatArray): Float {
        val sess = session ?: return 0f
        val envRef = env ?: return 0f
        if (samples.isEmpty()) return 0f
        val count = minOf(samples.size, FRAME)
        System.arraycopy(samples, 0, frameBuffer, 0, count)
        if (count < FRAME) java.util.Arrays.fill(frameBuffer, count, FRAME, 0f)
        return runCatching {
            OnnxTensor.createTensor(envRef, FloatBuffer.wrap(frameBuffer), longArrayOf(1, FRAME.toLong())).use { input ->
                OnnxTensor.createTensor(envRef, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                    OnnxTensor.createTensor(envRef, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf(1)).use { srTensor ->
                        sess.run(mapOf("input" to input, "state" to stateTensor, "sr" to srTensor)).use { output ->
                            val prob = (output.get("output").orElse(null) as? OnnxTensor)
                                ?.floatBuffer?.get(0) ?: 0f
                            (output.get("stateN").orElse(null) as? OnnxTensor)?.floatBuffer?.let { fb ->
                                val next = FloatArray(state.size)
                                fb.get(next)
                                state = next
                            }
                            prob
                        }
                    }
                }
            }
        }.onFailure { if (failureReason == null) failureReason = it.message }.getOrDefault(0f)
    }

    /** Runs the model over a block of audio, returning 32 ms frame probabilities. */
    fun frameProbabilities(samples: FloatArray): FloatArray {
        if (!isReady || samples.isEmpty()) return FloatArray(0)
        val frames = samples.size / FRAME
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            out[i] = probability(samples.copyOfRange(i * FRAME, (i + 1) * FRAME))
        }
        return out
    }

    fun mergeInto(features: List<AudioFeatureFrame>, probabilities: FloatArray): List<AudioFeatureFrame> {
        if (probabilities.isEmpty() || features.isEmpty()) return features
        return features.map { frame ->
            val idx = (frame.timeMs / 100 / 3).toInt().coerceIn(0, probabilities.size - 1)
            frame.copy(speechProbability = probabilities[idx])
        }
    }

    fun selfTest(): Boolean {
        val sess = session ?: return false
        return runCatching {
            val probe = FloatArray(FRAME)
            val p = probability(probe)
            sess.inputNames.isNotEmpty() && p in 0f..1f
        }.getOrDefault(false)
    }
}
