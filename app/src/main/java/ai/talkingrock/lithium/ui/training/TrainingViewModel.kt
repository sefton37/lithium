package ai.talkingrock.lithium.ui.training

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.PatternStat
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

data class TrainingUiState(
    val left: NotificationRecord? = null,
    val right: NotificationRecord? = null,
    val isLoading: Boolean = true,
    val exhausted: Boolean = false,
    val setPosition: Int = 0,
    val lastBattle: BattleOutcome? = null
)

enum class BattleOutcome { LEFT_WINS, RIGHT_WINS, TIE, SKIPPED }

sealed class XpEvent {
    data class Judgment(val xp: Int, val patternNewlyMapped: Boolean) : XpEvent()
    data class SetComplete(val bonusXp: Int, val totalSetXp: Int) : XpEvent()
    data class QuestComplete(val quest: Quest, val totalXp: Int) : XpEvent()
    data class LevelUp(val level: TrainerLevel) : XpEvent()
}

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val notificationDao: NotificationDao,
    private val judgmentDao: TrainingJudgmentDao,
    private val sharedPreferences: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingUiState())
    val uiState: StateFlow<TrainingUiState> = _uiState.asStateFlow()

    private val _xpEvents = MutableSharedFlow<XpEvent>(replay = 0, extraBufferCapacity = 4)
    val xpEvents: SharedFlow<XpEvent> = _xpEvents.asSharedFlow()

    private val _activeQuest = MutableStateFlow(
        Quests.byId(sharedPreferences.getString(PREF_ACTIVE_QUEST, null))
    )
    val activeQuest: StateFlow<Quest> = _activeQuest.asStateFlow()

    val questXp: StateFlow<Map<String, Int>> = judgmentDao.xpByQuestFlow()
        .map { it.associate { row -> row.questId to row.xp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Pattern stats, reactive. One row per (package, tier_reason). */
    val patternStats: StateFlow<List<PatternStat>> = notificationDao.getPatternStatsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Trainer snapshot driven by patterns mapped + total XP (for display). */
    val trainer: StateFlow<TrainerSnapshot> = combine(
        judgmentDao.totalXpFlow(),
        patternStats
    ) { xp, stats ->
        TrainerLevels.snapshot(
            xp = xp,
            mapped = stats.count { it.isMapped() },
            total = stats.size
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        TrainerLevels.snapshot(0, 0, 0)
    )

    val judgmentCount: StateFlow<Int> = judgmentDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var setXpAccumulator = 0
    private var setCompletedInThisRun = 0
    /** Last level ordinal observed — used to detect level-up transitions. */
    private var lastLevelOrdinal = 0

    init {
        loadNextPair()
        viewModelScope.launch {
            trainer.collect { snap ->
                if (snap.level.ordinal > lastLevelOrdinal) {
                    _xpEvents.emit(XpEvent.LevelUp(snap.level))
                }
                lastLevelOrdinal = snap.level.ordinal
            }
        }
    }

    fun selectQuest(questId: String) {
        val q = Quests.byId(questId)
        _activeQuest.value = q
        sharedPreferences.edit().putString(PREF_ACTIVE_QUEST, q.id).apply()
        setXpAccumulator = 0
        setCompletedInThisRun = 0
        _uiState.value = _uiState.value.copy(setPosition = 0)
        loadNextPair()
    }

    fun submit(choice: String) {
        val state = _uiState.value
        val left = state.left ?: return
        val right = state.right ?: return
        val quest = _activeQuest.value

        viewModelScope.launch {
            val stats = patternStats.value
            val leftKey = patternKey(left)
            val rightKey = patternKey(right)
            val counts = stats.associate { it.pattern to it.judged }
            val xp = computeXpForJudgment(choice, leftKey, rightKey, counts)
            val isRealJudgment = choice != "skip"

            val willCompleteSet = isRealJudgment && (setCompletedInThisRun + 1) == SET_SIZE
            val pendingSetXp = if (willCompleteSet) setXpAccumulator + xp else 0
            val bonus = if (willCompleteSet) (pendingSetXp * SET_BONUS_MULTIPLIER).roundToInt() else 0

            val questXpBefore = questXp.value[quest.id] ?: 0

            // Detect newly-mapped patterns: a pattern becomes mapped when this
            // judgment pushes its judged count to exactly MIN_JUDGMENTS_TO_MAP.
            val newlyMapped = listOf(left, right).any { row ->
                val key = patternKey(row)
                val was = counts[key] ?: 0
                was == MIN_JUDGMENTS_TO_MAP - 1 && isRealJudgment
            }

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
                    setBonusXp = bonus,
                    questId = quest.id
                )
            )

            if (xp > 0) _xpEvents.emit(XpEvent.Judgment(xp, newlyMapped))
            if (willCompleteSet) _xpEvents.emit(XpEvent.SetComplete(bonus, pendingSetXp))

            if (quest.goalXp > 0 && quest != Quests.FREE_PLAY) {
                val questXpAfter = questXpBefore + xp + bonus
                if (questXpBefore < quest.goalXp && questXpAfter >= quest.goalXp) {
                    _xpEvents.emit(XpEvent.QuestComplete(quest, questXpAfter))
                }
            }

            if (isRealJudgment) {
                setXpAccumulator += xp
                setCompletedInThisRun += 1
                if (willCompleteSet) {
                    setXpAccumulator = 0
                    setCompletedInThisRun = 0
                }
            }

            _uiState.value = _uiState.value.copy(
                setPosition = setCompletedInThisRun,
                lastBattle = when (choice) {
                    "left" -> BattleOutcome.LEFT_WINS
                    "right" -> BattleOutcome.RIGHT_WINS
                    "tie" -> BattleOutcome.TIE
                    else -> BattleOutcome.SKIPPED
                }
            )

            kotlinx.coroutines.delay(BATTLE_DURATION_MS)
            _uiState.value = _uiState.value.copy(lastBattle = null)
            loadNextPair()
        }
    }

    /**
     * Active-learning pair selection:
     *   1. Fetch a wide pool of ambiguous candidates (respecting the active quest).
     *   2. Pick the row whose pattern is least-judged.
     *   3. Pair it with another row from a DIFFERENT unmapped pattern when possible,
     *      so each judgment extends coverage rather than deepening it.
     *   4. If no cross-pattern match exists in the pool, fall back to a
     *      different-package row; finally, whatever's second-best.
     */
    private fun loadNextPair() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val excluded = judgmentDao.getJudgedNotificationIds()
            val quest = _activeQuest.value
            val raw = if (quest.onlyUnclassified) {
                notificationDao.getUnclassifiedCandidates(
                    limit = CANDIDATE_POOL_SIZE,
                    excludeIds = if (excluded.isEmpty()) listOf(-1L) else excluded
                )
            } else {
                notificationDao.getAmbiguousCandidates(
                    limit = CANDIDATE_POOL_SIZE,
                    excludeIds = if (excluded.isEmpty()) listOf(-1L) else excluded
                )
            }
            val questFiltered = if (quest.packagePrefixes.isEmpty()) raw
            else raw.filter { row -> quest.packagePrefixes.any { row.packageName.startsWith(it) } }

            if (questFiltered.size < 2) {
                _uiState.value = _uiState.value.copy(
                    left = null, right = null,
                    isLoading = false, exhausted = true
                )
                return@launch
            }

            val counts = patternStats.first().associate { it.pattern to it.judged }
            val sorted = questFiltered.sortedBy { counts[patternKey(it)] ?: 0 }
            val first = sorted.first()
            val firstPattern = patternKey(first)
            val second = sorted.drop(1).firstOrNull { patternKey(it) != firstPattern }
                ?: sorted.drop(1).firstOrNull { it.packageName != first.packageName }
                ?: sorted[1]

            _uiState.value = _uiState.value.copy(
                left = first, right = second,
                isLoading = false, exhausted = false
            )
        }
    }

    companion object {
        private const val CANDIDATE_POOL_SIZE = 60
        private const val PREF_ACTIVE_QUEST = "training_active_quest"
        const val BATTLE_DURATION_MS = 650L
    }
}
