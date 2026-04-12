package ai.talkingrock.lithium.ui.training

import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlin.math.roundToInt

/**
 * Fixed level ladder with meaningful inflection points. XP totals are
 * hand-chosen so that reaching each rank corresponds to a real change in
 * how well Lithium can personalise classification:
 *
 *   - Novice  (0):     still collecting signal
 *   - Trainee (50):    enough for per-package tier hints
 *   - Trainer (150):   enough for rule-suggestion ranking
 *   - Expert  (350):   enough for personalised tier thresholds
 *   - Master  (700):   RLHF surface — queue / suppress decisions track you
 *
 * Every real (non-skip) judgment yields 1–10 XP proportional to pair
 * ambiguity, so Master is ~140 judgments — a meaningful climb but not a
 * three-month grind.
 */
data class TrainerLevel(
    val ordinal: Int,
    val name: String,
    val floor: Int,
    /** XP where the next level starts (exclusive). Null for terminal. */
    val ceiling: Int?,
    /** One-line capability unlock shown in the UI. */
    val unlock: String
)

data class TrainerSnapshot(
    val xp: Int,
    val level: TrainerLevel,
    val nextLevel: TrainerLevel?,
    /** Progress within the current level, 0f..1f. 1f at Master. */
    val progressWithinLevel: Float
)

object TrainerLevels {
    val all = listOf(
        TrainerLevel(0, "Novice",  0,   50,   "Collecting signal"),
        TrainerLevel(1, "Trainee", 50,  150,  "Per-app tier hints unlocked"),
        TrainerLevel(2, "Trainer", 150, 350,  "Rule suggestions ranked by you"),
        TrainerLevel(3, "Expert",  350, 700,  "Personal tier thresholds"),
        TrainerLevel(4, "Master",  700, null, "RLHF — queue/suppress tracks you")
    )

    fun snapshot(xp: Int): TrainerSnapshot {
        val level = all.lastOrNull { xp >= it.floor } ?: all.first()
        val next = all.getOrNull(level.ordinal + 1)
        val progress = if (level.ceiling == null) 1f
        else ((xp - level.floor).toFloat() / (level.ceiling - level.floor)).coerceIn(0f, 1f)
        return TrainerSnapshot(xp, level, next, progress)
    }
}

/**
 * XP for one judgment. Ties count like a normal judgment; skips return 0.
 * Ambiguity per row: unclassified = 1.0 (max), classified = 1 - |conf-0.5|*2.
 * Pair ambiguity = max of the two. XP = round(BASE_XP * pair_ambiguity), min 1.
 */
fun computeXpForJudgment(
    choice: String,
    left: NotificationRecord,
    right: NotificationRecord
): Int {
    if (choice == "skip") return 0
    val leftAmb = rowAmbiguity(left)
    val rightAmb = rowAmbiguity(right)
    return maxOf(1, (BASE_XP * maxOf(leftAmb, rightAmb)).roundToInt())
}

private fun rowAmbiguity(row: NotificationRecord): Float {
    val conf = row.aiConfidence ?: return 1f
    return (1f - (kotlin.math.abs(conf - 0.5f) * 2f)).coerceIn(0f, 1f)
}

private const val BASE_XP = 10

/** Pairs per set. Set completion awards bonus XP. */
const val SET_SIZE = 5

/** Bonus multiplier applied to accumulated set XP on completion. */
const val SET_BONUS_MULTIPLIER = 0.5f
