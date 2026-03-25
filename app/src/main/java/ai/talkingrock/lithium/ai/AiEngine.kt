package ai.talkingrock.lithium.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

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
 * Manages the ONNX Runtime session lifecycle and runs real inference.
 *
 * Architecture:
 * - [loadModel] loads both the ONNX model and the WordPiece vocabulary from a directory.
 * - [classify] tokenizes the input, runs the ONNX session, and returns the top category.
 * - [releaseModel] frees native memory.
 *
 * Model files (sideloaded via ADB for the Pixel trial):
 * ```
 * context.filesDir/models/classification_v1.onnx   — the ONNX model
 * context.filesDir/models/vocab.txt                — BERT WordPiece vocabulary
 * ```
 *
 * When no model file is present, [isModelLoaded] returns false and [classify] returns null.
 * Callers must handle null gracefully — the heuristic classifier is the fallback.
 *
 * Thread safety: [loadModel] and [releaseModel] should be called from a single thread.
 * [classify] is safe to call from a background coroutine.
 */
@Singleton
class AiEngine @Inject constructor() {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val tokenizer = WordPieceTokenizer()

    /**
     * Loads the ONNX model and vocabulary from [modelDir].
     *
     * Looks for:
     * - `classification_v1.onnx` (or any `.onnx` file) in [modelDir]
     * - `vocab.txt` in [modelDir]
     *
     * Safe to call multiple times — replaces the previous session.
     * No-op if the model file or vocab file doesn't exist.
     *
     * @param modelDir Absolute path to the directory containing model and vocab files.
     */
    fun loadModel(modelDir: String) {
        val dir = File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "loadModel: directory not found at $modelDir — operating in no-op mode")
            return
        }

        // Find the ONNX model file (alphabetically first if multiple exist)
        val modelFile = dir.listFiles()
            ?.filter { it.extension == "onnx" }
            ?.minByOrNull { it.name }
        if (modelFile == null) {
            Log.w(TAG, "loadModel: no .onnx file found in $modelDir — operating in no-op mode")
            return
        }

        // Find the vocab file
        val vocabFile = File(dir, "vocab.txt")
        if (!vocabFile.exists()) {
            Log.w(TAG, "loadModel: vocab.txt not found in $modelDir — operating in no-op mode")
            return
        }

        try {
            releaseModel()

            // Load vocabulary
            if (!tokenizer.load(vocabFile.absolutePath)) {
                Log.e(TAG, "loadModel: failed to load vocabulary")
                return
            }

            // Create ONNX Runtime environment and session
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                env.createSession(modelFile.absolutePath, options)
            }

            ortEnvironment = env
            ortSession = session

            // Log model input/output info for debugging
            Log.i(TAG, "loadModel: session created for ${modelFile.name}")
            Log.i(TAG, "loadModel: inputs=${session.inputNames}, outputs=${session.outputNames}")
        } catch (e: Exception) {
            Log.e(TAG, "loadModel: failed to load model from $modelDir", e)
            releaseModel()
        }
    }

    /** Returns true if an ONNX session is currently loaded and ready for inference. */
    fun isModelLoaded(): Boolean = ortSession != null && tokenizer.isLoaded()

    /**
     * Runs inference on [text] using the loaded ONNX session.
     *
     * Pipeline:
     * 1. Tokenize [text] using WordPiece (BERT-style)
     * 2. Create input tensors (input_ids, attention_mask)
     * 3. Run ONNX session
     * 4. Extract logits from output
     * 5. Apply softmax
     * 6. Return argmax label and confidence
     *
     * Returns null if no model is loaded or if inference fails.
     *
     * @param text Pre-formatted input string (e.g. "[APP: com.example] [TITLE: Hello]")
     * @return [ClassificationResult] or null if no model is loaded.
     */
    fun classify(text: String): ClassificationResult? {
        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        var inputIdsTensor: OnnxTensor? = null
        var attentionMaskTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        return try {
            // Step 1: Tokenize
            val tokens = tokenizer.tokenize(text, WordPieceTokenizer.MAX_SEQ_LEN)
            val seqLen = tokens.inputIds.size.toLong()

            // Step 2: Create input tensors
            inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.inputIds),
                longArrayOf(1, seqLen)
            )
            attentionMaskTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.attentionMask),
                longArrayOf(1, seqLen)
            )

            // Step 3: Run inference
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            results = session.run(inputs)

            // Step 4: Extract logits
            // Standard HuggingFace text-classification output: float32[1, num_classes]
            @Suppress("UNCHECKED_CAST")
            val logits = when (val output = results[0].value) {
                is Array<*> -> (output as Array<FloatArray>)[0]
                is FloatArray -> output
                else -> {
                    Log.e(TAG, "classify: unexpected output type: ${output?.javaClass}")
                    return null
                }
            }

            // Step 5: Softmax
            if (logits.isEmpty()) {
                Log.e(TAG, "classify: model returned empty logits")
                return null
            }
            val probs = softmax(logits)

            // Step 6: Argmax → category label
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: return null
            if (maxIdx >= CATEGORY_LABELS.size) {
                Log.e(TAG, "classify: model output index $maxIdx exceeds label count ${CATEGORY_LABELS.size}")
                return null
            }

            val label = CATEGORY_LABELS[maxIdx]
            val confidence = probs[maxIdx]

            Log.d(TAG, "classify: result=$label confidence=${"%.3f".format(confidence)} " +
                    "(input length=${text.length}, logits=${logits.map { "%.2f".format(it) }})")

            ClassificationResult(label, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "classify: inference error for input length ${text.length}", e)
            null
        } finally {
            // Always close native resources, even on exception
            inputIdsTensor?.close()
            attentionMaskTensor?.close()
            results?.close()
        }
    }

    /**
     * Releases the current ONNX session and environment, freeing native memory.
     *
     * Called automatically by [loadModel] when replacing a session. Should also be called
     * at the end of each worker run to free resources between cycles.
     */
    fun releaseModel() {
        try {
            ortSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "releaseModel: error closing session", e)
        } finally {
            ortSession = null
        }

        try {
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.w(TAG, "releaseModel: error closing environment", e)
        } finally {
            ortEnvironment = null
        }
    }

    /** Computes softmax over a float array. */
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    companion object {
        private const val TAG = "AiEngine"

        /**
         * Label mapping: model output index → NotificationCategory label string.
         *
         * This must match the label ordering used during model training.
         * When fine-tuning a HuggingFace model, the label2id mapping in config.json
         * defines this order. Update this array if the training label order changes.
         */
        val CATEGORY_LABELS = arrayOf(
            "personal",         // 0
            "engagement_bait",  // 1
            "promotional",      // 2
            "transactional",    // 3
            "system",           // 4
            "social_signal",    // 5
            "unknown"           // 6
        )
    }
}
