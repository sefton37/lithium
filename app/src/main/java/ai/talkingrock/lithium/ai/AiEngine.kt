package ai.talkingrock.lithium.ai

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result returned by [AiEngine.classify].
 *
 * [label] matches one of the [NotificationCategory] label strings.
 * [confidence] is in [0.0, 1.0].
 */
data class ClassificationResult(
    val label: String,
    val confidence: Float
)

/**
 * Manages the ONNX Runtime session lifecycle: load, infer, release.
 *
 * MVP implementation: the ONNX model is loaded from a file path supplied at runtime via
 * [loadModel]. When no model file has been downloaded yet, [isModelLoaded] returns false
 * and [classify] returns `null`. Callers must handle `null` gracefully — log and skip,
 * no crash.
 *
 * Full autoregressive decode-loop inference will be integrated here when the model export
 * format is confirmed (see PLAN.md §M3.2). For now this class holds the session lifecycle
 * contract so callers and DI bindings are wired correctly before the model exists.
 *
 * Thread safety: [loadModel] and [releaseModel] should be called from a single thread.
 * [classify] is safe to call from a background coroutine.
 */
@Singleton
class AiEngine @Inject constructor() {

    // OrtEnvironment and OrtSession are managed here. The actual imports are deferred until
    // a model file is loaded so that ONNX Runtime initialisation cost is not paid at app
    // startup when no model exists.
    //
    // The fields are typed as Any? to avoid a hard compile-time dependency on ONNX runtime
    // symbols while keeping the class self-contained. When full inference is wired in, these
    // should be typed as OrtEnvironment and OrtSession respectively.
    private var ortEnvironment: Any? = null
    private var ortSession: Any? = null

    /**
     * Loads the ONNX model from [modelPath] into memory.
     *
     * Safe to call multiple times — calling with a new path releases the previous session
     * before loading the new one. No-op if [modelPath] points to a file that does not exist.
     *
     * @param modelPath Absolute path to the `.onnx` model file in app-private storage.
     */
    fun loadModel(modelPath: String) {
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            Log.w(TAG, "loadModel: model file not found at $modelPath — operating in no-op mode")
            return
        }

        try {
            releaseModel()

            // Reflective instantiation keeps this file compilable before ONNX Runtime is
            // configured and allows the heuristic classifier to function without a model.
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val getInstanceMethod = envClass.getMethod("getEnvironment")
            val env = getInstanceMethod.invoke(null)

            val createSessionMethod = envClass.getMethod(
                "createSession",
                String::class.java,
                Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            )
            val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val options = optionsClass.getDeclaredConstructor().newInstance()

            ortEnvironment = env
            ortSession = createSessionMethod.invoke(env, modelPath, options)
            Log.i(TAG, "loadModel: session created for $modelPath")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "loadModel: ONNX Runtime not available on classpath — no-op mode", e)
        } catch (e: Exception) {
            Log.e(TAG, "loadModel: failed to load model from $modelPath", e)
            releaseModel()
        }
    }

    /** Returns true if an ONNX session is currently loaded and ready for inference. */
    fun isModelLoaded(): Boolean = ortSession != null

    /**
     * Runs inference on [text] using the loaded ONNX session.
     *
     * Returns `null` if no model is loaded. This is the expected path when the model has
     * not been downloaded yet — callers should fall through to the heuristic classifier or
     * simply skip classification.
     *
     * When full autoregressive inference is implemented, the output label and confidence
     * will be extracted from the session output tensors here.
     *
     * @param text Pre-formatted input string (e.g. "[APP: com.example] [TITLE: Hello]")
     * @return [ClassificationResult] or `null` if no model is loaded.
     */
    fun classify(text: String): ClassificationResult? {
        if (!isModelLoaded()) return null

        return try {
            // Full ONNX inference decode loop is not yet implemented.
            // This stub returns null so the caller falls through to the heuristic classifier.
            // When the model export format is confirmed (PLAN.md §M3.2), replace this block
            // with actual tokenization → session.run() → argmax logic.
            Log.d(TAG, "classify: ONNX session present but inference not yet implemented — deferring to heuristic")
            null
        } catch (e: Exception) {
            Log.e(TAG, "classify: inference error for input length ${text.length}", e)
            null
        }
    }

    /**
     * Releases the current ONNX session and environment, freeing native memory.
     *
     * Called automatically by [loadModel] when replacing a session. Should also be called
     * in the application's onTerminate callback, though Android does not guarantee that
     * callback fires.
     */
    fun releaseModel() {
        try {
            ortSession?.let {
                it.javaClass.getMethod("close").invoke(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseModel: error closing session", e)
        } finally {
            ortSession = null
        }

        try {
            ortEnvironment?.let {
                it.javaClass.getMethod("close").invoke(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseModel: error closing environment", e)
        } finally {
            ortEnvironment = null
        }
    }

    companion object {
        private const val TAG = "AiEngine"
    }
}
