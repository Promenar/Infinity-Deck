#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <mutex>
#include <algorithm>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "InfinityLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaSession {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
};

static std::once_flag backend_once;

static std::string utf8_bytes(JNIEnv *env, jbyteArray bytes) {
    const jsize length = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}

static llama_token greedy_sample(const llama_vocab *vocab, const float *logits) {
    int32_t n_vocab = llama_vocab_n_tokens(vocab);
    llama_token best = 0;
    float max_logit = -INFINITY;
    for (int32_t i = 0; i < n_vocab; i++) {
        if (logits[i] > max_logit) {
            max_logit = logits[i];
            best = i;
        }
    }
    return best;
}

// ═══════════════════════════════════════════════
// 1. Model loading
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jlong JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_00024Companion_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jbyteArray modelPath, jint nCtx, jint nThreads, jboolean useGpu) {

    auto *session = new LlamaSession();

    std::call_once(backend_once, llama_backend_init);

    llama_model_params model_params = llama_model_default_params();
    if (useGpu) {
        model_params.n_gpu_layers = 999;
    }

    const auto path = utf8_bytes(env, modelPath);
    session->model = llama_model_load_from_file(path.c_str(), model_params);

    if (!session->model) {
        LOGE("Failed to load model");
        delete session;
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = static_cast<uint32_t>(nCtx);
    ctx_params.n_threads       = static_cast<int32_t>(nThreads);
    ctx_params.n_threads_batch = static_cast<int32_t>(nThreads);
    ctx_params.embeddings      = false;

    session->ctx = llama_init_from_model(session->model, ctx_params);
    if (!session->ctx) {
        LOGE("Failed to create context");
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    session->vocab = llama_model_get_vocab(session->model);

    LOGI("Model loaded (n_ctx=%d, threads=%d, gpu=%s)",
         nCtx, nThreads, useGpu ? "on" : "off");
    return reinterpret_cast<jlong>(session);
}

// ═══════════════════════════════════════════════
// 2. Tokenize
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jintArray JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeTokenize(
        JNIEnv *env, jobject, jlong ptr, jbyteArray text, jboolean addBos) {

    auto *session = reinterpret_cast<LlamaSession *>(ptr);
    const auto utf8 = utf8_bytes(env, text);
    const auto *str = utf8.data();
    int32_t text_len = static_cast<int32_t>(utf8.size());

    std::vector<llama_token> tokens(text_len + 4);
    int32_t n_tokens = llama_tokenize(
            session->vocab, str, text_len,
            tokens.data(), static_cast<int32_t>(tokens.size()),
            addBos, true);

    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(
                session->vocab, str, text_len,
                tokens.data(), static_cast<int32_t>(tokens.size()),
                addBos, true);
    }


    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewIntArray(0);
    }

    jintArray result = env->NewIntArray(n_tokens);
    env->SetIntArrayRegion(result, 0, n_tokens,
                           reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

// ═══════════════════════════════════════════════
// 3. Detokenize
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeDetokenize(
        JNIEnv *env, jobject, jlong ptr, jintArray tokens) {

    auto *session = reinterpret_cast<LlamaSession *>(ptr);
    jsize len = env->GetArrayLength(tokens);
    jint *token_arr = env->GetIntArrayElements(tokens, nullptr);

    std::string result;
    std::vector<char> buf(256);

    for (jsize t = 0; t < len; t++) {
        llama_token tok = static_cast<llama_token>(token_arr[t]);
        int n = llama_token_to_piece(
                session->vocab, tok,
                buf.data(), static_cast<int32_t>(buf.size()),
                0, true);
        if (n < 0) {
            buf.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(
                    session->vocab, tok,
                    buf.data(), static_cast<int32_t>(buf.size()),
                    0, true);
        }
        if (n > 0) {
            result.append(buf.data(), static_cast<size_t>(n));
        }
    }

    env->ReleaseIntArrayElements(tokens, token_arr, 0);
    auto bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (bytes && !result.empty()) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte *>(result.data()));
    }
    return bytes;
}

// ═══════════════════════════════════════════════
// 4. Ingest prompt tokens (batch prompt processing)
//    Positions are tracked automatically by llama_decode
//    via llama_batch_get_one. Only the last token
//    generates logits (default when logits == nullptr).
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeIngestPrompt(
        JNIEnv *env, jobject, jlong ptr, jintArray tokens) {

    auto *session = reinterpret_cast<LlamaSession *>(ptr);
    jsize len = env->GetArrayLength(tokens);
    jint *token_arr = env->GetIntArrayElements(tokens, nullptr);

    uint32_t n_batch = llama_n_batch(session->ctx);

    bool succeeded = true;
    for (jsize i = 0; i < len; i += static_cast<jsize>(n_batch)) {
        int32_t batch_size = static_cast<int32_t>(
            std::min(static_cast<jsize>(n_batch), len - i));
        llama_batch batch = llama_batch_get_one(
                reinterpret_cast<llama_token *>(&token_arr[i]),
                batch_size);

        int32_t ret = llama_decode(session->ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed during prompt ingestion: %d", ret);
            succeeded = false;
            break;
        }
    }

    env->ReleaseIntArrayElements(tokens, token_arr, JNI_ABORT);
    return succeeded;
}

// ═══════════════════════════════════════════════
// 5. Sample from current logits (no decode)
//    Used after ingestPrompt to get the first
//    generated token.
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jint JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeSample(
        JNIEnv *, jobject, jlong ptr) {

    auto *session = reinterpret_cast<LlamaSession *>(ptr);

    const float *logits = llama_get_logits_ith(session->ctx, -1);
    if (!logits) {
        LOGE("No logits available for sampling");
        return -1;
    }

    return static_cast<jint>(greedy_sample(session->vocab, logits));
}

// ═══════════════════════════════════════════════
// 6. Single-step decode + greedy sample
//    Input: one token; Returns: next token id
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jint JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeDecode(
        JNIEnv *, jobject, jlong ptr, jint token) {

    auto *session = reinterpret_cast<LlamaSession *>(ptr);

    llama_token tok = static_cast<llama_token>(token);
    llama_batch batch = llama_batch_get_one(&tok, 1);

    int32_t ret = llama_decode(session->ctx, batch);
    if (ret != 0) {
        LOGE("llama_decode failed: %d", ret);
        return -1;
    }

    const float *logits = llama_get_logits_ith(session->ctx, -1);
    if (!logits) {
        LOGE("No logits after decode");
        return -1;
    }

    return static_cast<jint>(greedy_sample(session->vocab, logits));
}

// ═══════════════════════════════════════════════
// 7. Embedding
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeEmbed(
        JNIEnv *env, jobject, jlong ptr, jbyteArray text) {

    auto *session = reinterpret_cast<LlamaSession *>(ptr);

    llama_set_embeddings(session->ctx, true);
    llama_memory_t mem = llama_get_memory(session->ctx);
    llama_memory_clear(mem, true);

    const auto utf8 = utf8_bytes(env, text);
    const auto *str = utf8.data();
    int32_t text_len = static_cast<int32_t>(utf8.size());

    std::vector<llama_token> tokens(text_len + 4);
    int32_t n_tokens = llama_tokenize(
            session->vocab, str, text_len,
            tokens.data(), static_cast<int32_t>(tokens.size()),
            false, true);
    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(
                session->vocab, str, text_len,
                tokens.data(), static_cast<int32_t>(tokens.size()),
                false, true);
    }

    if (n_tokens <= 0) {
        LOGE("Embedding: tokenization returned %d tokens", n_tokens);
        llama_set_embeddings(session->ctx, false);
        llama_memory_clear(mem, true);
        return env->NewFloatArray(0);
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    int32_t ret = llama_decode(session->ctx, batch);
    if (ret != 0) {
        LOGE("Embedding: llama_decode failed: %d", ret);
        llama_set_embeddings(session->ctx, false);
        llama_memory_clear(mem, true);
        return env->NewFloatArray(0);
    }

    int32_t n_embd = llama_model_n_embd(
            llama_get_model(session->ctx));

    std::vector<float> embedding(static_cast<size_t>(n_embd), 0.0f);
    int32_t valid = 0;

    for (int32_t t = 0; t < n_tokens; t++) {
        const float *embd = llama_get_embeddings_ith(session->ctx, t);
        if (!embd) continue;
        valid++;
        for (int32_t i = 0; i < n_embd; i++) {
            embedding[static_cast<size_t>(i)] += embd[i];
        }
    }

    if (valid > 0) {
        for (int32_t i = 0; i < n_embd; i++) {
            embedding[static_cast<size_t>(i)] /= static_cast<float>(valid);
        }
    }

    llama_set_embeddings(session->ctx, false);
    llama_memory_clear(mem, true);

    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, embedding.data());
    return result;
}

// ═══════════════════════════════════════════════
// 8. Get EOS token
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeIsEndToken(
        JNIEnv *, jobject, jlong ptr, jint token) {
    auto *session = reinterpret_cast<LlamaSession *>(ptr);
    return llama_vocab_is_eog(session->vocab, token);
}

// ═══════════════════════════════════════════════
// 9. Clear KV cache
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeClear(
        JNIEnv *, jobject, jlong ptr) {
    auto *session = reinterpret_cast<LlamaSession *>(ptr);
    llama_memory_t mem = llama_get_memory(session->ctx);
    llama_memory_clear(mem, true);
}

// ═══════════════════════════════════════════════
// 10. Release
// ═══════════════════════════════════════════════
extern "C" JNIEXPORT void JNICALL
Java_com_promenar_infinity_data_local_inference_LlamaContext_nativeFree(
        JNIEnv *, jobject, jlong ptr) {
    auto *session = reinterpret_cast<LlamaSession *>(ptr);
    if (session->ctx)   llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    LOGI("Session released");
    delete session;
}
