package ai.talkingrock.lithium.ui.training

import ai.talkingrock.lithium.data.db.PatternStat
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Level progression anchored to **pattern coverage**, not raw XP. A pattern
 * is a unique (package, tier_reason) combination — one "kind of notification"
 * the user receives (LinkedIn nudges, Amazon promos, Gmail unknown sender,
 * etc). A pattern becomes "mapped" once [MIN_JUDGMENTS_TO_MAP] of its rows
 * have been judged — enough examples to know how the user categorises it.
 *
 * Levels reflect how many patterns the user has trained Lithium on:
 *
 *   - Novice  (0):  still learning what you have
 *   - Trainee (3):  enough for per-app tier hints
 *   - Trainer (10): enough for rule-suggestion ranking
 *   - Expert  (25): enough for personal tier thresholds
 *   - Master  (50*): RLHF surface — queue/suppress tracks you
 *
 * *Master is dynamically capped at 80% of total patterns so tiny datasets
 * don't make it impossible. XP still accumulates for per-judgment feedback
 * but does not gate level progression.
 */
data class TrainerLevel(
    val ordinal: Int,
    val name: String,
    val floorPatterns: Int,
    val ceilingPatterns: Int?,
    val unlock: String
)

data class TrainerSnapshot(
    val xp: Int,
    val patternsMapped: Int,
    val patternsTotal: Int,
    val level: TrainerLevel,
    val nextLevel: TrainerLevel?,
    val progressWithinLevel: Float
)

const val MIN_JUDGMENTS_TO_MAP = 3

object TrainerLevels {

    private val base = listOf(
        TrainerLevel(0, "Novice",  0,  3,    "Still learning what you have"),
        TrainerLevel(1, "Trainee", 3,  10,   "Per-app tier hints unlocked"),
        TrainerLevel(2, "Trainer", 10, 25,   "Rule suggestions ranked by you"),
        TrainerLevel(3, "Expert",  25, 50,   "Personal tier thresholds"),
        TrainerLevel(4, "Master",  50, null, "RLHF — queue/suppress tracks you")
    )

    /** Builds the ladder with Master capped at min(50, 80% of patternsTotal). */
    fun ladder(patternsTotal: Int): List<TrainerLevel> {
        val masterFloor = min(50, (patternsTotal * 0.8).roundToInt().coerceAtLeast(5))
        val expertFloor = min(25, (patternsTotal * 0.4).roundToInt().coerceAtLeast(3))
        return listOf(
            base[0],
            base[1],
            base[2],
            base[3].copy(floorPatterns = expertFloor, ceilingPatterns = masterFloor),
            base[4].copy(floorPatterns = masterFloor)
        ).let { list ->
            // Ensure the 2→3 ceiling matches 3's floor, and 1→2 ceiling matches 2's floor.
            list.mapIndexed { i, lvl ->
                if (i < list.lastIndex) lvl.copy(ceilingPatterns = list[i + 1].floorPatterns)
                else lvl
            }
        }
    }

    fun snapshot(xp: Int, mapped: Int, total: Int): TrainerSnapshot {
        val lad = ladder(total)
        val level = lad.lastOrNull { mapped >= it.floorPatterns } ?: lad.first()
        val next = lad.getOrNull(level.ordinal + 1)
        val progress = if (level.ceilingPatterns == null) 1f
        else ((mapped - level.floorPatterns).toFloat() /
              (level.ceilingPatterns - level.floorPatterns).coerceAtLeast(1))
            .coerceIn(0f, 1f)
        return TrainerSnapshot(
            xp = xp,
            patternsMapped = mapped,
            patternsTotal = total,
            level = level,
            nextLevel = next,
            progressWithinLevel = progress
        )
    }
}

/** Returns true for patterns with enough judgments to count as "mapped." */
fun PatternStat.isMapped(): Boolean = judged >= MIN_JUDGMENTS_TO_MAP

/**
 * XP for one judgment. Skips return 0. The formula rewards judging rows
 * from patterns that are still new to Lithium (novelty = information gain):
 *
 *   - Pattern has 0 prior judgments: 10 XP
 *   - 1 prior judgment:              7 XP
 *   - 2 prior judgments:             4 XP
 *   - 3+ prior judgments:            1 XP (just a checkmark)
 *
 * The XP is based on the MORE-novel side of the pair so the user gets
 * credit even when one side is well-covered.
 */
fun computeXpForJudgment(
    choice: String,
    leftPattern: String,
    rightPattern: String,
    patternJudgeCounts: Map<String, Int>
): Int {
    if (choice == "skip") return 0
    val leftPrior = patternJudgeCounts[leftPattern] ?: 0
    val rightPrior = patternJudgeCounts[rightPattern] ?: 0
    val bestPrior = minOf(leftPrior, rightPrior)
    return when (bestPrior) {
        0 -> 10
        1 -> 7
        2 -> 4
        else -> 1
    }
}

/** Builds the pattern-key for a single row. Kept in sync with DAO SQL. */
fun patternKey(record: NotificationRecord): String =
    "${record.packageName}|${record.tierReason ?: "none"}"

/** Pairs per set. Set completion awards bonus XP. */
const val SET_SIZE = 5

/** Bonus multiplier applied to accumulated set XP on completion. */
const val SET_BONUS_MULTIPLIER = 0.5f
