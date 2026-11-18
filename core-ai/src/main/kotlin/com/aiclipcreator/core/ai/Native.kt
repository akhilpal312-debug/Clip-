package com.aiclipcreator.core.ai

import com.aiclipcreator.core.common.Logx

/** whisper.cpp bindings (see src/main/cpp/whisper_jni.cpp). */
internal object WhisperNative {
    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeModelType(handle: Long): Int
    @JvmStatic external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        threadCount: Int,
        language: String,
        translate: Boolean,
        wordTimestamps: Boolean
    ): String
    @JvmStatic external fun nativeBenchmark(handle: Long): String
}

/** llama.cpp bindings (see src/main/cpp/llm_jni.cpp). */
internal object LlmNative {
    @JvmStatic external fun nativeCompiled(): Boolean
    @JvmStatic external fun nativeInit(modelPath: String, threads: Int, contextSize: Int): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    @JvmStatic external fun nativeVersion(): String
}

object NativeLibs {
    private const val LIB = "aiclipai"

    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary(LIB) }
            .onFailure { Logx.w("Native library $LIB unavailable: ${it.message}") }
            .isSuccess
    }

    val whisperAvailable: Boolean by lazy {
        loaded && runCatching { WhisperNative.nativeBenchmark(0L) }.isSuccess
    }

    val llmCompiled: Boolean by lazy {
        loaded && runCatching { LlmNative.nativeCompiled() }.getOrDefault(false)
    }

    fun llmVersion(): String = if (!loaded) "unavailable" else runCatching { LlmNative.nativeVersion() }.getOrDefault("unavailable")
}
