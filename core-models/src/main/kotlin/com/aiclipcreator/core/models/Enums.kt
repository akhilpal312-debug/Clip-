package com.aiclipcreator.core.models

enum class ProcessingStage {
    IDLE, PROBE, AUDIO_EXTRACT, VAD, TRANSCRIBE, SCENE_DETECT, VISION,
    AUDIO_FEATURES, SCORE, CAPTION, RENDER, EXPORT, DONE, FAILED, CANCELLED;

    val label: String
        get() = when (this) {
            IDLE -> "Waiting"
            PROBE -> "Reading metadata"
            AUDIO_EXTRACT -> "Extracting audio"
            VAD -> "Detecting speech"
            TRANSCRIBE -> "Transcribing speech"
            SCENE_DETECT -> "Detecting scenes"
            VISION -> "Analysing frames"
            AUDIO_FEATURES -> "Analysing audio"
            SCORE -> "Ranking moments"
            CAPTION -> "Building captions"
            RENDER -> "Rendering clips"
            EXPORT -> "Exporting to gallery"
            DONE -> "Completed"
            FAILED -> "Failed"
            CANCELLED -> "Cancelled"
        }
}

enum class JobState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

enum class ClipMode(val label: String) {
    BEST("Best Moments"), BALANCED("Balanced Clips"), BOTH("Both")
}

enum class ClipQuality(val label: String, val folder: String) {
    BEST("Best", "Best Moments"), BALANCED("Balanced", "Balanced Clips")
}

enum class AspectRatio(val width: Int, val height: Int, val label: String) {
    PORTRAIT_9_16(9, 16, "9:16"),
    LANDSCAPE_16_9(16, 9, "16:9"),
    SQUARE_1_1(1, 1, "1:1");

    val ratio: Float get() = width.toFloat() / height.toFloat()
}

enum class Language(val code: String, val label: String, val whisperCode: String) {
    AUTO("auto", "Auto detect", "auto"),
    HINDI("hi", "Hindi", "hi"),
    ENGLISH("en", "English", "en"),
    HINGLISH("hi-en", "Hinglish", "hi")
}

enum class DetectionCategory(val label: String) {
    FUNNY("Funny"),
    INSIGHT("Insight"),
    EMOTIONAL("Emotional"),
    STORY("Story"),
    EDUCATIONAL("Educational"),
    GAMING("Gaming"),
    REACTION("Reaction"),
    EMPHASIS("Emphasis"),
    ACTION("Action"),
    GENERAL("General")
}

enum class SignalKind(val label: String) {
    DIALOGUE("Dialogue"),
    AUDIO_PEAK("Audio peak"),
    SCENE_CHANGE("Scene change"),
    MOTION("Motion"),
    FACE("Faces"),
    LAUGHTER("Laughter"),
    QUESTION("Question"),
    COMPLETE_THOUGHT("Complete thought"),
    PACING("Pacing")
}

enum class ModelCategory(val label: String) {
    SPEECH("Speech to text"),
    VISION("Vision"),
    LANGUAGE("Language model"),
    AUDIO("Audio intelligence")
}

enum class ModelState { NOT_INSTALLED, QUEUED, DOWNLOADING, VERIFYING, INSTALLED, READY, FAILED, UNSUPPORTED }

enum class ModelRuntime { WHISPER_CPP, LITERT, ONNXRUNTIME, LLAMA_CPP }

enum class ProductionProfile(val label: String) {
    LITE("Lite"),
    BALANCED("Balanced"),
    ADVANCED("Advanced")
}

enum class DurationMode { AI_DECIDED, CUSTOM }

enum class ClipCountMode { ALL_QUALIFIED, CUSTOM }

enum class LiveSourceType(val label: String) {
    HLS("HLS (.m3u8)"),
    PROGRESSIVE_HTTP("HTTP video (mp4/ts)"),
    YOUTUBE("YouTube live (metadata only)")
}

enum class LiveStatus { IDLE, VALIDATING, LIVE, OFFLINE, ERROR, UNSUPPORTED }
