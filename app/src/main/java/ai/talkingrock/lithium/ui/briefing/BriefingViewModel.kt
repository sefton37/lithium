package ai.talkingrock.lithium.ui.briefing

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.talkingrock.lithium.ai.AiAnalysisWorker
import ai.talkingrock.lithium.ai.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.data.repository.ReportRepository
import ai.talkingrock.lithium.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for [BriefingScreen].
 *
 * @param report The latest unreviewed report, or null when no new report exists.
 * @param suggestions Pending suggestions attached to [report]. Empty when [report] is null.
 * @param isLoading True on first emission before the DB has responded.
 * @param commentDrafts Map of suggestion ID to in-progress comment text.
 * @param expandedCommentId ID of the suggestion whose comment field is currently expanded, or null.
 */
data class BriefingUiState(
    val report: Report? = null,
    val suggestions: List<Suggestion> = emptyList(),
    val isLoading: Boolean = true,
    val commentDrafts: Map<Long, String> = emptyMap(),
    val expandedCommentId: Long? = null,
    /** True when enough data has been collected for meaningful recommendations. */
    val dataReady: Boolean = false,
    /** Tier counts for the last 24 hours, indexed by tier 0..3. Zeros when no data. */
    val tierBreakdown24h: Map<Int, Int> = emptyMap(),
    /** True when an AiAnalysisWorker run is RUNNING or ENQUEUED. */
    val analysisRunning: Boolean = false
)

/**
 * ViewModel for [BriefingScreen].
 *
 * Exposes the latest unreviewed [Report] and its pending [Suggestion] list as a single
 * [StateFlow<BriefingUiState>]. Both streams come from [ReportRepository] and are combined
 * reactively so the UI updates automatically when the AI worker inserts new data.
 *
 * Suggestion approval/rejection is handled by [approveSuggestion] and [rejectSuggestion].
 * When a suggestion is approved, a new Rule is created via [RuleRepository.createFromSuggestion].
 * When all suggestions for a report are actioned, the report is marked reviewed automatically.
 */
@HiltViewModel
class BriefingViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    private val ruleRepository: RuleRepository,
    private val notificationRepository: NotificationRepository,
    private val sharedPreferences: SharedPreferences,
    @ApplicationContext context: Context
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Emits true while analysis is actively in flight.
     *
     * Periodic work sits in ENQUEUED state perpetually between its 24h firings,
     * so we only treat it as "running" when it's actually RUNNING. The manual
     * one-shot is treated as running from ENQUEUED onward — that covers the
     * brief window between tapping "Run Now" and the worker thread starting.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val analysisRunningFlow = kotlinx.coroutines.flow.combine(
        workManager.getWorkInfosForUniqueWorkFlow(AiAnalysisWorker.WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(WorkScheduler.MANUAL_WORK_NAME)
    ) { periodic, manual ->
        periodic.any { it.state == WorkInfo.State.RUNNING } ||
        manual.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
    }

    // Ephemeral UI state for comment expansion and drafts — not persisted.
    private val _uiExtras = MutableStateFlow(UiExtras())

    /** Last 24 hours of tier counts, keyed by tier (0..3). Empty until first emission. */
    private val tierBreakdownFlow = notificationRepository
        .getTierBreakdownSince(System.currentTimeMillis() - 24L * 60L * 60L * 1000L)

    private val dataReady: Boolean
        get() = sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BriefingUiState> = reportRepository
        .getLatestUnreviewed()
        .flatMapLatest { report ->
            val reportWithSuggestions = if (report == null) {
                flowOf(Pair<Report?, List<Suggestion>>(null, emptyList()))
            } else {
                reportRepository.getPendingForReport(report.id)
                    .combine(flowOf(report)) { suggestions, r -> Pair(r as Report?, suggestions) }
            }
            reportWithSuggestions
                .combine(_uiExtras) { rs, extras -> Triple(rs.first, rs.second, extras) }
                .combine(tierBreakdownFlow) { triple, tierCounts ->
                    Pair(triple, tierCounts)
                }
                .combine(analysisRunningFlow) { (triple, tierCounts), running ->
                    BriefingUiState(
                        report = triple.first,
                        suggestions = triple.second,
                        isLoading = false,
                        commentDrafts = triple.third.commentDrafts,
                        expandedCommentId = triple.third.expandedCommentId,
                        dataReady = dataReady,
                        tierBreakdown24h = tierCounts.associate { it.tier to it.count },
                        analysisRunning = running
                    )
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BriefingUiState(isLoading = true)
        )

    /**
     * Approves a suggestion:
     * 1. Creates an approved Rule from the suggestion's condition JSON.
     * 2. Marks the suggestion as approved with the current comment draft (if any).
     * 3. If no more pending suggestions exist for the report, marks the report reviewed.
     */
    fun approveSuggestion(suggestion: Suggestion, reportId: Long) {
        viewModelScope.launch {
            val comment = _uiExtras.value.commentDrafts[suggestion.id]
            // Create the rule first so it's live as soon as the status updates.
            ruleRepository.createFromSuggestion(suggestion)
            reportRepository.updateSuggestionStatus(suggestion.id, "approved", comment)
            autoMarkReviewedIfDone(reportId)
            // Clear the comment draft for this suggestion.
            _uiExtras.update { it.copy(commentDrafts = it.commentDrafts - suggestion.id) }
        }
    }

    /**
     * Rejects a suggestion:
     * 1. Marks the suggestion as rejected with the current comment draft (if any).
     * 2. No rule is created.
     * 3. If no more pending suggestions exist for the report, marks the report reviewed.
     */
    fun rejectSuggestion(suggestion: Suggestion, reportId: Long) {
        viewModelScope.launch {
            val comment = _uiExtras.value.commentDrafts[suggestion.id]
            reportRepository.updateSuggestionStatus(suggestion.id, "rejected", comment)
            autoMarkReviewedIfDone(reportId)
            _uiExtras.update { it.copy(commentDrafts = it.commentDrafts - suggestion.id) }
        }
    }

    /** Updates the comment draft for a suggestion without persisting it yet. */
    fun updateCommentDraft(suggestionId: Long, text: String) {
        _uiExtras.update {
            it.copy(commentDrafts = it.commentDrafts + (suggestionId to text))
        }
    }

    /** Toggles the expanded comment field for a suggestion. */
    fun toggleCommentExpanded(suggestionId: Long) {
        _uiExtras.update { extras ->
            val newExpanded = if (extras.expandedCommentId == suggestionId) null else suggestionId
            extras.copy(expandedCommentId = newExpanded)
        }
    }

    private suspend fun autoMarkReviewedIfDone(reportId: Long) {
        val remaining = reportRepository.countPendingSuggestions(reportId)
        if (remaining == 0) {
            reportRepository.markReviewed(reportId)
        }
    }

    /** Ephemeral (non-persisted) UI state for comment drafts and expansion. */
    private data class UiExtras(
        val commentDrafts: Map<Long, String> = emptyMap(),
        val expandedCommentId: Long? = null
    )
}
