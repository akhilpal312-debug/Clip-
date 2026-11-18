#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cmath>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct WhisperSession {
    whisper_context *ctx = nullptr;
    std::mutex mutex;
};

std::string jstringToString(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string escapeJson(const std::string &input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    // skip control characters
                } else {
                    out += c;
                }
        }
    }
    return out;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_aiclipcreator_core_ai_WhisperNative_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const std::string path = jstringToString(env, modelPath);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) {
        LOGE("Unable to load whisper model at %s", path.c_str());
        return 0;
    }
    auto *session = new WhisperSession();
    session->ctx = ctx;
    LOGI("Loaded whisper model %s", path.c_str());
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiclipcreator_core_ai_WhisperNative_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *session = reinterpret_cast<WhisperSession *>(handle);
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->ctx) whisper_free(session->ctx);
    }
    delete session;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiclipcreator_core_ai_WhisperNative_nativeModelType(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *session = reinterpret_cast<WhisperSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) return -1;
    return static_cast<jint>(whisper_model_type(session->ctx));
}

/**
 * Transcribes one PCM window (16 kHz mono floats). Returns a JSON document:
 * {"language":"hi","segments":[{"t0":0,"t1":2100,"text":"...","no_speech":0.12,
 *   "words":[{"t0":120,"t1":480,"p":0.91,"w":"hello"}]}]}
 * Times are milliseconds relative to the window.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_aiclipcreator_core_ai_WhisperNative_nativeTranscribe(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle,
        jfloatArray pcm,
        jint threadCount,
        jstring language,
        jboolean translate,
        jboolean wordTimestamps) {

    auto *session = reinterpret_cast<WhisperSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return env->NewStringUTF("{\"error\":\"model not loaded\",\"segments\":[]}");
    }
    if (pcm == nullptr) {
        return env->NewStringUTF("{\"error\":\"no audio\",\"segments\":[]}");
    }
    const jsize length = env->GetArrayLength(pcm);
    if (length < 1600) {
        return env->NewStringUTF("{\"language\":\"\",\"segments\":[]}");
    }
    std::vector<float> samples(static_cast<size_t>(length));
    env->GetFloatArrayRegion(pcm, 0, length, samples.data());

    const std::string lang = jstringToString(env, language);

    std::lock_guard<std::mutex> lock(session->mutex);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate == JNI_TRUE;
    params.n_threads = threadCount > 0 ? threadCount : 2;
    params.offset_ms = 0;
    params.no_context = false;
    params.single_segment = false;
    params.token_timestamps = wordTimestamps == JNI_TRUE;
    params.max_len = 0;
    params.language = lang.empty() || lang == "auto" ? nullptr : lang.c_str();

    if (whisper_full(session->ctx, params, samples.data(), length) != 0) {
        LOGE("whisper_full failed");
        return env->NewStringUTF("{\"error\":\"inference failed\",\"segments\":[]}");
    }

    const int segments = whisper_full_n_segments(session->ctx);
    std::string json = "{\"language\":\"";
    const char *detected = whisper_full_lang_id(session->ctx) >= 0
                           ? whisper_lang_str(whisper_full_lang_id(session->ctx))
                           : "";
    json += escapeJson(detected ? detected : "");
    json += "\",\"segments\":[";
    for (int i = 0; i < segments; i++) {
        const int64_t t0 = whisper_full_get_segment_t0(session->ctx, i) * 10; // centiseconds -> ms
        const int64_t t1 = whisper_full_get_segment_t1(session->ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(session->ctx, i);
        if (i > 0) json += ",";
        json += "{\"t0\":" + std::to_string(t0) + ",\"t1\":" + std::to_string(t1);
        json += ",\"no_speech\":" + std::to_string(whisper_full_get_segment_no_speech_prob(session->ctx, i));
        json += ",\"text\":\"" + escapeJson(text ? text : "") + "\"";
        if (wordTimestamps == JNI_TRUE) {
            const int tokens = whisper_full_n_tokens(session->ctx, i);
            json += ",\"words\":[";
            bool first = true;
            for (int t = 0; t < tokens; t++) {
                const char *token = whisper_full_get_token_text(session->ctx, i, t);
                if (token == nullptr) continue;
                std::string tokenText(token);
                // Skip special tokens and pure whitespace fragments.
                bool special = tokenText.find("[_") != std::string::npos;
                if (special) continue;
                const auto data = whisper_full_get_token_data(session->ctx, i, t);
                if (data.t1 <= data.t0) continue;
                if (!first) json += ",";
                first = false;
                json += "{\"t0\":" + std::to_string(data.t0 * 10) +
                        ",\"t1\":" + std::to_string(data.t1 * 10) +
                        ",\"p\":" + std::to_string(data.p) +
                        ",\"w\":\"" + escapeJson(tokenText) + "\"}";
            }
            json += "]";
        }
        json += "}";
    }
    json += "]}";
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aiclipcreator_core_ai_WhisperNative_nativeBenchmark(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *session = reinterpret_cast<WhisperSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"model not loaded\"}");
    }
    // One second of a 440 Hz tone: proves the runtime can execute without a user file.
    std::vector<float> tone(16000);
    for (size_t i = 0; i < tone.size(); i++) {
        tone[i] = 0.05f * std::sin(2.0 * M_PI * 440.0 * static_cast<double>(i) / 16000.0);
    }
    std::lock_guard<std::mutex> lock(session->mutex);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.n_threads = 2;
    params.language = "en";
    const int64_t started = 0;
    (void) started;
    const int rc = whisper_full(session->ctx, params, tone.data(), static_cast<int>(tone.size()));
    if (rc != 0) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"benchmark inference failed\"}");
    }
    std::string json = "{\"ok\":true,\"segments\":" + std::to_string(whisper_full_n_segments(session->ctx)) + "}";
    return env->NewStringUTF(json.c_str());
}
