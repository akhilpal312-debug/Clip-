package com.aiclipcreator.core.ai

import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.TranscriptSegment
import com.aiclipcreator.core.models.TranscriptWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class WhisperResult(
    val segments: List<TranscriptSegment>,
    val detectedLanguage: String,
    val error: String? = null
)

/**
 * Owns one whisper.cpp context. Models can be loaded and unloaded independently; callers get a
 * clear failure instead of a crash when the native library is not part of the build.
 */
class WhisperRuntime {

    private val handle = AtomicLong(0L)
    @Volatile
    private var loadedPath: String? = null

    val isReady: Boolean get() = handle.get() != 0L

    val modelPath: String? get() = loadedPath

    fun load(modelFile: File): Result<Unit> = runCatching {
        require(NativeLibs.whisperAvailable) { "whisper.cpp runtime is not available in this build" }
        require(modelFile.exists() && modelFile.length() > 1024) { "Model file is missing" }
        close()
        val h = WhisperNative.nativeInit(modelFile.absolutePath)
        check(h != 0L) { "whisper.cpp could not load the model" }
        handle.set(h)
        loadedPath = modelFile.absolutePath
        Logx.i("Whisper model loaded: ${modelFile.name}")
    }

    fun close() {
        val h = handle.getAndSet(0L)
        loadedPath = null
        if (h != 0L && NativeLibs.loaded) runCatching { WhisperNative.nativeFree(h) }
    }

    /** Runs a real inference pass on a synthetic tone to validate the installed model. */
    fun selfTest(): Boolean {
        val h = handle.get()
        if (h == 0L) return false
        val result = runCatching { WhisperNative.nativeBenchmark(h) }.getOrNull() ?: return false
        return runCatching { JSONObject(result).optBoolean("ok", false) }.getOrDefault(false)
    }

    /**
     * Transcribes a 30 second (or shorter) window of 16 kHz mono audio.
     * Timestamps are returned relative to the window start.
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String = "auto",
        threads: Int = 2,
        translate: Boolean = false,
        wordTimestamps: Boolean = true
    ): WhisperResult = withContext(Dispatchers.Default) {
        val h = handle.get()
        if (h == 0L) return@withContext WhisperResult(emptyList(), "", "Whisper model is not loaded")
        if (samples.size < 1600) return@withContext WhisperResult(emptyList(), language)
        val raw = runCatching {
            WhisperNative.nativeTranscribe(h, samples, threads, language, translate, wordTimestamps)
        }.getOrElse { error ->
            Logx.e("Whisper inference failed", error)
            return@withContext WhisperResult(emptyList(), "", error.message ?: "Inference failed")
        }
        parse(raw)
    }

    private fun parse(raw: String): WhisperResult {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return WhisperResult(emptyList(), "", "Malformed inference output")
        }
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            return WhisperResult(emptyList(), "", it)
        }
        val detected = root.optString("language", "")
        val array = root.optJSONArray("segments") ?: return WhisperResult(emptyList(), detected)
        val segments = ArrayList<TranscriptSegment>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.optString("text").trim()
            if (text.isBlank()) continue
            val wordsArr = obj.optJSONArray("words")
            val words = ArrayList<TranscriptWord>()
            if (wordsArr != null) {
                for (w in 0 until wordsArr.length()) {
                    val wordObj = wordsArr.optJSONObject(w) ?: continue
                    val wText = wordObj.optString("w")
                    if (wText.isBlank()) continue
                    words += TranscriptWord(
                        startMs = wordObj.optLong("t0"),
                        endMs = wordObj.optLong("t1"),
                        text = wText,
                        probability = wordObj.optDouble("p", 0.0).toFloat()
                    )
                }
            }
            segments += TranscriptSegment(
                startMs = obj.optLong("t0"),
                endMs = obj.optLong("t1"),
                text = text,
                language = detected,
                words = words,
                noSpeechProbability = obj.optDouble("no_speech", 0.0).toFloat()
            )
        }
        return WhisperResult(segments, detected)
    }
}
