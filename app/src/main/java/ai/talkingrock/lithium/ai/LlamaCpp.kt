package ai.talkingrock.lithium.ai

import android.util.Log

/**
 * JNI bridge to llama.cpp for on-device generative inference.
 *
 * This object wraps the native `llama_jni` library, providing three operations:
 * - [loadModel]: Load a GGUF model file and return a context handle.
 * - [runInference]: Run text generation given a prompt and max token count.
 * - [freeModel]: Release the native context and free memory.
 *
 * The native library is built from `app/src/main/cpp/llama_jni.cpp` using CMake + NDK.
 * It links against the vendored llama.cpp source (see `app/src/main/cpp/vendor/`).
 *
 * Build requirements:
 * - Android NDK installed (r26+ recommended)
 * - llama.cpp source vendored at `app/src/main/cpp/vendor/llama.cpp/`
 * - Build flag: `./gradlew :app:assembleDebug -PenableLlama`
 *
 * When the native library is not available (e.g., NDK not installed, llama not vendored),
 * all methods return safe defaults and [isAvailable] returns false. The app does not crash.
 */
object LlamaCpp {

    private const val TAG = "LlamaCpp"

    /**
     * Whether the native library was loaded successfully.
     * If false, all JNI methods are no-ops.
     */
    var isAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("llama_jni")
            isAvailable = true
            Log.i(TAG, "Native llama_jni library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "llama_jni library not available — llama.cpp inference disabled", e)
            isAvailable = false
        }
    }

    /**
     * Loads a GGUF model file into memory and returns a native context handle.
     *
     * @param modelPath Absolute path to the `.gguf` model file.
     * @param contextSize Context window size in tokens (default 512 — sufficient for
     *                    notification classification prompts which are < 200 tokens).
     * @param threads Number of CPU threads to use for inference (default 4).
     * @return A non-zero handle on success, or 0 on failure.
     */
    fun loadModel(modelPath: String, contextSize: Int = 512, threads: Int = 4): Long {
        if (!isAvailable) return 0L
        return try {
            nativeLoadModel(modelPath, contextSize, threads)
        } catch (e: Exception) {
            Log.e(TAG, "loadModel failed", e)
            0L
        }
    }

    /**
     * Runs text generation on the loaded model.
     *
     * @param handle The model context handle returned by [loadModel].
     * @param prompt The full prompt string (system + user + assistant prefix).
     * @param maxTokens Maximum tokens to generate. Keep small for classification (32).
     * @return The generated text, or an empty string on failure.
     */
    fun runInference(handle: Long, prompt: String, maxTokens: Int = 32): String {
        if (!isAvailable || handle == 0L) return ""
        return try {
            nativeRunInference(handle, prompt, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "runInference failed", e)
            ""
        }
    }

    /**
     * Frees the native model context and releases all associated memory.
     *
     * @param handle The model context handle returned by [loadModel].
     */
    fun freeModel(handle: Long) {
        if (!isAvailable || handle == 0L) return
        try {
            nativeFreeModel(handle)
        } catch (e: Exception) {
            Log.e(TAG, "freeModel failed", e)
        }
    }

    // -- JNI native declarations --

    private external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Long
    private external fun nativeRunInference(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFreeModel(handle: Long)
}
