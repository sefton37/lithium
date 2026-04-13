package ai.talkingrock.lithium.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.ai.BriefingService
import ai.talkingrock.lithium.ai.LlamaEngine
import ai.talkingrock.lithium.ai.RuleExtractor
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.RuleCondition
import ai.talkingrock.lithium.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Named

/**
 * Drives the Chat tab.
 *
 * The Chat tab is a tool-launcher UI: users tap a tool card, which starts a
 * conversational flow in the thread below. This VM owns message state for two
 * tools:
 *
 *  - **Briefing** — one-shot on-demand generation via [BriefingService].
 *  - **Rule creation** — multi-turn: NL → per-field LLM extraction → review form
 *    → optional refinement → approval and persistence.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val briefingService: BriefingService,
    private val ruleExtractor: RuleExtractor,
    private val ruleRepository: RuleRepository,
    private val llamaEngine: LlamaEngine,
    @Named("modelDir") private val modelDir: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun updateInputDraft(text: String) {
        _state.update { it.copy(inputDraft = text) }
    }

    /**
     * Invoked by the Briefing tool card. Runs on-demand briefing generation and
     * appends a [ChatMessage.BriefingResult] to the thread.
     */
    fun invokeBriefing() {
        if (_state.value.isBriefingRunning) return
        _state.update { it.copy(isBriefingRunning = true) }
        viewModelScope.launch {
            try {
                val result = briefingService.generateReport()
                val text = extractReportText(result.report.summaryJson)
                appendMessage(
                    ChatMessage.BriefingResult(
                        reportText = text,
                        suggestionCount = result.suggestionCount,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "invokeBriefing: failed", e)
                appendMessage(
                    ChatMessage.SystemMessage(
                        text = "Couldn't generate briefing: ${e.message ?: "unknown error"}",
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            } finally {
                _state.update { it.copy(isBriefingRunning = false) }
            }
        }
    }

    /**
     * Invoked when the user taps the Rule Creation tool card. Switches the chat
     * input into rule-creation mode; the next [submitInput] call will start extraction.
     */
    fun startRuleCreationTool() {
        _state.update { it.copy(activeTool = ChatTool.RULE_CREATION) }
        appendMessage(
            ChatMessage.SystemMessage(
                text = "Describe the rule you want (e.g. \"mute Slack DMs when I'm not a contact\").",
                timestampMs = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Routes a submitted chat input based on [ChatUiState.activeTool]:
     *  - If no active rule draft exists → starts rule extraction from scratch.
     *  - If a draft exists → treats the input as a refinement follow-up.
     */
    fun submitInput() {
        val text = _state.value.inputDraft.trim()
        if (text.isBlank()) return
        _state.update { it.copy(inputDraft = "") }

        val activeDraft = lastRuleDraft()
        appendMessage(ChatMessage.UserText(text, System.currentTimeMillis()))
        if (activeDraft == null) {
            startRuleCreation(text)
        } else {
            refineRule(text, activeDraft)
        }
    }

    private fun startRuleCreation(userInput: String) {
        if (_state.value.isExtracting) return
        _state.update { it.copy(isExtracting = true, activeTool = ChatTool.RULE_CREATION) }
        viewModelScope.launch {
            ensureGenerativeModel()
            val progress = ProgressAppender()
            try {
                val draft = ruleExtractor.extract(userInput, progress)
                replaceOrAppendDraft(draft)
            } catch (e: Exception) {
                Log.e(TAG, "startRuleCreation: failed", e)
                appendMessage(
                    ChatMessage.SystemMessage(
                        "Couldn't extract rule fields: ${e.message ?: "unknown"}",
                        System.currentTimeMillis(),
                    )
                )
            } finally {
                _state.update { it.copy(isExtracting = false) }
            }
        }
    }

    private fun refineRule(followUp: String, existing: RuleDraftState) {
        if (_state.value.isExtracting) return
        _state.update { it.copy(isExtracting = true) }
        viewModelScope.launch {
            ensureGenerativeModel()
            val progress = ProgressAppender()
            try {
                val refined = ruleExtractor.refine(existing, followUp, progress)
                replaceOrAppendDraft(refined)
            } catch (e: Exception) {
                Log.e(TAG, "refineRule: failed", e)
                appendMessage(
                    ChatMessage.SystemMessage(
                        "Couldn't refine rule: ${e.message ?: "unknown"}",
                        System.currentTimeMillis(),
                    )
                )
            } finally {
                _state.update { it.copy(isExtracting = false) }
            }
        }
    }

    /** Called from the form whenever the user manually edits a field. */
    fun updateDraftField(fieldId: String, transform: (RuleDraftState) -> RuleDraftState) {
        val current = lastRuleDraft() ?: return
        val updated = transform(current).copy(
            userEditedFields = current.userEditedFields + fieldId,
        )
        replaceOrAppendDraft(updated)
    }

    /**
     * Persists the draft as a Rule with source="ai" and status="pending_review".
     * Appends a confirmation SystemMessage and clears the active tool.
     */
    fun approveRule(draft: RuleDraftState) {
        val conditionJson = buildConditionJson(draft) ?: run {
            appendMessage(
                ChatMessage.SystemMessage(
                    "Can't save: fill in at least one filter (app, channel, category, or contact).",
                    System.currentTimeMillis(),
                )
            )
            return
        }
        replaceOrAppendDraft(draft.copy(isSaving = true))
        viewModelScope.launch {
            try {
                val rule = Rule(
                    name = ruleName(draft),
                    conditionJson = conditionJson,
                    action = draft.action,
                    status = "pending_review",
                    createdAtMs = System.currentTimeMillis(),
                    source = "ai",
                )
                val id = ruleRepository.insertRule(rule)
                replaceOrAppendDraft(draft.copy(isSaving = false, savedRuleId = id))
                appendMessage(
                    ChatMessage.SystemMessage(
                        text = "Rule saved for review — go to the Rules tab to activate it.",
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                _state.update { it.copy(activeTool = null) }
            } catch (e: Exception) {
                Log.e(TAG, "approveRule: failed", e)
                replaceOrAppendDraft(draft.copy(isSaving = false))
                appendMessage(
                    ChatMessage.SystemMessage(
                        "Couldn't save rule: ${e.message ?: "unknown error"}",
                        System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun cancelDraft() {
        _state.update { ui ->
            ui.copy(
                messages = ui.messages.filterNot { it is ChatMessage.RuleDraft },
                activeTool = null,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun lastRuleDraft(): RuleDraftState? =
        _state.value.messages.asReversed().firstOrNull { it is ChatMessage.RuleDraft }
            ?.let { (it as ChatMessage.RuleDraft).draft }

    private fun appendMessage(msg: ChatMessage) {
        _state.update { it.copy(messages = it.messages + msg) }
    }

    /** Replaces the most recent RuleDraft message, or appends a new one if none exist. */
    private fun replaceOrAppendDraft(draft: RuleDraftState) {
        _state.update { ui ->
            val msgs = ui.messages
            val lastIdx = msgs.indexOfLast { it is ChatMessage.RuleDraft }
            val wrapped = ChatMessage.RuleDraft(draft, System.currentTimeMillis())
            val next = if (lastIdx == -1) msgs + wrapped else msgs.toMutableList().also { it[lastIdx] = wrapped }
            ui.copy(messages = next)
        }
    }

    private inner class ProgressAppender : RuleExtractor.ProgressListener {
        override fun onFieldComplete(fieldsComplete: Int, totalFields: Int) {
            _state.update { ui ->
                val msg = ChatMessage.RuleExtractionProgress(
                    fieldsComplete = fieldsComplete,
                    totalFields = totalFields,
                    timestampMs = System.currentTimeMillis(),
                )
                val msgs = ui.messages
                val lastIdx = msgs.indexOfLast { it is ChatMessage.RuleExtractionProgress }
                val next = if (lastIdx == -1) msgs + msg
                else msgs.toMutableList().also { it[lastIdx] = msg }
                ui.copy(messages = next)
            }
        }
    }

    private fun ensureGenerativeModel() {
        if (!llamaEngine.isModelLoaded()) {
            llamaEngine.loadModel(modelDir, LlamaEngine.GENERATIVE_CONTEXT_SIZE)
        }
    }

    private fun buildConditionJson(draft: RuleDraftState): String? {
        val parts = mutableListOf<RuleCondition>()
        draft.packageName?.takeIf { it.isNotBlank() }?.let {
            if (draft.channelId != null && draft.channelId.isNotBlank()) {
                parts += RuleCondition.ChannelMatch(it, draft.channelId)
            } else {
                parts += RuleCondition.PackageMatch(it)
            }
        } ?: draft.channelId?.takeIf { it.isNotBlank() }?.let {
            parts += RuleCondition.ChannelMatch(packageName = null, channelId = it)
        }
        draft.category?.takeIf { it.isNotBlank() }?.let { parts += RuleCondition.CategoryMatch(it) }
        if (draft.notFromContact) parts += RuleCondition.NotFromContact

        val condition: RuleCondition = when {
            parts.isEmpty() -> return null
            parts.size == 1 -> parts.single()
            else -> RuleCondition.CompositeAnd(parts)
        }
        return json.encodeToString(RuleCondition.serializer(), condition)
    }

    private fun ruleName(draft: RuleDraftState): String {
        val snippet = draft.originalInput.trim().take(60)
        return if (snippet.isNotEmpty()) snippet else "AI rule"
    }

    private fun extractReportText(summaryJson: String): String = try {
        json.parseToJsonElement(summaryJson).jsonObject["text"]?.jsonPrimitive?.content
            ?: summaryJson
    } catch (e: Exception) {
        Log.w(TAG, "extractReportText: falling back to raw JSON", e)
        summaryJson
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
