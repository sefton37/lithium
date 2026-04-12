package ai.talkingrock.lithium.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.TrainingJudgment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

/** What the Training screen needs to render. */
data class TrainingUiState(
    val left: NotificationRecord? = null,
    val right: NotificationRecord? = null,
    val isLoading: Boolean = true,
    /** True when the candidate pool is too small to form a pair. */
    val exhausted: Boolean = false,
    /** 0..SET_SIZE-1 — position within the current set. */
    val setPosition: Int = 0
)

/** Event emitted after each judgment, consumed by the screen for animations. */
sealed class XpEvent {
    data class Judgment(val xp: Int) : XpEvent()
    data class SetComplete(val bonusXp: Int, val totalSetXp: Int) : XpEvent()
}

/**
 * ViewModel for the Training tab.
 *
 * Produces ambiguous pairs, scores each judgment, accumulates XP into the
 * current set, and awards a set-completion bonus every [SET_SIZE] pairs
 * (skips don't count toward the set — they're filtered out).
 *
 * Level progression is dynamic: the total ambiguity pool in the DB defines
 * the "max achievable XP" and the level ladder is percentage-based against
 * that estimate. As your notification history grows, so does the ceiling.
 */
@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val judgmentDao: TrainingJudgmentDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingUiState())
    val uiState: StateFlow<TrainingUiState> = _uiState.asStateFlow()

    private val _xpEvents = MutableSharedFlow<XpEvent>(replay = 0, extraBufferCapacity = 4)
    val xpEvents: SharedFlow<XpEvent> = _xpEvents.asSharedFlow()

    /** Accumulated XP within the current (not-yet-completed) set. */
    private var setXpAccumulator = 0
    /** Non-skip judgments counted toward the current set (0..SET_SIZE). */
    private var setCompletedInThisRun = 0

    /** Reactive snapshot combining total XP and the dynamic pool-sized ladder. */
    val trainer: StateFlow<TrainerSnapshot> = combine(
        judgmentDao.totalXpFlow(),
        notificationDao.countAmbiguityPoolFlow()
    ) { xp, poolSize ->
        TrainerLevels.snapshot(
            xp = xp,
            totalAchievable = poolSize * AVG_XP_PER_JUDGMENT
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrainerLevels.snapshot(xp = 0, totalAchievable = 0)
    )

    val judgmentCount: StateFlow<Int> = judgmentDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init { loadNextPair() }

    fun submit(choice: String) {
        val state = _uiState.value
        val left = state.left ?: return
        val right = state.right ?: return

        viewModelScope.launch {
            val xp = computeXpForJudgment(choice, left, right)
            val isRealJudgment = choice != "skip"

            // Figure out set bookkeeping before persisting — so set_complete
            // is known at insert time.
            val willCompleteSet = isRealJudgment && (setCompletedInThisRun + 1) == SET_SIZE
            val pendingSetXp = if (willCompleteSet) setXpAccumulator + xp else 0
            val bonus = if (willCompleteSet) (pendingSetXp * SET_BONUS_MULTIPLIER).roundToInt() else 0

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
                    createdAtMs = System.currentTimeMillis(),
                    xpAwarded = xp,
                    setComplete = willCompleteSet,
                    setBonusXp = bonus
                )
            )

            // Emit per-judgment XP event for the floating "+N XP" animation.
            if (xp > 0) _xpEvents.emit(XpEvent.Judgment(xp))

            // Advance set state.
            if (isRealJudgment) {
                setXpAccumulator += xp
                setCompletedInThisRun += 1
                if (willCompleteSet) {
                    _xpEvents.emit(XpEvent.SetComplete(bonusXp = bonus, totalSetXp = pendingSetXp))
                    setXpAccumulator = 0
                    setCompletedInThisRun = 0
                }
            }
            // Skips don't count toward the set — user couldn't judge.

            _uiState.value = _uiState.value.copy(setPosition = setCompletedInThisRun)
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
                _uiState.value = _uiState.value.copy(
                    left = null, right = null,
                    isLoading = false, exhausted = true
                )
                return@launch
            }
            val first = pool.first()
            val second = pool.drop(1).firstOrNull { it.packageName != first.packageName }
                ?: pool[1]
            _uiState.value = _uiState.value.copy(
                left = first, right = second,
                isLoading = false, exhausted = false
            )
        }
    }

    companion object {
        private const val CANDIDATE_POOL_SIZE = 40
    }
}
