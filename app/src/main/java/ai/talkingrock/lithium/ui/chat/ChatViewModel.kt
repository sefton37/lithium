package ai.talkingrock.lithium.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.ai.BriefingService
import ai.talkingrock.lithium.ai.ChatToolDispatcher
import ai.talkingrock.lithium.ai.LlamaEngine
import ai.talkingrock.lithium.ai.RuleExtractor
import ai.talkingrock.lithium.ai.ToolResult
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.RuleCondition
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.repository.ReportRepository
import ai.talkingrock.lithium.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val reportRepository: ReportRepository,
    private val llamaEngine: LlamaEngine,
    private val chatToolDispatcher: ChatToolDispatcher,
    @Named("modelDir") private val modelDir: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    // Reactive wiring for SuggestionPrompt messages — replaces the former
    // BriefingScreen approval UI (deleted per spec #41). When the DB has an
    // unreviewed Report with pending suggestions, we append a SuggestionPrompt
    // per suggestion into the message thread. On each emission we rebuild the
    // SuggestionPrompt-typed subset of messages so approvals/rejections self-
    // remove as soon as the Room flow re-emits with fewer pending rows. Non-
    // suggestion messages (UserText, BriefingResult, RuleDraft, etc.) are
    // preserved untouched.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pendingSuggestionsFlow = reportRepository.getLatestUnreviewed()
        .flatMapLatest { report ->
            if (report == null) flowOf(Pair<Long, List<Suggestion>>(0L, emptyList()))
            else reportRepository.getPendingForReport(report.id)
                .map { suggestions -> Pair(report.id, suggestions) }
        }

    init {
        viewModelScope.launch {
            pendingSuggestionsFlow.collect { (reportId, suggestions) ->
                _state.update { ui ->
                    val nonPrompts = ui.messages.filterNot { it is ChatMessage.SuggestionPrompt }
                    val prompts = suggestions.map { s ->
                        ChatMessage.SuggestionPrompt(
                            suggestion = s,
                            reportId = reportId,
                            timestampMs = System.currentTimeMillis(),
                        )
                    }
                    ui.copy(messages = nonPrompts + prompts)
                }
            }
        }
    }

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
     * Invoked by the Ask / Q&A tool card. Switches the chat into Q&A mode and
     * shows the input bar so the user can type a question.
     */
    fun startQaMode() {
        _state.update { it.copy(activeTool = ChatTool.QA) }
        appendMessage(
            ChatMessage.SystemMessage(
                text = "Ask me anything about your notification history.",
                timestampMs = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Runs the two-pass Q&A tool-calling loop for [question].
     *
     * Pass 1: asks the model to select a tool → [ChatToolDispatcher.dispatch].
     * Pass 2: feeds the tool result back to the model → natural-language answer.
     * If the dispatcher returns [ToolResult.Unknown] or [ToolResult.NoToolCall],
     * the canonical [ChatToolDispatcher.REFUSAL_TEXT] is appended instead.
     */
    fun submitQaInput(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank()) return
        appendMessage(ChatMessage.UserText(trimmed, System.currentTimeMillis()))
        _state.update { it.copy(isQaThinking = true) }
        viewModelScope.launch {
            ensureGenerativeModel()
            try {
                val pass1Output = llamaEngine.generate(buildPass1Prompt(trimmed), maxTokens = 32)
                val toolResult = chatToolDispatcher.dispatch(pass1Output)
                val answerText = when (toolResult) {
                    is ToolResult.Unknown, is ToolResult.NoToolCall -> ChatToolDispatcher.REFUSAL_TEXT
                    else -> {
                        val pass2Output = llamaEngine.generate(
                            buildPass2Prompt(trimmed, toolResult),
                            maxTokens = 256,
                        )
                        pass2Output.trim().ifBlank { ChatToolDispatcher.REFUSAL_TEXT }
                    }
                }
                appendQaAnswer(answerText)
            } catch (e: Exception) {
                Log.e(TAG, "submitQaInput: failed", e)
                appendQaAnswer(ChatToolDispatcher.REFUSAL_TEXT)
            } finally {
                _state.update { it.copy(isQaThinking = false) }
            }
        }
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

    /**
     * Approves a pending suggestion:
     *  1. Creates an approved Rule from the suggestion's condition JSON via
     *     [RuleRepository.createFromSuggestion].
     *  2. Marks the suggestion "approved" in the DB (no comment draft in the
     *     Chat UI — comment UX was a BriefingScreen feature that did not
     *     migrate; pass null).
     *  3. If the Report has no more pending suggestions, marks it reviewed —
     *     which causes `getLatestUnreviewed()` to emit null and all prompts
     *     to disappear from the thread.
     * Migrated from the retired BriefingViewModel.approveSuggestion (spec #41).
     */
    fun approveSuggestion(suggestion: Suggestion, reportId: Long) {
        viewModelScope.launch {
            ruleRepository.createFromSuggestion(suggestion)
            reportRepository.updateSuggestionStatus(suggestion.id, "approved", null)
            autoMarkReviewedIfDone(reportId)
        }
    }

    /**
     * Rejects a pending suggestion. No rule is created. See [approveSuggestion]
     * for the reactive-removal semantics.
     */
    fun rejectSuggestion(suggestion: Suggestion, reportId: Long) {
        viewModelScope.launch {
            reportRepository.updateSuggestionStatus(suggestion.id, "rejected", null)
            autoMarkReviewedIfDone(reportId)
        }
    }

    private suspend fun autoMarkReviewedIfDone(reportId: Long) {
        val remaining = reportRepository.countPendingSuggestions(reportId)
        if (remaining == 0) {
            reportRepository.markReviewed(reportId)
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

    /** Appends an [ChatMessage.AssistantAnswer] with [text] to the message thread. */
    private fun appendQaAnswer(text: String) {
        appendMessage(ChatMessage.AssistantAnswer(text = text, timestampMs = System.currentTimeMillis()))
    }

    /**
     * Builds the Pass-1 prompt: system tool-listing prompt + user question.
     * The model must reply with a TOOL: / ARGS: block.
     */
    private fun buildPass1Prompt(question: String): String = buildString {
        appendLine(ChatToolDispatcher.QA_SYSTEM_PROMPT)
        appendLine()
        appendLine("[USER] $question")
        appendLine("[ASSISTANT]")
    }

    /**
     * Builds the Pass-2 prompt: system prompt + user question + tool result.
     * The model must reply with a natural-language answer only (no TOOL: line).
     *
     * TODO(v2): include recent conversation turns for multi-turn support.
     */
    private fun buildPass2Prompt(question: String, result: ToolResult): String = buildString {
        appendLine(ChatToolDispatcher.QA_SYSTEM_PROMPT)
        appendLine()
        appendLine("[USER] $question")
        appendLine("[RESULT]")
        appendLine(result.toPromptString())
        appendLine("[ASSISTANT] Answer in natural language:")
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
