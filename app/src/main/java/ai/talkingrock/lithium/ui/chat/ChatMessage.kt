package ai.talkingrock.lithium.ui.chat

/**
 * Tools exposed as action cards in the Chat tab.
 *
 * Each tool defines a distinct conversational flow. The BRIEFING tool is a one-shot
 * button that runs the briefing aggregator on demand. The RULE_CREATION tool is a
 * multi-turn exchange: initial NL description → per-field LLM extraction → user
 * review → optional refinement follow-ups → approval and persistence.
 */
enum class ChatTool { BRIEFING, RULE_CREATION, QA }

/**
 * Field identifiers used for tracking which draft fields the user has manually
 * edited so re-extraction does not overwrite them.
 */
object RuleDraftFields {
    const val PACKAGE = "packageName"
    const val CHANNEL = "channelId"
    const val CATEGORY = "category"
    const val NOT_FROM_CONTACT = "notFromContact"
    const val ACTION = "action"
    const val TEXT_KEYWORD = "textKeyword"
    const val TEXT_OPERATOR = "textOperator"
}

/**
 * Draft of a rule being built by the rule-creation tool.
 *
 * A field is `null` when the LLM could not extract it with confidence — the UI
 * surfaces these as empty form fields with a "couldn't determine" hint.
 */
data class RuleDraftState(
    val originalInput: String,
    val packageName: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val notFromContact: Boolean = false,
    val action: String = "suppress",
    /** Optional case-insensitive keyword substring filter against notification title+body. */
    val textKeyword: String? = null,
    /** "contains" or "not_contains". Ignored when [textKeyword] is null. */
    val textOperator: String = "contains",
    /** Field IDs the user has manually overridden. Preserved across refinement. */
    val userEditedFields: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val savedRuleId: Long? = null,
)

/**
 * Messages rendered in the chat thread. The thread is append-only except where
 * noted (RuleDraft messages are replaced when refined).
 */
sealed class ChatMessage {
    abstract val timestampMs: Long

    data class UserText(
        val text: String,
        override val timestampMs: Long,
    ) : ChatMessage()

    data class SystemMessage(
        val text: String,
        override val timestampMs: Long,
    ) : ChatMessage()

    data class BriefingResult(
        val reportText: String,
        val suggestionCount: Int,
        override val timestampMs: Long,
    ) : ChatMessage()

    data class RuleExtractionProgress(
        val fieldsComplete: Int,
        val totalFields: Int,
        override val timestampMs: Long,
    ) : ChatMessage()

    /** Draft rule form. Replaced when the user refines; removed when approved. */
    data class RuleDraft(
        val draft: RuleDraftState,
        override val timestampMs: Long,
    ) : ChatMessage()

    /**
     * Natural-language answer produced by the Q&A two-pass tool-calling loop.
     * The answer text is the Pass-2 model output after the tool result was injected.
     * If no valid tool call was made, the text contains the canonical [ChatToolDispatcher.REFUSAL_TEXT].
     */
    data class AssistantAnswer(
        val text: String,
        override val timestampMs: Long,
    ) : ChatMessage()
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputDraft: String = "",
    val isExtracting: Boolean = false,
    val isBriefingRunning: Boolean = false,
    val isQaThinking: Boolean = false,
    val activeTool: ChatTool? = null,
)
