package ai.talkingrock.lithium.ui.training

import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlin.math.roundToInt

/**
 * Dynamic level progression for the Training tab.
 *
 * Unlike a fixed XP ladder, the thresholds scale with the size of the
 * ambiguity pool in the user's database. Master means "about 90% of the
 * ambiguity gap has been closed" — so a small-dataset user can realistically
 * reach Master by judging most candidates, while a large-dataset user has
 * more work to do.
 *
 * As new notifications arrive the pool grows, shifting thresholds up and
 * giving room to level up further.
 */
data class TrainerLevel(
    val ordinal: Int,
    val name: String,
    /** XP where this level begins. */
    val floor: Int,
    /** XP where the next level begins (exclusive). Null for the terminal level. */
    val ceiling: Int?
)

/**
 * Snapshot of the trainer's current standing for rendering.
 *
 * @param xp Cumulative XP earned from all non-skip judgments.
 * @param level The level [xp] currently falls into.
 * @param nextLevel The next level up, or null if already Master.
 * @param progressWithinLevel 0f..1f progress from level floor to ceiling.
 * @param totalAchievable Estimated max XP given current ambiguity pool. Used for the dynamic ladder.
 */
data class TrainerSnapshot(
    val xp: Int,
    val level: TrainerLevel,
    val nextLevel: TrainerLevel?,
    val progressWithinLevel: Float,
    val totalAchievable: Int
)

object TrainerLevels {

    /** Level names in ascending order. */
    private val names = listOf("Novice", "Trainee", "Trainer", "Expert", "Master")

    /** Percent of totalAchievable where each level begins. */
    private val floors = listOf(0f, 0.10f, 0.30f, 0.60f, 0.90f)

    /**
     * Builds a level ladder scaled to [totalAchievable].
     *
     * Guarantees a minimum span per level so a near-empty database still has
     * a meaningful ladder. The last level (Master) has no ceiling.
     */
    fun buildLadder(totalAchievable: Int): List<TrainerLevel> {
        val anchor = maxOf(totalAchievable, MIN_LADDER_CEILING)
        return names.mapIndexed { i, name ->
            val floor = (floors[i] * anchor).roundToInt()
            val ceiling = if (i == names.lastIndex) null
                          else (floors[i + 1] * anchor).roundToInt()
            TrainerLevel(i, name, floor, ceiling)
        }
    }

    fun snapshot(xp: Int, totalAchievable: Int): TrainerSnapshot {
        val ladder = buildLadder(totalAchievable)
        val current = ladder.lastOrNull { xp >= it.floor } ?: ladder.first()
        val next = ladder.getOrNull(current.ordinal + 1)
        val progress = if (current.ceiling == null) 1f
        else ((xp - current.floor).toFloat() / (current.ceiling - current.floor)).coerceIn(0f, 1f)
        return TrainerSnapshot(
            xp = xp,
            level = current,
            nextLevel = next,
            progressWithinLevel = progress,
            totalAchievable = totalAchievable
        )
    }

    /** Minimum ladder ceiling so a fresh install still shows a real progress bar. */
    private const val MIN_LADDER_CEILING = 100
}

/**
 * XP awarded for a single judgment. Ties count like a normal judgment; skips
 * are 0 (no training signal). XP scales with the *information gain* of the pair:
 * pairs where the model is most uncertain give the most XP.
 *
 * Ambiguity per row:
 *   - ai_classification IS NULL → 1.0 (maximum — we have no signal at all)
 *   - classified → 1 - min(|confidence - 0.5| * 2, 1)
 *
 * Pair ambiguity = max(leftAmb, rightAmb), so the user gets credit for the
 * more-uncertain side. Final XP = round(BASE_XP * pair_ambiguity), min 1
 * (so every real judgment feels like progress).
 */
fun computeXpForJudgment(
    choice: String,
    left: NotificationRecord,
    right: NotificationRecord
): Int {
    if (choice == "skip") return 0
    val leftAmb = rowAmbiguity(left)
    val rightAmb = rowAmbiguity(right)
    val pairAmb = maxOf(leftAmb, rightAmb)
    return maxOf(1, (BASE_XP * pairAmb).roundToInt())
}

/**
 * Average XP per judgment used to estimate the total achievable given an
 * ambiguity pool size. Derived empirically from the formula above; using a
 * constant avoids a separate pass over the DB each refresh.
 */
const val AVG_XP_PER_JUDGMENT = 7

/** Base XP at maximum ambiguity (confidence exactly 0.5 or unclassified). */
private const val BASE_XP = 10

private fun rowAmbiguity(row: NotificationRecord): Float {
    val conf = row.aiConfidence ?: return 1f
    return (1f - (kotlin.math.abs(conf - 0.5f) * 2f)).coerceIn(0f, 1f)
}

/** Default pairs per set. User earns bonus XP for completing a set. */
const val SET_SIZE = 5

/** Bonus XP multiplier applied to the set's accumulated XP at completion. */
const val SET_BONUS_MULTIPLIER = 0.5f
