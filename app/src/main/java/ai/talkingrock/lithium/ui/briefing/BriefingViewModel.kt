package ai.talkingrock.lithium.ui.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state for [BriefingScreen].
 *
 * @param report The latest unreviewed report, or null when no new report exists.
 * @param suggestions Pending suggestions attached to [report]. Empty when [report] is null.
 * @param isLoading True on first emission before the DB has responded.
 */
data class BriefingUiState(
    val report: Report? = null,
    val suggestions: List<Suggestion> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * ViewModel for [BriefingScreen].
 *
 * Exposes the latest unreviewed [Report] and its pending [Suggestion] list as a single
 * [StateFlow<BriefingUiState>]. Both streams come from [ReportRepository] and are combined
 * reactively so the UI updates automatically when the AI worker inserts new data.
 *
 * Suggestion approval/rejection is intentionally deferred to M5 — the UI shows the cards
 * but the approve/reject buttons are not wired yet.
 */
@HiltViewModel
class BriefingViewModel @Inject constructor(
    private val reportRepository: ReportRepository
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BriefingUiState> = reportRepository
        .getLatestUnreviewed()
        .flatMapLatest { report ->
            if (report == null) {
                flowOf(BriefingUiState(report = null, suggestions = emptyList(), isLoading = false))
            } else {
                reportRepository.getPendingForReport(report.id)
                    .combine(flowOf(report)) { suggestions, r ->
                        BriefingUiState(
                            report = r,
                            suggestions = suggestions,
                            isLoading = false
                        )
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BriefingUiState(isLoading = true)
        )
}
