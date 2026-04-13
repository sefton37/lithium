package ai.talkingrock.lithium.ai

import android.util.Log
import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.ui.chat.RuleDraftFields
import ai.talkingrock.lithium.ui.chat.RuleDraftState
import javax.inject.Inject
import javax.inject.Singleton

/** Label/package pair injected into extraction prompts so the model can map "Gmail" to "com.google.android.gm". */
data class KnownApp(val packageName: String, val label: String)

/**
 * Converts a free-form rule description into a structured [RuleDraftState] by
 * running one focused LLM prompt per field. Each field the model cannot resolve
 * reliably is left null for the user to fill in.
 *
 * Callers are responsible for ensuring [LlamaEngine] has a model loaded
 * (ideally with [LlamaEngine.GENERATIVE_CONTEXT_SIZE]) before invoking this class.
 */
@Singleton
class RuleExtractor @Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val notificationRepository: NotificationRepository,
    private val appLabelResolver: AppLabelResolver,
) {

    /** Fields extracted, in order. Number is surfaced in UI progress messages. */
    val totalFields: Int = 6

    /**
     * Optional context that callers can inject in place of repository lookups.
     * The eval harness uses this to feed synthetic app + contact lists; production
     * callers pass null so the extractor reads from the real repository.
     *
     * @param packages override for the known-packages list (null = use repo)
     * @param contacts contact names to ground the contact-filter prompt (empty OK)
     */
    data class Context(
        val apps: List<KnownApp>? = null,
        val contacts: List<String> = emptyList(),
    )

    /**
     * Optional progress sink — invoked after each field is extracted (1-based).
     * Tests ignore it.
     */
    interface ProgressListener {
        fun onFieldComplete(fieldsComplete: Int, totalFields: Int)
    }

    /**
     * Extracts all fields from scratch.
     *
     * If the model is unavailable, returns a draft with null fields and default
     * action="suppress" so the UI can still surface a blank form for manual entry.
     */
    suspend fun extract(
        userInput: String,
        progress: ProgressListener? = null,
        context: Context? = null,
    ): RuleDraftState {
        val known = context?.apps ?: safeKnownApps()
        val contacts = context?.contacts ?: emptyList()
        if (!llamaEngine.isModelLoaded()) {
            Log.w(TAG, "extract: model not loaded — returning blank draft")
            return RuleDraftState(originalInput = userInput)
        }

        var complete = 0
        fun tick() {
            complete += 1
            progress?.onFieldComplete(complete, totalFields)
        }

        val pkg = extractPackage(userInput, known).also { tick() }
        val channel = extractChannel(userInput).also { tick() }
        val category = extractCategory(userInput).also { tick() }
        val notFromContact = extractNotFromContact(userInput, contacts).also { tick() }
        val action = extractAction(userInput).also { tick() }
        val (textKeyword, textOperator) = extractTextFilter(userInput).also { tick() }

        return RuleDraftState(
            originalInput = userInput,
            packageName = pkg,
            channelId = channel,
            category = category,
            notFromContact = notFromContact,
            action = action,
            textKeyword = textKeyword,
            textOperator = textOperator,
        )
    }

    /**
     * Re-runs extraction using `"${existing.originalInput}. $followUp"` as the new input,
     * then merges results — preserving any field the user has manually edited.
     */
    suspend fun refine(
        existing: RuleDraftState,
        followUp: String,
        progress: ProgressListener? = null,
        context: Context? = null,
    ): RuleDraftState {
        val combinedInput = "${existing.originalInput}. $followUp".trim()
        val fresh = extract(combinedInput, progress, context)
        val edited = existing.userEditedFields
        return fresh.copy(
            originalInput = combinedInput,
            packageName = if (RuleDraftFields.PACKAGE in edited) existing.packageName else fresh.packageName,
            channelId = if (RuleDraftFields.CHANNEL in edited) existing.channelId else fresh.channelId,
            category = if (RuleDraftFields.CATEGORY in edited) existing.category else fresh.category,
            notFromContact = if (RuleDraftFields.NOT_FROM_CONTACT in edited) existing.notFromContact else fresh.notFromContact,
            action = if (RuleDraftFields.ACTION in edited) existing.action else fresh.action,
            textKeyword = if (RuleDraftFields.TEXT_KEYWORD in edited) existing.textKeyword else fresh.textKeyword,
            textOperator = if (RuleDraftFields.TEXT_OPERATOR in edited) existing.textOperator else fresh.textOperator,
            userEditedFields = existing.userEditedFields,
        )
    }

    // -----------------------------------------------------------------------
    // Per-field extractors
    // -----------------------------------------------------------------------

    private suspend fun extractPackage(input: String, knownApps: List<KnownApp>): String? {
        // Inject "Label (package.name)" pairs so the model can map human names to
        // opaque package strings like com.google.android.gm.
        val shortList = knownApps.take(MAX_KNOWN_PACKAGES)
            .joinToString(", ") { "${it.label} (${it.packageName})" }
        val prompt = prompt(
            field = "app package name",
            instructions = "Choose the package that best matches the app the user mentions. " +
                "Reply with exactly one package name from the list below (the part in " +
                "parentheses), or 'unknown' if no listed app matches.",
            input = input,
            extra = "Known apps (label followed by package): $shortList",
        )
        val raw = llamaEngine.generate(prompt, MAX_FIELD_TOKENS).trim().lowercase()
        val cleaned = firstToken(raw)
        if (cleaned.isBlank() || cleaned == "unknown") return null
        // Grounding: only accept package names the device has actually observed.
        return knownApps.firstOrNull { it.packageName.equals(cleaned, ignoreCase = true) }?.packageName
    }

    private suspend fun extractChannel(input: String): String? {
        val prompt = prompt(
            field = "notification channel id",
            instructions = "If the user mentions a specific channel (e.g. 'dm_alerts', " +
                "'promotions'), reply with that id. Otherwise reply 'unknown'.",
            input = input,
        )
        val raw = llamaEngine.generate(prompt, MAX_FIELD_TOKENS).trim().lowercase()
        val cleaned = firstToken(raw)
        return if (cleaned.isBlank() || cleaned == "unknown") null else cleaned
    }

    private suspend fun extractCategory(input: String): String? {
        val prompt = prompt(
            field = "notification category",
            instructions = "Reply with exactly one label from: personal, engagement_bait, " +
                "promotional, transactional, system, social_signal, background. Reply " +
                "'unknown' if the description does not clearly target one category.",
            input = input,
        )
        val raw = llamaEngine.generate(prompt, MAX_FIELD_TOKENS).trim().lowercase()
        val valid = setOf(
            "personal", "engagement_bait", "promotional", "transactional",
            "system", "social_signal", "background",
        )
        return valid.firstOrNull { raw.contains(it) }
    }

    private suspend fun extractNotFromContact(input: String, contacts: List<String>): Boolean {
        val contactList = contacts.take(MAX_CONTACTS)
            .joinToString(", ")
            .ifBlank { "(contact list unavailable)" }
        val prompt = prompt(
            field = "contact-only filter",
            instructions = "Reply 'yes' if the rule should only apply to senders who are " +
                "NOT in the user's contacts. Reply 'no' if the rule targets a specific " +
                "contact by name or does not depend on contact status.",
            input = input,
            extra = "Known contacts (for reference): $contactList",
        )
        val raw = llamaEngine.generate(prompt, MAX_FIELD_TOKENS).trim().lowercase()
        return raw.contains("yes") || raw.contains("not a contact") || raw.contains("non-contact")
    }

    /**
     * Extracts a keyword filter and operator. The keyword is matched as a literal
     * case-insensitive substring against notification title+body at rule-eval time,
     * so the model should emit a short, distinctive word or phrase — not a paraphrase.
     *
     * Returns (null, "contains") when the user expresses no keyword constraint.
     */
    private suspend fun extractTextFilter(input: String): Pair<String?, String> {
        val kwPrompt = prompt(
            field = "keyword substring",
            instructions = "If the rule targets notifications whose TEXT contains (or does " +
                "not contain) a specific word or short phrase, reply with that literal " +
                "word/phrase only (1-4 words, no quotes, no explanation). Examples: " +
                "'deal', 'shipped', 'breaking news', 'from the economist'. Reply 'none' " +
                "if the rule is only about app/channel/category/contact, not content words.",
            input = input,
        )
        val rawKw = llamaEngine.generate(kwPrompt, MAX_FIELD_TOKENS).trim()
        val keyword = rawKw
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.trim('"', '\'', '.', ',', ':', ';', '(', ')')
            .orEmpty()
        if (keyword.isBlank() || keyword.equals("none", ignoreCase = true) ||
            keyword.equals("unknown", ignoreCase = true) || keyword.length > 60) {
            return null to "contains"
        }
        val opPrompt = prompt(
            field = "keyword operator",
            instructions = "Reply with exactly one: 'contains' (block/target notifications " +
                "that HAVE this word) or 'not_contains' (block/target notifications that " +
                "LACK this word, e.g. 'only show X' or 'except when it says Y').",
            input = input,
            extra = "Keyword in question: \"$keyword\"",
        )
        val rawOp = llamaEngine.generate(opPrompt, MAX_FIELD_TOKENS).trim().lowercase()
        val op = if (rawOp.contains("not_contain") || rawOp.contains("not contain") ||
            rawOp.contains("doesn't contain") || rawOp.contains("does not contain") ||
            rawOp.contains("lack") || rawOp.contains("without") || rawOp.contains("except")
        ) "not_contains" else "contains"
        return keyword to op
    }

    private suspend fun extractAction(input: String): String {
        val prompt = prompt(
            field = "action",
            instructions = "Reply with exactly one word: 'suppress' (hide silently) or " +
                "'queue' (hold for later review).",
            input = input,
        )
        val raw = llamaEngine.generate(prompt, MAX_FIELD_TOKENS).trim().lowercase()
        return if (raw.contains("queue")) "queue" else "suppress"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun prompt(
        field: String,
        instructions: String,
        input: String,
        extra: String? = null,
    ): String = buildString {
        appendLine("[SYSTEM] You are a rule-field extractor. Extract only the requested field.")
        appendLine("Reply with the value only — no explanation. If unclear, reply 'unknown'.")
        appendLine()
        appendLine("[USER]")
        appendLine("Field: $field")
        appendLine("Instructions: $instructions")
        if (extra != null) appendLine(extra)
        appendLine("Description: \"$input\"")
        appendLine("Reply:")
        appendLine()
        appendLine("[ASSISTANT]")
    }

    /** First whitespace-delimited token, stripped of surrounding punctuation. Keeps
     *  internal periods intact so package names like `com.slack` survive. */
    private fun firstToken(raw: String): String =
        raw.split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trim(',', ';', ':', '"', '\'', '.', '(', ')')
            ?: ""

    private suspend fun safeKnownApps(): List<KnownApp> = try {
        notificationRepository.getDistinctPackageNames().map { pkg ->
            KnownApp(pkg, appLabelResolver.label(pkg))
        }
    } catch (e: Exception) {
        Log.w(TAG, "safeKnownApps: falling back to empty list", e)
        emptyList()
    }

    companion object {
        private const val TAG = "RuleExtractor"
        /** Output budget per field — short values only. */
        private const val MAX_FIELD_TOKENS = 32
        /** Cap on known-packages list size injected into prompts. */
        private const val MAX_KNOWN_PACKAGES = 20
        /** Cap on contact list size injected into prompts. */
        private const val MAX_CONTACTS = 30
    }
}
