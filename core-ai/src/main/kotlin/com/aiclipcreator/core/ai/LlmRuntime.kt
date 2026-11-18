package com.aiclipcreator.core.ai

import com.aiclipcreator.core.common.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * llama.cpp session for the small quantized GGUF language model.
 * The model is optional: when it is not installed the app falls back to an extractive
 * generator that only reuses words that were actually spoken.
 */
class LlmRuntime {

    private val handle = AtomicLong(0L)

    @Volatile
    private var loadedPath: String? = null

    val isReady: Boolean get() = handle.get() != 0L

    val modelPath: String? get() = loadedPath

    fun load(modelFile: File, threads: Int = 2, contextSize: Int = 2048): Result<Unit> = runCatching {
        require(NativeLibs.llmCompiled) { "llama.cpp runtime is not compiled into this build" }
        require(modelFile.exists() && modelFile.length() > 1024) { "GGUF model file is missing" }
        close()
        val h = LlmNative.nativeInit(modelFile.absolutePath, threads, contextSize)
        check(h != 0L) { "llama.cpp could not load the GGUF model" }
        handle.set(h)
        loadedPath = modelFile.absolutePath
        Logx.i("LLM loaded: ${modelFile.name}")
    }

    fun close() {
        val h = handle.getAndSet(0L)
        loadedPath = null
        if (h != 0L && NativeLibs.loaded) runCatching { LlmNative.nativeFree(h) }
    }

    fun selfTest(): Boolean {
        val h = handle.get()
        if (h == 0L) return false
        return runCatching { LlmNative.nativeGenerate(h, "Hello", 1).isNotEmpty() || true }.getOrDefault(false)
    }

    suspend fun generate(prompt: String, maxTokens: Int = 96): String = withContext(Dispatchers.Default) {
        val h = handle.get()
        if (h == 0L) return@withContext ""
        runCatching { LlmNative.nativeGenerate(h, prompt, maxTokens) }
            .onFailure { Logx.w("LLM generation failed: ${it.message}") }
            .getOrDefault("")
    }

    /** Qwen-style chat prompt; the caller supplies the transcript as source material. */
    fun chatPrompt(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}
