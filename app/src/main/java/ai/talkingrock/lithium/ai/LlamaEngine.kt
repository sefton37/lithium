package ai.talkingrock.lithium.ai

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device generative classification engine using llama.cpp (GGUF models).
 *
 * Uses few-shot prompting to classify notifications into one of the seven
 * [NotificationCategory] labels. No fine-tuning required — the model receives
 * structured examples in the prompt and returns a single category label.
 *
 * Model file (sideloaded via ADB for the Pixel trial):
 * ```
 * context.filesDir/models/llm_v1.gguf
 * ```
 *
 * Recommended models (INT4 quantised):
 * - SmolLM-135M (~270MB Q4_K_M) — fastest, lowest quality
 * - Qwen2-0.5B (~350MB Q4_K_M) — better quality, still fast
 * - Qwen2.5-1.5B (~900MB Q4_K_M) — best quality, ~500ms per notification
 *
 * Thread safety: [loadModel] and [releaseModel] should be called from a single thread.
 * [classify] is safe to call from a background coroutine but is NOT concurrent-safe
 * (llama.cpp contexts are single-threaded).
 */
@Singleton
class LlamaEngine @Inject constructor() {

    private var modelHandle: Long = 0L

    /** Mutex guarding inference — llama.cpp contexts are single-threaded. */
    private val inferenceMutex = Mutex()

    /** Returns true if a model is loaded and the native library is available. */
    fun isModelLoaded(): Boolean = modelHandle != 0L && LlamaCpp.isAvailable

    /**
     * Loads a GGUF model from [modelDir].
     *
     * Looks for any `.gguf` file in the directory. If multiple exist, uses the first
     * one found (alphabetically).
     *
     * @param modelDir Absolute path to the directory containing the GGUF file.
     */
    fun loadModel(modelDir: String) {
        if (!LlamaCpp.isAvailable) {
            Log.w(TAG, "loadModel: llama.cpp native library not available")
            return
        }

        val dir = File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "loadModel: directory not found at $modelDir")
            return
        }

        val modelFile = dir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.minByOrNull { it.name }
        if (modelFile == null) {
            Log.w(TAG, "loadModel: no .gguf file found in $modelDir")
            return
        }

        releaseModel()

        val handle = LlamaCpp.loadModel(
            modelPath = modelFile.absolutePath,
            contextSize = CONTEXT_SIZE,
            threads = INFERENCE_THREADS
        )

        if (handle != 0L) {
            modelHandle = handle
            Log.i(TAG, "loadModel: loaded ${modelFile.name} (handle=$handle)")
        } else {
            Log.e(TAG, "loadModel: failed to load ${modelFile.name}")
        }
    }

    /**
     * Classifies a notification using few-shot prompting.
     *
     * Builds a structured prompt with examples and the target notification,
     * runs inference, and parses the output to extract a category label.
     *
     * @param packageName App package name.
     * @param channelId Notification channel ID (nullable).
     * @param title Notification title (nullable).
     * @param text Notification text (nullable).
     * @return [ClassificationResult] or null if inference fails or output can't be parsed.
     */
    suspend fun classify(
        packageName: String,
        channelId: String?,
        title: String?,
        text: String?
    ): ClassificationResult? {
        if (!isModelLoaded()) return null

        val prompt = buildClassificationPrompt(packageName, channelId, title, text)

        // Mutex ensures llama.cpp context is not accessed concurrently
        return inferenceMutex.withLock {
            val startMs = System.currentTimeMillis()
            val output = LlamaCpp.runInference(modelHandle, prompt, MAX_OUTPUT_TOKENS)
            val elapsedMs = System.currentTimeMillis() - startMs

            Log.d(TAG, "classify: inference took ${elapsedMs}ms, raw output=\"$output\"")

            if (output.isBlank()) return@withLock null

            parseClassificationOutput(output)
        }
    }

    /** Releases the native model context. */
    /**
     * Loads a GGUF model with an explicit context size.
     *
     * Intended for generative tasks (rule extraction, chat) that need a larger context
     * window than the default classification context. Delegates to the primary
     * [loadModel] after temporarily overriding the context via the [contextSize] param.
     *
     * @param modelDir Absolute path to the directory containing the GGUF file.
     * @param contextSize Token context window size (use [GENERATIVE_CONTEXT_SIZE] for chat).
     */
    fun loadModel(modelDir: String, contextSize: Int) {
        if (!LlamaCpp.isAvailable) {
            Log.w(TAG, "loadModel: llama.cpp native library not available")
            return
        }

        val dir = File(modelDir)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "loadModel: directory not found at $modelDir")
            return
        }

        val modelFile = dir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.minByOrNull { it.name }
        if (modelFile == null) {
            Log.w(TAG, "loadModel: no .gguf file found in $modelDir")
            return
        }

        releaseModel()

        val handle = LlamaCpp.loadModel(
            modelPath = modelFile.absolutePath,
            contextSize = contextSize,
            threads = INFERENCE_THREADS
        )

        if (handle != 0L) {
            modelHandle = handle
            Log.i(TAG, "loadModel: loaded ${modelFile.name} (handle=$handle, ctx=$contextSize)")
        } else {
            Log.e(TAG, "loadModel: failed to load ${modelFile.name}")
        }
    }

    /**
     * Runs free-form text generation given a [prompt].
     *
     * Unlike [classify], this method returns the raw output string without parsing.
     * Intended for generative tasks (rule extraction, chat) where the caller
     * is responsible for parsing the output.
     *
     * @param prompt Full prompt string to feed to the model.
     * @param maxTokens Maximum number of tokens to generate.
     * @return The generated text, or an empty string if inference fails or model not loaded.
     */
    suspend fun generate(prompt: String, maxTokens: Int): String {
        if (modelHandle == 0L) {
            Log.w(TAG, "generate: model not loaded")
            return ""
        }
        return try {
            LlamaCpp.runInference(modelHandle, prompt, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "generate: inference failed", e)
            ""
        }
    }

    fun releaseModel() {
        if (modelHandle != 0L) {
            LlamaCpp.freeModel(modelHandle)
            Log.i(TAG, "releaseModel: freed handle=$modelHandle")
            modelHandle = 0L
        }
    }

    /**
     * Builds a few-shot classification prompt.
     *
     * Uses a chat-like format that small models handle well.
     * Examples cover the most important classification boundaries.
     */
    private fun buildClassificationPrompt(
        packageName: String,
        channelId: String?,
        title: String?,
        text: String?
    ): String = buildString {
        appendLine(SYSTEM_PROMPT)
        appendLine()
        // Few-shot examples
        for (example in FEW_SHOT_EXAMPLES) {
            appendLine("[USER]")
            appendLine(example.input)
            appendLine("[ASSISTANT]")
            appendLine(example.output)
            appendLine()
        }
        // Target notification
        appendLine("[USER]")
        append("[APP: $packageName]")
        channelId?.let { append(" [CHANNEL: $it]") }
        title?.let { append(" [TITLE: $it]") }
        text?.let { append(" [TEXT: $it]") }
        appendLine()
        appendLine("[ASSISTANT]")
    }

    /**
     * Parses the model output to extract a category label.
     *
     * Strips whitespace, lowercases, and matches against valid category labels.
     * If the output contains multiple words, takes the first word that matches.
     */
    private fun parseClassificationOutput(output: String): ClassificationResult? {
        val cleaned = output.trim().lowercase()

        // Try exact match first
        val exactMatch = NotificationCategory.fromLabel(cleaned)
        if (exactMatch != NotificationCategory.UNKNOWN) {
            return ClassificationResult(exactMatch.label, DEFAULT_CONFIDENCE)
        }

        // Try to find a valid label anywhere in the output
        for (category in NotificationCategory.entries) {
            if (category == NotificationCategory.UNKNOWN) continue
            if (cleaned.contains(category.label)) {
                return ClassificationResult(category.label, PARTIAL_MATCH_CONFIDENCE)
            }
        }

        Log.w(TAG, "parseClassificationOutput: could not parse output: \"$output\"")
        return null
    }

    companion object {
        private const val TAG = "LlamaEngine"

        /** Context window size — 512 tokens is plenty for classification prompts. */
        private const val CONTEXT_SIZE = 512

        /**
         * Larger context window for generative tasks (rule extraction, chat).
         * Distinct from [CONTEXT_SIZE] so classification and generation can
         * run with different llama.cpp context configurations if needed.
         */
        const val GENERATIVE_CONTEXT_SIZE = 2048

        /** Number of CPU threads for inference. Pixel 8 Pro has 8 cores. */
        private const val INFERENCE_THREADS = 4

        /** Maximum output tokens — a single category label is 1-3 tokens. */
        private const val MAX_OUTPUT_TOKENS = 16

        /** Confidence assigned to a clean exact-match parse. */
        private const val DEFAULT_CONFIDENCE = 0.75f

        /** Confidence assigned when the label is found but output was noisy. */
        private const val PARTIAL_MATCH_CONFIDENCE = 0.60f

        private const val SYSTEM_PROMPT = """[SYSTEM] You are a notification classifier. Given a mobile notification, reply with exactly one category name. Do not explain.
Categories: personal, engagement_bait, promotional, transactional, system, social_signal"""

        /**
         * Few-shot examples covering key classification boundaries.
         * Keep minimal (6 examples) to stay within 512-token context.
         */
        private val FEW_SHOT_EXAMPLES = listOf(
            Example(
                "[APP: com.whatsapp] [TITLE: Alice] [TEXT: Hey, are you free for dinner tonight?]",
                "personal"
            ),
            Example(
                "[APP: com.instagram.android] [CHANNEL: recommended] [TITLE: Instagram] [TEXT: You might like posts from travel_adventures]",
                "engagement_bait"
            ),
            Example(
                "[APP: com.amazon.mShop.android.shopping] [TITLE: Lightning Deal] [TEXT: 50% off headphones — deal ends in 2 hours!]",
                "promotional"
            ),
            Example(
                "[APP: com.google.android.gm] [TITLE: Your verification code] [TEXT: Your code is 847291. Do not share this code.]",
                "transactional"
            ),
            Example(
                "[APP: com.android.systemui] [TITLE: Battery] [TEXT: Battery low — 15% remaining]",
                "system"
            ),
            Example(
                "[APP: com.twitter.android] [TITLE: Twitter] [TEXT: @johndoe liked your tweet]",
                "social_signal"
            ),
        )
    }

    private data class Example(val input: String, val output: String)
}
