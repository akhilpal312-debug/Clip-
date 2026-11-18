package com.aiclipcreator.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.FaceDetector
import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.FrameAnalysis
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-frame visual analysis.
 *
 * - Scene classification runs on the LiteRT (TensorFlow Lite) EfficientNet-Lite0 model with the
 *   ImageNet label set when the model is installed.
 * - Face count/position uses the platform face detector so framing and speaker centring work
 *   even before any vision model is downloaded.
 * - Motion, brightness and sharpness are computed on the downscaled frame.
 */
class VisionAnalyzer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputWidth = 224
    private var inputHeight = 224
    private var inputSize = 224 * 224 * 3

    @Volatile
    private var modelLoaded = false

    val isReady: Boolean get() = modelLoaded

    fun load(modelFile: File, threads: Int = 2): Result<Unit> = runCatching {
        close()
        require(modelFile.exists()) { "Vision model missing" }
        val options = Interpreter.Options().apply {
            setNumThreads(threads.coerceIn(1, 4))
            setUseXNNPACK(true)
        }
        val itp = Interpreter(modelFile, options)
        val shape = itp.getInputTensor(0).shape()
        if (shape.size == 4) {
            inputHeight = shape[1]
            inputWidth = shape[2]
            inputSize = shape[1] * shape[2] * shape[3]
        }
        interpreter = itp
        if (labels.isEmpty()) labels = loadLabels()
        modelLoaded = true
        Logx.i("Vision model loaded (${inputWidth}x$inputHeight)")
    }

    fun loadFromAssets(assetName: String, threads: Int = 2): Result<Unit> = runCatching {
        val file = File(context.cacheDir, "vision_$assetName")
        if (!file.exists()) {
            context.assets.open(assetName).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        load(file, threads)
    }

    fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        modelLoaded = false
    }

    /** Runs a real inference pass with a neutral image to verify a freshly installed model. */
    fun selfTest(): Boolean {
        val itp = interpreter ?: return false
        return runCatching {
            val input = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
            val output = Array(1) { FloatArray(1001) }
            itp.run(input.rewind() as ByteBuffer, output)
            output[0].any { it != 0f }
        }.getOrDefault(false)
    }

    fun classify(bitmap: Bitmap, topK: Int = 3): List<String> {
        val itp = interpreter ?: return emptyList()
        if (labels.isEmpty()) return emptyList()
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val pixels = IntArray(inputWidth * inputHeight)
            scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            if (scaled !== bitmap) scaled.recycle()
            val input = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = input.asFloatBuffer()
            for (p in pixels) {
                floatBuffer.put(((p shr 16) and 0xFF) / 255f)
                floatBuffer.put(((p shr 8) and 0xFF) / 255f)
                floatBuffer.put((p and 0xFF) / 255f)
            }
            val outputSize = itp.getOutputTensor(0).shape().last()
            val output = Array(1) { FloatArray(outputSize) }
            itp.run(input.rewind() as ByteBuffer, output)
            output[0].mapIndexed { index, value -> index to value }
                .sortedByDescending { it.second }
                .take(topK)
                .mapNotNull { (index, _) ->
                    labels.getOrNull(index)?.replace('_', ' ')
                }
        }.getOrDefault(emptyList())
    }

    /** Face rectangles as fractions of the frame (x, y, width, height). */
    fun faces(bitmap: Bitmap): List<FloatArray> {
        val maxFaces = 4
        val scaled = if (bitmap.width > 480) {
            Bitmap.createScaledBitmap(bitmap, 480, (bitmap.height * 480 / bitmap.width).coerceAtLeast(1), true)
        } else bitmap
        return runCatching {
            // FaceDetector requires RGB_565.
            val rgb565 = if (scaled.config == Bitmap.Config.RGB_565) {
                scaled
            } else {
                Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.RGB_565).also { target ->
                    val canvas = android.graphics.Canvas(target)
                    canvas.drawBitmap(scaled, 0f, 0f, null)
                }
            }
            val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
            val detector = FaceDetector(rgb565.width, rgb565.height, maxFaces)
            val found = runCatching { detector.findFaces(rgb565, faces) }.getOrDefault(0)
            val result = ArrayList<FloatArray>(found)
            val midpoint = android.graphics.PointF()
            for (i in 0 until found) {
                val face = faces[i] ?: continue
                runCatching {
                    face.getMidPoint(midpoint)
                    val eyeDistance = face.eyesDistance().coerceAtLeast(1f)
                    result += floatArrayOf(
                        (midpoint.x / rgb565.width).coerceIn(0f, 1f),
                        (midpoint.y / rgb565.height).coerceIn(0f, 1f),
                        ((eyeDistance * 2f) / rgb565.width).coerceIn(0f, 1f),
                        face.confidence() / 100f
                    )
                }
            }
            @Suppress("UNUSED_VARIABLE")
            val release = detector
            if (rgb565 !== scaled) rgb565.recycle()
            if (scaled !== bitmap) scaled.recycle()
            faces.forEach { it?.let { f -> runCatching { f.getMidPoint(midpoint) } } }
            result
        }.getOrDefault(emptyList())
    }

    private fun loadLabels(): List<String> = runCatching {
        context.assets.open("imagenet_labels.txt").bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    /** Full analysis for one sampled frame. */
    fun analyze(
        bitmap: Bitmap,
        timeMs: Long,
        previousHistogram: FloatArray?,
        previousPixels: IntArray?
    ): Pair<FrameAnalysis, FloatArray> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = com.aiclipcreator.core.video.SceneDetector.histogram(pixels, bitmap.width, bitmap.height)
        val motion = if (previousHistogram != null) {
            com.aiclipcreator.core.video.SceneDetector.difference(previousHistogram, histogram)
        } else 0f
        val detectedFaces = faces(bitmap)
        val largest = detectedFaces.maxByOrNull { it[2] } ?: FloatArray(0)
        val labels = classify(bitmap)
        val analysis = FrameAnalysis(
            timeMs = timeMs,
            brightness = com.aiclipcreator.core.video.SceneDetector.averageBrightness(pixels),
            sharpness = com.aiclipcreator.core.video.SceneDetector.sharpness(pixels, bitmap.width, bitmap.height),
            motion = motion,
            faceCount = detectedFaces.size,
            largestFaceArea = if (largest.isNotEmpty()) largest[2] * largest[2] else 0f,
            faceCenterX = if (largest.isNotEmpty()) largest[0] else 0.5f,
            faceCenterY = if (largest.isNotEmpty()) largest[1] else 0.5f,
            labels = labels,
            activity = (motion * 0.7f + (detectedFaces.size.coerceAtMost(3) / 3f) * 0.3f).coerceIn(0f, 1f)
        )
        return analysis to histogram
    }

    @Suppress("unused")
    private val cancelled = AtomicBoolean(false)
}
