package ai.talkingrock.lithium.ai

import android.util.Log
import java.io.File
import java.text.Normalizer

/**
 * BERT-style WordPiece tokenizer for ONNX classification models.
 *
 * Reads a standard `vocab.txt` file (one token per line, line number = token ID)
 * and implements the WordPiece algorithm used by DistilBERT, MobileBERT, and other
 * BERT-family models.
 *
 * Usage:
 * 1. Call [load] with the path to `vocab.txt`.
 * 2. Call [tokenize] with the input text and max sequence length.
 * 3. The returned [TokenizerOutput] contains `inputIds` and `attentionMask` as
 *    LongArrays ready for ONNX tensor creation.
 *
 * The vocab file is expected alongside the ONNX model file in the models directory
 * (e.g., `context.filesDir/models/vocab.txt`), sideloaded via ADB for the Pixel trial.
 */
class WordPieceTokenizer {

    private var vocab: Map<String, Int> = emptyMap()
    private var loaded = false

    /** Returns true if the vocabulary has been loaded successfully. */
    fun isLoaded(): Boolean = loaded

    /**
     * Loads the vocabulary from a `vocab.txt` file.
     *
     * Each line is one token; the line number (0-indexed) is the token ID.
     * Standard BERT vocab files have ~30,522 entries.
     *
     * @param vocabPath Absolute path to the vocab.txt file.
     * @return true if loaded successfully, false on error.
     */
    fun load(vocabPath: String): Boolean {
        val file = File(vocabPath)
        if (!file.exists()) {
            Log.w(TAG, "load: vocab file not found at $vocabPath")
            return false
        }

        return try {
            val entries = mutableMapOf<String, Int>()
            file.bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    // trimEnd handles Windows-style \r\n line endings in vocab files
                    entries[line.trimEnd()] = index
                }
            }
            vocab = entries
            loaded = entries.isNotEmpty()
            Log.i(TAG, "load: loaded ${entries.size} tokens from $vocabPath")
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "load: failed to read vocab file", e)
            false
        }
    }

    /**
     * Tokenizes [text] into BERT-format input tensors.
     *
     * Produces: `[CLS] token1 token2 ... tokenN [SEP] [PAD] [PAD] ...`
     *
     * @param text The input text to tokenize.
     * @param maxLen Maximum sequence length (including [CLS] and [SEP]). Default 128.
     * @return [TokenizerOutput] with inputIds and attentionMask arrays of length [maxLen].
     */
    fun tokenize(text: String, maxLen: Int = MAX_SEQ_LEN): TokenizerOutput {
        if (!loaded) {
            Log.w(TAG, "tokenize: vocab not loaded, returning empty tensors")
            return TokenizerOutput(LongArray(maxLen), LongArray(maxLen))
        }

        // Pre-tokenize: lowercase, strip accents, split on whitespace and punctuation
        val words = preTokenize(text)

        // WordPiece encode each word
        val tokenIds = mutableListOf<Int>()
        tokenIds.add(clsId())

        for (word in words) {
            val wordTokens = wordPieceEncode(word)
            // Reserve 1 for [SEP]
            if (tokenIds.size + wordTokens.size >= maxLen - 1) {
                // Truncate: add as many tokens as fit
                val remaining = maxLen - 1 - tokenIds.size
                tokenIds.addAll(wordTokens.take(remaining))
                break
            }
            tokenIds.addAll(wordTokens)
        }

        tokenIds.add(sepId())

        // Build output arrays
        val inputIds = LongArray(maxLen)
        val attentionMask = LongArray(maxLen)

        for (i in tokenIds.indices) {
            inputIds[i] = tokenIds[i].toLong()
            attentionMask[i] = 1L
        }
        // Remaining positions stay 0 (PAD token ID = 0, attention = 0)

        return TokenizerOutput(inputIds, attentionMask)
    }

    /**
     * Pre-tokenizes text using BERT-style rules:
     * - Lowercase
     * - Strip accents (NFD normalization, remove combining marks)
     * - Split on whitespace
     * - Split on punctuation (each punctuation char becomes its own token)
     */
    private fun preTokenize(text: String): List<String> {
        // Lowercase and normalize unicode
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        // Remove combining diacritical marks (accents)
        val stripped = normalized.replace(ACCENT_REGEX, "")

        val words = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in stripped) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        words.add(current.toString())
                        current.clear()
                    }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) {
                        words.add(current.toString())
                        current.clear()
                    }
                    words.add(ch.toString())
                }
                isControl(ch) -> {
                    // Skip control characters
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            words.add(current.toString())
        }

        return words
    }

    /**
     * Encodes a single word using the WordPiece algorithm.
     *
     * Greedily matches the longest prefix from the vocabulary. Subsequent subwords
     * use the "##" prefix. Unknown subwords map to [UNK].
     */
    private fun wordPieceEncode(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()

        // Check if the whole word is in vocab
        vocab[word]?.let { return listOf(it) }

        val tokens = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found = false

            while (start < end) {
                val substr = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##${word.substring(start, end)}"
                }

                val id = vocab[substr]
                if (id != null) {
                    tokens.add(id)
                    start = end
                    found = true
                    break
                }
                end--
            }

            if (!found) {
                // Character not in vocab — use [UNK] for the rest of the word
                tokens.add(unkId())
                break
            }
        }

        return tokens
    }

    private fun clsId(): Int = vocab["[CLS]"] ?: CLS_DEFAULT
    private fun sepId(): Int = vocab["[SEP]"] ?: SEP_DEFAULT
    private fun unkId(): Int = vocab["[UNK]"] ?: UNK_DEFAULT

    companion object {
        private const val TAG = "WordPieceTokenizer"

        /** Maximum sequence length for BERT-family models on notification text. */
        const val MAX_SEQ_LEN = 128

        // Standard BERT special token IDs (used as fallback if not found in vocab)
        private const val CLS_DEFAULT = 101
        private const val SEP_DEFAULT = 102
        private const val UNK_DEFAULT = 100

        /** Regex to strip combining diacritical marks after NFD normalization. */
        private val ACCENT_REGEX = Regex("[\\p{InCombiningDiacriticalMarks}]")

        /** Returns true if the character is punctuation (BERT definition). */
        private fun isPunctuation(ch: Char): Boolean {
            val cp = ch.code
            // ASCII punctuation ranges
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            // Unicode general punctuation
            return Character.getType(ch).toByte() in setOf(
                Character.CONNECTOR_PUNCTUATION,
                Character.DASH_PUNCTUATION,
                Character.END_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION,
                Character.START_PUNCTUATION
            )
        }

        /** Returns true if the character is a control character (should be skipped). */
        private fun isControl(ch: Char): Boolean {
            if (ch == '\t' || ch == '\n' || ch == '\r') return false
            return Character.getType(ch).toByte() == Character.CONTROL
        }
    }
}

/**
 * Output of [WordPieceTokenizer.tokenize].
 *
 * Both arrays are of length `maxLen` and can be wrapped in LongBuffer for
 * ONNX tensor creation.
 */
data class TokenizerOutput(
    val inputIds: LongArray,
    val attentionMask: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizerOutput) return false
        return inputIds.contentEquals(other.inputIds) && attentionMask.contentEquals(other.attentionMask)
    }

    override fun hashCode(): Int {
        return 31 * inputIds.contentHashCode() + attentionMask.contentHashCode()
    }
}
