package ai.talkingrock.lithium.ui.training

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.db.AppBattleJudgmentDao
import ai.talkingrock.lithium.data.db.AppRankingDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.PatternStat
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import ai.talkingrock.lithium.data.model.AppBattleJudgment
import ai.talkingrock.lithium.data.model.AppRanking
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

/**
 * One round on the Training tab. Either a notification pair comparison or
 * an app-vs-app battle. The UI switches renderers based on [challenge].
 */
sealed class Challenge {
    data class NotificationPair(
        val left: NotificationRecord,
        val right: NotificationRecord
    ) : Challenge()

    data class AppBattle(
        val leftPackage: String,
        val leftElo: Int,
        val rightPackage: String,
        val rightElo: Int
    ) : Challenge()
}

data class TrainingUiState(
    val challenge: Challenge? = null,
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
    private val appRankingDao: AppRankingDao,
    private val appBattleDao: AppBattleJudgmentDao,
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
        val challenge = state.challenge ?: return
        viewModelScope.launch {
            val xp = when (challenge) {
                is Challenge.NotificationPair -> submitNotificationPair(challenge, choice)
                is Challenge.AppBattle -> submitAppBattle(challenge, choice)
            }
            val isRealJudgment = choice != "skip"
            val willCompleteSet = isRealJudgment && (setCompletedInThisRun + 1) == SET_SIZE
            val pendingSetXp = if (willCompleteSet) setXpAccumulator + xp else 0
            val bonus = if (willCompleteSet) (pendingSetXp * SET_BONUS_MULTIPLIER).roundToInt() else 0
            if (willCompleteSet) _xpEvents.emit(XpEvent.SetComplete(bonus, pendingSetXp))
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

    private suspend fun submitNotificationPair(
        c: Challenge.NotificationPair,
        choice: String
    ): Int {
        val quest = _activeQuest.value
        val stats = patternStats.value
        val leftKey = patternKey(c.left)
        val rightKey = patternKey(c.right)
        val counts = stats.associate { it.pattern to it.judged }
        val xp = computeXpForJudgment(choice, leftKey, rightKey, counts)
        val isReal = choice != "skip"
        val newlyMapped = isReal && listOf(c.left, c.right).any {
            val was = counts[patternKey(it)] ?: 0
            was == MIN_JUDGMENTS_TO_MAP - 1
        }
        val questXpBefore = questXp.value[quest.id] ?: 0

        judgmentDao.insert(
            TrainingJudgment(
                leftNotificationId = c.left.id,
                rightNotificationId = c.right.id,
                choice = choice,
                leftTier = c.left.tier,
                rightTier = c.right.tier,
                leftTierReason = c.left.tierReason,
                rightTierReason = c.right.tierReason,
                leftAiClassification = c.left.aiClassification,
                rightAiClassification = c.right.aiClassification,
                leftConfidence = c.left.aiConfidence,
                rightConfidence = c.right.aiConfidence,
                createdAtMs = System.currentTimeMillis(),
                xpAwarded = xp,
                setComplete = false,
                setBonusXp = 0,
                questId = quest.id
            )
        )
        if (xp > 0) _xpEvents.emit(XpEvent.Judgment(xp, newlyMapped))

        if (quest.goalXp > 0 && quest != Quests.FREE_PLAY) {
            val after = questXpBefore + xp
            if (questXpBefore < quest.goalXp && after >= quest.goalXp) {
                _xpEvents.emit(XpEvent.QuestComplete(quest, after))
            }
        }
        return xp
    }

    private suspend fun submitAppBattle(c: Challenge.AppBattle, choice: String): Int {
        val now = System.currentTimeMillis()
        val actual = when (choice) {
            "left" -> 1.0
            "right" -> 0.0
            "tie" -> 0.5
            else -> -1.0  // skip — no Elo update
        }
        val (leftAfter, rightAfter) = if (actual < 0) c.leftElo to c.rightElo
        else updateElo(c.leftElo, c.rightElo, actual)

        // Persist the per-judgment audit row first.
        val xp = if (choice == "skip") 0 else APP_BATTLE_XP
        appBattleDao.insert(
            AppBattleJudgment(
                leftPackage = c.leftPackage,
                rightPackage = c.rightPackage,
                choice = choice,
                leftEloBefore = c.leftElo,
                rightEloBefore = c.rightElo,
                leftEloAfter = leftAfter,
                rightEloAfter = rightAfter,
                xpAwarded = xp,
                questId = _activeQuest.value.id,
                createdAtMs = now
            )
        )

        // Only update rankings for non-skip judgments.
        if (actual >= 0) {
            val leftPrev = appRankingDao.get(c.leftPackage)
                ?: AppRanking(packageName = c.leftPackage, updatedAtMs = now)
            val rightPrev = appRankingDao.get(c.rightPackage)
                ?: AppRanking(packageName = c.rightPackage, updatedAtMs = now)
            appRankingDao.upsert(
                leftPrev.copy(
                    eloScore = leftAfter,
                    wins = leftPrev.wins + (if (choice == "left") 1 else 0),
                    losses = leftPrev.losses + (if (choice == "right") 1 else 0),
                    ties = leftPrev.ties + (if (choice == "tie") 1 else 0),
                    judgments = leftPrev.judgments + 1,
                    updatedAtMs = now
                )
            )
            appRankingDao.upsert(
                rightPrev.copy(
                    eloScore = rightAfter,
                    wins = rightPrev.wins + (if (choice == "right") 1 else 0),
                    losses = rightPrev.losses + (if (choice == "left") 1 else 0),
                    ties = rightPrev.ties + (if (choice == "tie") 1 else 0),
                    judgments = rightPrev.judgments + 1,
                    updatedAtMs = now
                )
            )
        }
        if (xp > 0) _xpEvents.emit(XpEvent.Judgment(xp, false))
        return xp
    }

    /**
     * Picks the next challenge — either a notification pair or an app
     * battle. Mix is weighted: biased toward app battles when many apps
     * have no ranking yet, tapering to 25% once the Elo pool is seeded.
     * Free Play allows both; themed quests force notification pairs
     * (app battles bypass package filters by design).
     */
    private fun loadNextPair() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val quest = _activeQuest.value
            val tryAppBattle = quest == Quests.FREE_PLAY && shouldDoAppBattle()
            val challenge: Challenge? = if (tryAppBattle) {
                pickAppBattleChallenge() ?: pickNotificationChallenge(quest)
            } else {
                pickNotificationChallenge(quest) ?: pickAppBattleChallenge()
            }
            if (challenge == null) {
                _uiState.value = _uiState.value.copy(
                    challenge = null, isLoading = false, exhausted = true
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    challenge = challenge, isLoading = false, exhausted = false
                )
            }
        }
    }

    private suspend fun shouldDoAppBattle(): Boolean {
        val total = appRankingDao.count()
        val eligible = appRankingDao.getEligiblePackages().size
        if (eligible < 2) return false
        // Early game: lots of apps have no Elo. Bias toward app battles.
        // Late game: ranks settled. Let notification pairs dominate.
        val weight = if (total < eligible / 2) 0.5 else 0.25
        return kotlin.random.Random.nextDouble() < weight
    }

    private suspend fun pickAppBattleChallenge(): Challenge.AppBattle? {
        val eligible = appRankingDao.getEligiblePackages()
        if (eligible.size < 2) return null
        val existingMap = appRankingDao.getAll().associateBy { it.packageName }
        val nowMs = System.currentTimeMillis()
        val merged: List<AppRanking> = eligible.map { pkg ->
            existingMap[pkg] ?: AppRanking(packageName = pkg, updatedAtMs = nowMs)
        }
        val pair = pickAppBattlePair(merged) ?: return null
        val leftRank = merged.first { it.packageName == pair.first }
        val rightRank = merged.first { it.packageName == pair.second }
        return Challenge.AppBattle(
            leftPackage = leftRank.packageName, leftElo = leftRank.eloScore,
            rightPackage = rightRank.packageName, rightElo = rightRank.eloScore
        )
    }

    private suspend fun pickNotificationChallenge(quest: Quest): Challenge.NotificationPair? {
        val excluded = judgmentDao.getJudgedNotificationIds()
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
        if (questFiltered.size < 2) return null
        val counts = patternStats.first().associate { it.pattern to it.judged }
        val sorted = questFiltered.sortedBy { counts[patternKey(it)] ?: 0 }
        val first = sorted.first()
        val firstPattern = patternKey(first)
        val second = sorted.drop(1).firstOrNull { patternKey(it) != firstPattern }
            ?: sorted.drop(1).firstOrNull { it.packageName != first.packageName }
            ?: sorted[1]
        return Challenge.NotificationPair(first, second)
    }

    companion object {
        private const val CANDIDATE_POOL_SIZE = 60
        private const val PREF_ACTIVE_QUEST = "training_active_quest"
        const val BATTLE_DURATION_MS = 650L
    }
}
