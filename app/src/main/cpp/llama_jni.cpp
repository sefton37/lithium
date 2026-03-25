/**
 * JNI bridge between Kotlin's LlamaCpp.kt and the llama.cpp C API.
 *
 * This file implements three JNI functions:
 * - nativeLoadModel: loads a GGUF model and returns a context handle
 * - nativeRunInference: runs text completion on a prompt
 * - nativeFreeModel: releases the context and model
 *
 * Build requirements:
 * - llama.cpp source vendored at vendor/llama.cpp/ (or adjust CMakeLists.txt)
 * - Android NDK r26+ with arm64-v8a support
 * - Build flag: ./gradlew :app:assembleDebug -PenableLlama
 *
 * The bridge is intentionally minimal — all prompt construction and output parsing
 * happens in Kotlin (LlamaEngine.kt). This layer handles only model lifecycle and
 * raw text generation.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// llama.cpp headers — paths relative to vendor/llama.cpp/include/
#include "llama.h"

#define TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Opaque context wrapper holding both the model and context.
 * Stored as a jlong handle passed to/from Kotlin.
 */
struct LlamaContext {
    llama_model *model;
    llama_context *ctx;
    int n_ctx;
};

// -- Inline batch helpers (these were removed from the llama.cpp public API) --

/** Reset a batch to empty without freeing its memory. */
static void batch_clear(llama_batch &batch) {
    batch.n_tokens = 0;
}

/** Add a single token to the batch. */
static void batch_add(llama_batch &batch, llama_token id, llama_pos pos,
                       const std::vector<llama_seq_id> &seq_ids, bool output_logits) {
    int i = batch.n_tokens;
    batch.token[i]    = id;
    batch.pos[i]      = pos;
    batch.n_seq_id[i] = static_cast<int32_t>(seq_ids.size());
    for (size_t s = 0; s < seq_ids.size(); s++) {
        batch.seq_id[i][s] = seq_ids[s];
    }
    batch.logits[i] = output_logits ? 1 : 0;
    batch.n_tokens++;
}

extern "C" {

/**
 * Load a GGUF model file and create an inference context.
 *
 * @param modelPath Absolute path to the .gguf file.
 * @param contextSize Context window size in tokens.
 * @param threads Number of CPU threads for inference.
 * @return Pointer to LlamaContext cast to jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_ai_talkingrock_lithium_ai_LlamaCpp_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring modelPath, jint contextSize, jint threads) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("nativeLoadModel: failed to get model path string");
        return 0;
    }

    LOGI("nativeLoadModel: loading %s (ctx=%d, threads=%d)", path, contextSize, threads);

    // Initialize llama backend (safe to call multiple times)
    llama_backend_init();

    // Load model
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("nativeLoadModel: failed to load model");
        return 0;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("nativeLoadModel: failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *wrapper = new LlamaContext{model, ctx, contextSize};
    LOGI("nativeLoadModel: model loaded successfully");
    return reinterpret_cast<jlong>(wrapper);
}

/**
 * Run text generation on the loaded model.
 *
 * @param handle Pointer to LlamaContext (from nativeLoadModel).
 * @param prompt The full prompt string.
 * @param maxTokens Maximum tokens to generate.
 * @return Generated text as a Java string.
 */
JNIEXPORT jstring JNICALL
Java_ai_talkingrock_lithium_ai_LlamaCpp_nativeRunInference(
        JNIEnv *env, jobject /* this */,
        jlong handle, jstring prompt, jint maxTokens) {

    auto *wrapper = reinterpret_cast<LlamaContext *>(handle);
    if (!wrapper || !wrapper->ctx) {
        LOGE("nativeRunInference: invalid handle");
        return env->NewStringUTF("");
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (!promptStr) {
        LOGE("nativeRunInference: failed to get prompt string");
        return env->NewStringUTF("");
    }

    // Tokenize the prompt
    const llama_vocab *vocab = llama_model_get_vocab(wrapper->model);
    int n_tokens_max = wrapper->n_ctx;  // context size is the hard upper bound
    std::vector<llama_token> tokens(n_tokens_max);

    int prompt_len = static_cast<int>(strlen(promptStr));
    int n_tokens = llama_tokenize(vocab, promptStr, prompt_len,
                                  tokens.data(), n_tokens_max,
                                  /* add_special= */ true, /* parse_special= */ false);
    env->ReleaseStringUTFChars(prompt, promptStr);

    if (n_tokens < 0) {
        LOGE("nativeRunInference: tokenization failed (n_tokens=%d)", n_tokens);
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // Clear memory (KV cache) for fresh inference
    llama_memory_clear(llama_get_memory(wrapper->ctx), true);

    // Process prompt tokens in a single batch
    llama_batch batch = llama_batch_init(n_tokens + maxTokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch_add(batch, tokens[i], i, {0}, false);
    }
    // Enable logits for the last prompt token
    batch.logits[batch.n_tokens - 1] = 1;

    if (llama_decode(wrapper->ctx, batch) != 0) {
        LOGE("nativeRunInference: prompt decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Generate tokens
    std::string result;
    int n_cur = n_tokens;
    int n_vocab_size = llama_vocab_n_tokens(vocab);

    for (int i = 0; i < maxTokens; i++) {
        // Logits are available from the most recent llama_decode call.
        // After prompt decode: logits for the last prompt token (batch index n_tokens-1).
        // After generation decode: logits for the single token (batch index 0).
        int logits_idx = (i == 0) ? (n_tokens - 1) : 0;
        float *logits = llama_get_logits_ith(wrapper->ctx, logits_idx);

        // Sample greedily (argmax) — deterministic for classification
        llama_token best_id = 0;
        float best_logit = logits[0];
        for (int v = 1; v < n_vocab_size; v++) {
            if (logits[v] > best_logit) {
                best_logit = logits[v];
                best_id = static_cast<llama_token>(v);
            }
        }

        // Check for EOS
        if (llama_vocab_is_eog(vocab, best_id)) {
            break;
        }

        // Decode the chosen token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, best_id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            result.append(buf, n);
        }

        // Check for newline (end of classification output)
        auto nl = result.find('\n');
        if (nl != std::string::npos) {
            result.resize(nl);
            break;
        }

        // Prepare next batch with the new token
        batch_clear(batch);
        batch_add(batch, best_id, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(wrapper->ctx, batch) != 0) {
            LOGE("nativeRunInference: decode failed at step %d", i);
            break;
        }
    }

    llama_batch_free(batch);

    LOGI("nativeRunInference: generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

/**
 * Free the model context and release all native memory.
 *
 * @param handle Pointer to LlamaContext (from nativeLoadModel).
 */
JNIEXPORT void JNICALL
Java_ai_talkingrock_lithium_ai_LlamaCpp_nativeFreeModel(
        JNIEnv * /* env */, jobject /* this */, jlong handle) {

    auto *wrapper = reinterpret_cast<LlamaContext *>(handle);
    if (!wrapper) return;

    if (wrapper->ctx) {
        llama_free(wrapper->ctx);
    }
    if (wrapper->model) {
        llama_model_free(wrapper->model);
    }
    delete wrapper;

    LOGI("nativeFreeModel: context freed");
}

} // extern "C"
