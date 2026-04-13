package ai.talkingrock.lithium.ai.scoring

import ai.talkingrock.lithium.data.db.ScoreQuantilesDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a continuous importance score s(x) ∈ [0, 1] to a discrete tier {0, 1, 2, 3}.
 *
 * Thresholds are read from [ScoreQuantilesDao] (nightly-computed by AiAnalysisWorker
 * Step 6.5, Phase C). Until quantiles are available the hard-coded defaults below are used:
 *
 *   tier 3 (Interrupt)  if s ≥ q90 (default 0.90)
 *   tier 2 (Worth)      if s ≥ q60 (default 0.60)
 *   tier 1 (Noise)      if s ≥ q20 (default 0.20)
 *   tier 0 (Invisible)  else
 *
 * Defaults are intentionally conservative — a fresh install with no training data
 * produces s ≈ 0.5 for most notifications, landing them in tier 2 (Worth), which
 * matches [TierClassifier]'s default-tier behavior.
 */
@Singleton
class TierMapper @Inject constructor(
    private val scoreQuantilesDao: ScoreQuantilesDao,
) {

    /**
     * Maps [score] to a tier using stored quantile thresholds (or hardcoded defaults).
     *
     * Suspend: performs a single indexed DAO read. Callers must be in a coroutine context.
     */
    suspend fun mapToTier(score: Double): Int {
        val q = scoreQuantilesDao.get()
        val q20 = q?.q20 ?: 0.20
        val q60 = q?.q60 ?: 0.60
        val q90 = q?.q90 ?: 0.90
        return when {
            score >= q90 -> 3
            score >= q60 -> 2
            score >= q20 -> 1
            else -> 0
        }
    }
}
