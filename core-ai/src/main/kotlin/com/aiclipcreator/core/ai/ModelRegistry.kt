package com.aiclipcreator.core.ai

import com.aiclipcreator.core.models.ModelCategory
import com.aiclipcreator.core.models.ModelRuntime
import com.aiclipcreator.core.models.ModelSpec
import com.aiclipcreator.core.models.ProductionProfile

/**
 * Every artifact below is the upstream file, and [ModelSpec.sha256] is the digest of that exact
 * file measured from the publisher's URL. Nothing is invented: if a download does not match the
 * digest, installation fails.
 */
object ModelRegistry {

    const val ID_WHISPER_TINY = "whisper-tiny"
    const val ID_WHISPER_BASE = "whisper-base"
    const val ID_VISION_EFFICIENTNET = "vision-efficientnet-lite0"
    const val ID_VAD_SILERO = "audio-silero-vad"
    const val ID_LLM_QWEN = "llm-qwen2.5-0.5b"

    private const val HF_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = ID_WHISPER_TINY,
            name = "Whisper Tiny (multilingual)",
            category = ModelCategory.SPEECH,
            runtime = ModelRuntime.WHISPER_CPP,
            fileName = "ggml-tiny.bin",
            url = "$HF_WHISPER/ggml-tiny.bin",
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            sizeBytes = 77_691_713,
            license = "MIT (whisper.cpp model weights, OpenAI Whisper)",
            source = "https://github.com/ggml-org/whisper.cpp",
            version = "ggml-v1.7.4-compatible",
            architectures = listOf("arm64-v8a", "x86_64", "armeabi-v7a"),
            minRamMb = 900,
            minFreeStorageMb = 240,
            description = "Fast multilingual speech recognition with word timestamps. Recommended on 4 GB devices.",
            recommendedFor = ProductionProfile.LITE
        ),
        ModelSpec(
            id = ID_WHISPER_BASE,
            name = "Whisper Base (multilingual)",
            category = ModelCategory.SPEECH,
            runtime = ModelRuntime.WHISPER_CPP,
            fileName = "ggml-base.bin",
            url = "$HF_WHISPER/ggml-base.bin",
            sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            sizeBytes = 147_951_465,
            license = "MIT (whisper.cpp model weights, OpenAI Whisper)",
            source = "https://github.com/ggml-org/whisper.cpp",
            version = "ggml-v1.7.4-compatible",
            architectures = listOf("arm64-v8a", "x86_64"),
            minRamMb = 1400,
            minFreeStorageMb = 320,
            description = "Better accuracy for Hindi/Hinglish. Needs ~1.5 GB free RAM during analysis.",
            recommendedFor = ProductionProfile.BALANCED,
            variantOf = ID_WHISPER_TINY
        ),
        ModelSpec(
            id = ID_VISION_EFFICIENTNET,
            name = "EfficientNet-Lite0 (scene classification)",
            category = ModelCategory.VISION,
            runtime = ModelRuntime.LITERT,
            fileName = "efficientnet_lite0.tflite",
            url = "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite",
            sha256 = "6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0",
            sizeBytes = 18_582_189,
            license = "Apache-2.0 (Google AI Edge / MediaPipe)",
            source = "https://developers.google.com/edge/litert",
            version = "1.0",
            architectures = listOf("arm64-v8a", "x86_64", "armeabi-v7a"),
            minRamMb = 400,
            minFreeStorageMb = 60,
            description = "Labels sampled frames (ImageNet classes) for scene and action context.",
            recommendedFor = ProductionProfile.LITE
        ),
        ModelSpec(
            id = ID_VAD_SILERO,
            name = "Silero VAD v5",
            category = ModelCategory.AUDIO,
            runtime = ModelRuntime.ONNXRUNTIME,
            fileName = "silero_vad.onnx",
            url = "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx",
            sha256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
            sizeBytes = 2_327_524,
            license = "MIT (Silero Team)",
            source = "https://github.com/snakers4/silero-vad",
            version = "5.0",
            architectures = listOf("arm64-v8a", "x86_64", "armeabi-v7a"),
            minRamMb = 200,
            minFreeStorageMb = 40,
            description = "Neural voice activity detection for precise speech boundaries.",
            recommendedFor = ProductionProfile.LITE
        ),
        ModelSpec(
            id = ID_LLM_QWEN,
            name = "Qwen2.5-0.5B-Instruct (Q4_K_M)",
            category = ModelCategory.LANGUAGE,
            runtime = ModelRuntime.LLAMA_CPP,
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            sizeBytes = 491_400_032,
            license = "Apache-2.0 (Alibaba Qwen)",
            source = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            version = "q4_k_m",
            architectures = listOf("arm64-v8a"),
            minRamMb = 2000,
            minFreeStorageMb = 700,
            description = "Local caption writer for titles, descriptions and hashtags. Optional: extractive captions are used when absent.",
            recommendedFor = ProductionProfile.ADVANCED
        )
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    fun speechModels(): List<ModelSpec> = all.filter { it.category == ModelCategory.SPEECH }

    fun forProfile(profile: ProductionProfile): List<ModelSpec> = when (profile) {
        ProductionProfile.LITE -> all.filter { it.id != ID_LLM_QWEN && it.id != ID_WHISPER_BASE }
        ProductionProfile.BALANCED -> all.filter { it.id != ID_LLM_QWEN }
        ProductionProfile.ADVANCED -> all
    }

    val totalInstallBytes: Long = all.sumOf { it.sizeBytes }
}
