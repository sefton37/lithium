package ai.talkingrock.lithium.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.TrainingJudgment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Training screen needs to render. */
data class TrainingUiState(
    val left: NotificationRecord? = null,
    val right: NotificationRecord? = null,
    val isLoading: Boolean = true,
    /** True when the candidate pool is too small to form a pair. */
    val exhausted: Boolean = false
)

/**
 * ViewModel for the Training tab.
 *
 * Loads a pair of notifications that are "ambiguous" (unclassified OR low
 * confidence), excluding rows that have already appeared in any judgment.
 * After the user picks left/right/tie/skip, a fresh pair is loaded.
 *
 * Pair selection policy (v1):
 *   1. Fetch top [CANDIDATE_POOL_SIZE] candidates ordered by ambiguity.
 *   2. First card: the most ambiguous remaining row.
 *   3. Second card: the most ambiguous candidate whose package differs from
 *      the first's. Falls back to any different row if no cross-package match
 *      exists in the pool.
 *
 * Ambiguity ranking is intentionally tier-agnostic — per design, cross-tier
 * pairs are allowed when they help disambiguate uncertain cases.
 */
@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val judgmentDao: TrainingJudgmentDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingUiState())
    val uiState: StateFlow<TrainingUiState> = _uiState.asStateFlow()

    /** Reactive count of completed judgments for the header. */
    val judgmentCount: StateFlow<Int> = judgmentDao.countFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    init {
        loadNextPair()
    }

    /**
     * Records the user's judgment of the current pair with [choice] in
     * {"left","right","tie","skip"}, then loads the next pair.
     */
    fun submit(choice: String) {
        val state = _uiState.value
        val left = state.left ?: return
        val right = state.right ?: return
        viewModelScope.launch {
            judgmentDao.insert(
                TrainingJudgment(
                    leftNotificationId = left.id,
                    rightNotificationId = right.id,
                    choice = choice,
                    leftTier = left.tier,
                    rightTier = right.tier,
                    leftTierReason = left.tierReason,
                    rightTierReason = right.tierReason,
                    leftAiClassification = left.aiClassification,
                    rightAiClassification = right.aiClassification,
                    leftConfidence = left.aiConfidence,
                    rightConfidence = right.aiConfidence,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            loadNextPair()
        }
    }

    private fun loadNextPair() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val excluded = judgmentDao.getJudgedNotificationIds()
            val pool = notificationDao.getAmbiguousCandidates(
                limit = CANDIDATE_POOL_SIZE,
                excludeIds = if (excluded.isEmpty()) listOf(-1L) else excluded
            )
            if (pool.size < 2) {
                _uiState.value = TrainingUiState(isLoading = false, exhausted = true)
                return@launch
            }
            val first = pool.first()
            val second = pool.drop(1).firstOrNull { it.packageName != first.packageName }
                ?: pool[1]
            _uiState.value = TrainingUiState(left = first, right = second, isLoading = false)
        }
    }

    companion object {
        /** How many ambiguous candidates to fetch per pair-selection round. */
        private const val CANDIDATE_POOL_SIZE = 40
    }
}
