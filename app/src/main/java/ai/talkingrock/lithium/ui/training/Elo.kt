package ai.talkingrock.lithium.ui.training

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Standard Elo rating update. K=32 is a reasonable default for a system
 * with a bounded number of total judgments (100s, not 1000s).
 *
 * actual: 1.0 for a left-wins, 0.0 for right-wins, 0.5 for tie.
 * Returns the new (left, right) scores rounded to the nearest integer.
 */
fun updateElo(
    leftScore: Int,
    rightScore: Int,
    actual: Double,
    k: Int = 32
): Pair<Int, Int> {
    val expectedLeft = 1.0 / (1.0 + 10.0.pow((rightScore - leftScore) / 400.0))
    val leftNew = leftScore + (k * (actual - expectedLeft))
    val rightNew = rightScore + (k * ((1.0 - actual) - (1.0 - expectedLeft)))
    return leftNew.roundToInt() to rightNew.roundToInt()
}

/**
 * Picks the next (left, right) pair of packages for an app battle. Active
 * learning: prefer apps whose Elo score is least known (fewest judgments),
 * then pair each with the closest-ranked opponent so the outcome carries
 * the most information.
 */
fun pickAppBattlePair(
    available: List<ai.talkingrock.lithium.data.model.AppRanking>,
    exclude: Set<String> = emptySet()
): Pair<String, String>? {
    val pool = available.filter { it.packageName !in exclude }
    if (pool.size < 2) return null
    val leastJudged = pool.minByOrNull { it.judgments } ?: return null
    val opponent = pool
        .filter { it.packageName != leastJudged.packageName }
        .minByOrNull { kotlin.math.abs(it.eloScore - leastJudged.eloScore) }
        ?: return null
    return leastJudged.packageName to opponent.packageName
}

/** XP awarded for a single app battle. Flat — these are cheap judgments. */
const val APP_BATTLE_XP = 3
