#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "LlmJNI"

#ifdef AICLIP_WITH_LLM
#include <vector>
#include <mutex>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct LlmSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::mutex mutex;
    int n_ctx = 2048;
};

std::string toStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeCompiled(JNIEnv *, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeInit(JNIEnv *env, jobject, jstring modelPath, jint threads, jint contextSize) {
    const std::string path = toStd(env, modelPath);
    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("Unable to load GGUF model %s", path.c_str());
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = contextSize > 0 ? contextSize : 2048;
    cparams.n_threads = threads > 0 ? threads : 2;
    cparams.n_batch = 256;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    auto *session = new LlmSession();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = cparams.n_ctx;
    LOGI("Loaded GGUF model %s", path.c_str());
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->ctx) llama_free(session->ctx);
        if (session->model) llama_model_free(session->model);
    }
    delete session;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeGenerate(JNIEnv *env, jobject, jlong handle,
                                                       jstring prompt, jint maxTokens) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return env->NewStringUTF("");
    }
    const std::string promptText = toStd(env, prompt);
    std::lock_guard<std::mutex> lock(session->mutex);
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const bool addSpecial = true;
    const int n_prompt = -llama_tokenize(session->vocab, promptText.c_str(),
                                        static_cast<int32_t>(promptText.size()),
                                        nullptr, 0, addSpecial, true);
    if (n_prompt <= 0) return env->NewStringUTF("");
    if (n_prompt >= session->n_ctx - 64) return env->NewStringUTF("");
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(session->vocab, promptText.c_str(), static_cast<int32_t>(promptText.size()),
                       tokens.data(), n_prompt, addSpecial, true) < 0) {
        return env->NewStringUTF("");
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    if (llama_decode(session->ctx, batch) != 0) return env->NewStringUTF("");

    std::string output;
    const int limit = maxTokens > 0 ? maxTokens : 96;
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    for (int i = 0; i < limit; i++) {
        const llama_token id = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, id)) break;
        char piece[256];
        const int n = llama_token_to_piece(session->vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);
        llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        if (llama_decode(session->ctx, next) != 0) break;
    }
    llama_sampler_free(smpl);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeVersion(JNIEnv *env, jobject) {
    std::string version = "llama.cpp ";
    version += llama_version();
    return env->NewStringUTF(version.c_str());
}

#else // AICLIP_WITH_LLM

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeCompiled(JNIEnv *, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeInit(JNIEnv *, jobject, jstring, jint, jint) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeFree(JNIEnv *, jobject, jlong) {
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeGenerate(JNIEnv *env, jobject, jlong, jstring, jint) {
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aiclipcreator_core_ai_LlmNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("llama.cpp (not compiled in this build)");
}

#endif
