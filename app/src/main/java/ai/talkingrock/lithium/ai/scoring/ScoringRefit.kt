package ai.talkingrock.lithium.ai.scoring

import android.content.SharedPreferences
import android.util.Log
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.AppBattleJudgmentDao
import ai.talkingrock.lithium.data.db.AppRankingDao
import ai.talkingrock.lithium.data.db.ChannelRankingDao
import ai.talkingrock.lithium.data.db.ImplicitJudgmentDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.ScoreQuantilesDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import ai.talkingrock.lithium.data.model.AppRanking
import ai.talkingrock.lithium.data.model.ChannelRanking
import ai.talkingrock.lithium.data.model.ScoreQuantiles
import ai.talkingrock.lithium.ui.training.updateElo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Periodic scoring model refit step, executed as Step 6.5 of [AiAnalysisWorker].
 *
 * Does three things in sequence:
 *
 * 1. **Bradley-Terry replay** — rebuilds [AppRanking] and [ChannelRanking] from scratch
 *    by replaying all [app_battle_judgments] and channel-pair [training_judgments] in
 *    chronological order. Corrects Elo drift from online updates.
 *
 * 2. **Category weight fit** — learns six scalar weights b[k] (one per [NotificationCategory])
 *    via logistic regression on same-channel notification-pair judgments. Weights are stored in
 *    [SharedPreferences] and read by [Scorer.categoryBias] at score time.
 *
 * 3. **Quantile recompute** — scores the last 7 days of [NotificationRecord] rows and writes
 *    q20/q60/q90 to [ScoreQuantiles] (the singleton table used by [TierMapper]).
 *
 * **Debounce:** skips the entire refit if fewer than [Prefs.REFIT_MIN_NEW_JUDGMENTS] new
 * training_judgments have been recorded since the last run. Prevents thrashing when training
 * is idle.
 */
@Singleton
class ScoringRefit @Inject constructor(
    private val trainingJudgmentDao: TrainingJudgmentDao,
    private val appBattleJudgmentDao: AppBattleJudgmentDao,
    private val appRankingDao: AppRankingDao,
    private val channelRankingDao: ChannelRankingDao,
    private val implicitJudgmentDao: ImplicitJudgmentDao,
    private val notificationDao: NotificationDao,
    private val scoreQuantilesDao: ScoreQuantilesDao,
    private val scorer: Scorer,
    private val sharedPreferences: SharedPreferences,
) {

    companion object {
        private const val TAG = "ScoringRefit"
        private const val DEFAULT_ELO = 1200
        private const val ELO_K = 32

        /** Elo K-factor for implicit cross-channel judgments (weaker signal than explicit K=32). */
        private const val K_IMPLICIT_CHANNEL = 8

        /**
         * Elo K-factor for implicit cross-app judgments.
         * Half of K_IMPLICIT_CHANNEL — the app-level implication of a channel-level implicit
         * signal is weaker than the channel-level implication itself.
         */
        private const val K_IMPLICIT_APP = 4

        /** Sample weight for implicit same-channel rows in the category weight regression. */
        private const val IMPLICIT_SAMPLE_WEIGHT = 0.25

        /** Rolling window for quantile sample. */
        private const val QUANTILE_WINDOW_DAYS = 7

        /** Minimum scored notifications required before writing quantiles. */
        private const val QUANTILE_MIN_SAMPLE = 50

        /** Minimum same-channel pairs required before fitting category weights. */
        private const val WEIGHT_FIT_MIN_PAIRS = 20

        /** Logistic regression hyper-parameters for category weight fit. */
        private const val LR_ITERATIONS = 50
        private const val LR_RATE = 0.1
        private const val LR_LAMBDA = 1.0  // L2 regularization toward zero

        /**
         * Ordered list of category labels whose weights are fit and stored.
         * Must stay in sync with [Scorer.CATEGORY_ORDER] — both index into
         * the same [Prefs.CATEGORY_WEIGHTS] comma-separated string.
         * UNKNOWN is excluded — it carries no content signal.
         */
        private val CATEGORY_ORDER get() = Scorer.CATEGORY_ORDER
    }

    /**
     * Runs the full refit pipeline. Debounced by judgment delta.
     * Suspends; must be called from a coroutine context.
     *
     * Failures in each sub-step are caught and logged independently so a failure
     * in (e.g.) category weight fit does not abort the quantile recompute.
     */
    suspend fun refit() {
        // ── Debounce check ────────────────────────────────────────────────────
        val currentCount = trainingJudgmentDao.count()
        val lastCount = sharedPreferences.getInt(Prefs.REFIT_LAST_JUDGMENT_COUNT, 0)
        val delta = currentCount - lastCount

        if (delta < Prefs.REFIT_MIN_NEW_JUDGMENTS) {
            Log.d(TAG, "refit: skipping — only $delta new judgments since last run (need ${Prefs.REFIT_MIN_NEW_JUDGMENTS})")
            return
        }

        Log.i(TAG, "refit: starting — $delta new judgments since last run (total=$currentCount)")

        // ── Step 1: Bradley-Terry Elo replay ─────────────────────────────────
        try {
            replayElo()
            Log.i(TAG, "refit: Elo replay complete")
        } catch (e: Exception) {
            Log.e(TAG, "refit: Elo replay failed", e)
        }

        // ── Step 2: Category weight fit ───────────────────────────────────────
        try {
            fitCategoryWeights()
            Log.i(TAG, "refit: category weight fit complete")
        } catch (e: Exception) {
            Log.e(TAG, "refit: category weight fit failed", e)
        }

        // ── Step 3: Quantile recompute ────────────────────────────────────────
        try {
            recomputeQuantiles()
            Log.i(TAG, "refit: quantile recompute complete")
        } catch (e: Exception) {
            Log.e(TAG, "refit: quantile recompute failed", e)
        }

        // Persist the new explicit count after all steps complete (even partial success).
        // Also record the implicit count for diagnostic logging only — it does NOT gate
        // the debounce check. The debounce gate is explicit-only (see §4 of the plan).
        val implicitCount = try { implicitJudgmentDao.count() } catch (_: Exception) { -1 }
        sharedPreferences.edit()
            .putInt(Prefs.REFIT_LAST_JUDGMENT_COUNT, currentCount)
            .putInt(Prefs.REFIT_LAST_IMPLICIT_COUNT, implicitCount)
            .apply()

        Log.i(TAG, "refit: done — explicit judgment count=$currentCount, implicit count=$implicitCount")
    }

    // ── 1. Elo replay ─────────────────────────────────────────────────────────

    /**
     * Unified event type for chronological Elo replay.
     *
     * All three judgment sources (app battles, explicit channel-pair training judgments,
     * and implicit behavioral observations) are mapped to this sealed type so they can
     * be sorted into a single timeline and processed in strict creation order.
     */
    private sealed interface ReplayEvent {
        val createdAtMs: Long

        /** An explicit app-vs-app battle from [AppBattleJudgment]. */
        data class AppBattle(
            override val createdAtMs: Long,
            val leftPkg: String,
            val rightPkg: String,
            val choice: String,
        ) : ReplayEvent

        /**
         * An explicit channel-pair [TrainingJudgment] where left and right
         * (pkg, channelId) tuples differ. Same-channel notification pairs are
         * excluded here (they feed the category weight fit only, not Elo).
         */
        data class ChannelPair(
            override val createdAtMs: Long,
            val leftPkg: String,
            val leftChannelId: String,
            val rightPkg: String,
            val rightChannelId: String,
            val choice: String,
        ) : ReplayEvent

        /**
         * A behavioral observation from [ImplicitJudgment].
         *
         * [isSameChannel] true → same-(pkg, channel) pair; carries content preference
         * only and is skipped during Elo replay (fed to category weight fit instead).
         * [screenWasOn] false → off-screen dismissal; too noisy to trust.
         */
        data class Implicit(
            override val createdAtMs: Long,
            val winnerPkg: String,
            val winnerChannelId: String,
            val loserPkg: String,
            val loserChannelId: String,
            val isSameChannel: Boolean,
            val screenWasOn: Boolean,
        ) : ReplayEvent
    }

    private suspend fun replayElo() {
        // Mutable in-memory tables; keyed by package or (pkg, channelId).
        data class RankState(
            var elo: Int = DEFAULT_ELO,
            var wins: Int = 0,
            var losses: Int = 0,
            var ties: Int = 0,
            var judgments: Int = 0,
        )

        val appStates = mutableMapOf<String, RankState>()
        val channelStates = mutableMapOf<Pair<String, String>, RankState>()

        fun appState(pkg: String) = appStates.getOrPut(pkg) { RankState() }
        fun chanState(pkg: String, ch: String) = channelStates.getOrPut(pkg to ch) { RankState() }

        fun applyElo(left: RankState, right: RankState, choice: String, k: Int = ELO_K) {
            val actual = when (choice) {
                "left" -> 1.0
                "right" -> 0.0
                "tie" -> 0.5
                else -> return  // skip
            }
            val (newLeft, newRight) = updateElo(left.elo, right.elo, actual, k)
            left.elo = newLeft
            right.elo = newRight
            when (choice) {
                "left" -> { left.wins++; right.losses++ }
                "right" -> { left.losses++; right.wins++ }
                "tie" -> { left.ties++; right.ties++ }
            }
            left.judgments++
            right.judgments++
        }

        // ── Collect all three sources into a unified, sorted timeline ─────────
        // This corrects the previous sequential sub-pass ordering where implicit
        // events that occurred before a later explicit judgment were applied after it.
        val appBattles = appBattleJudgmentDao.getAll()
        val allJudgments = trainingJudgmentDao.getAllWithNotifications()
        val implicitJudgments = implicitJudgmentDao.getAllChronological()

        Log.d(TAG, "replayElo: sources — app-battle=${appBattles.size}, " +
            "training=${allJudgments.size}, implicit=${implicitJudgments.size}")

        val events = buildList<ReplayEvent> {
            // Source 1: app-vs-app battles
            for (j in appBattles) {
                if (j.choice == "skip") continue
                add(ReplayEvent.AppBattle(
                    createdAtMs = j.createdAtMs,
                    leftPkg = j.leftPackage,
                    rightPkg = j.rightPackage,
                    choice = j.choice,
                ))
            }

            // Source 2: channel-pair training judgments
            // A channel-pair judgment has non-null leftChannelId AND rightChannelId AND
            // the two (pkg, channel) tuples differ.
            var channelPairCount = 0
            for (j in allJudgments) {
                if (j.choice == "skip") continue
                val lChan = j.leftChan ?: continue
                val rChan = j.rightChan ?: continue
                if (j.leftChannelId == null || j.rightChannelId == null) continue
                val leftKey = j.leftPkg to lChan
                val rightKey = j.rightPkg to rChan
                if (leftKey == rightKey) continue  // same-channel → notification pair, not for Elo
                add(ReplayEvent.ChannelPair(
                    createdAtMs = j.createdAtMs,
                    leftPkg = j.leftPkg,
                    leftChannelId = lChan,
                    rightPkg = j.rightPkg,
                    rightChannelId = rChan,
                    choice = j.choice,
                ))
                channelPairCount++
            }
            Log.d(TAG, "replayElo: ${channelPairCount} channel-pair event(s) added from ${allJudgments.size} training judgment(s)")

            // Source 3: implicit behavioral observations
            for (j in implicitJudgments) {
                val sameChannel = j.winnerPackage == j.loserPackage &&
                    j.winnerChannelId == j.loserChannelId
                add(ReplayEvent.Implicit(
                    createdAtMs = j.createdAtMs,
                    winnerPkg = j.winnerPackage,
                    winnerChannelId = j.winnerChannelId,
                    loserPkg = j.loserPackage,
                    loserChannelId = j.loserChannelId,
                    isSameChannel = sameChannel,
                    screenWasOn = j.screenWasOn,
                ))
            }
        }.sortedBy { it.createdAtMs }

        Log.d(TAG, "replayElo: replaying ${events.size} total event(s) in chronological order")

        // ── Single-pass dispatch over the unified timeline ────────────────────
        var implicitEloCount = 0
        var implicitSkipCount = 0

        for (event in events) {
            when (event) {
                is ReplayEvent.AppBattle -> {
                    // K=32 — deliberate explicit training signal
                    applyElo(appState(event.leftPkg), appState(event.rightPkg), event.choice, ELO_K)
                }

                is ReplayEvent.ChannelPair -> {
                    // K=32 for channel; also a weaker app-level signal at K=32
                    applyElo(
                        chanState(event.leftPkg, event.leftChannelId),
                        chanState(event.rightPkg, event.rightChannelId),
                        event.choice,
                        ELO_K,
                    )
                    applyElo(appState(event.leftPkg), appState(event.rightPkg), event.choice, ELO_K)
                }

                is ReplayEvent.Implicit -> {
                    // Same-channel implicit rows carry content preference only — skip for Elo
                    if (event.isSameChannel) { implicitSkipCount++; continue }
                    // Screen-off rows are too noisy for Elo
                    if (!event.screenWasOn) { implicitSkipCount++; continue }

                    // Cross-channel: update ChannelRanking with K=8
                    val winChan = chanState(event.winnerPkg, event.winnerChannelId)
                    val losChan = chanState(event.loserPkg, event.loserChannelId)
                    val (newWinElo, newLosElo) = updateElo(winChan.elo, losChan.elo, 1.0, K_IMPLICIT_CHANNEL)
                    winChan.elo = newWinElo
                    losChan.elo = newLosElo
                    winChan.wins++; winChan.judgments++
                    losChan.losses++; losChan.judgments++

                    // Cross-app: also update AppRanking with K=4 (weaker implication)
                    if (event.winnerPkg != event.loserPkg) {
                        val winApp = appState(event.winnerPkg)
                        val losApp = appState(event.loserPkg)
                        val (newWinAppElo, newLosAppElo) = updateElo(winApp.elo, losApp.elo, 1.0, K_IMPLICIT_APP)
                        winApp.elo = newWinAppElo
                        losApp.elo = newLosAppElo
                        winApp.wins++; winApp.judgments++
                        losApp.losses++; losApp.judgments++
                    }
                    implicitEloCount++
                }
            }
        }
        Log.d(TAG, "replayElo: applied $implicitEloCount implicit Elo update(s) ($implicitSkipCount skipped)")

        // ── Write back to DB ──────────────────────────────────────────────────
        val nowMs = System.currentTimeMillis()
        for ((pkg, state) in appStates) {
            appRankingDao.upsert(
                AppRanking(
                    packageName = pkg,
                    eloScore = state.elo,
                    wins = state.wins,
                    losses = state.losses,
                    ties = state.ties,
                    judgments = state.judgments,
                    updatedAtMs = nowMs,
                )
            )
        }
        for ((key, state) in channelStates) {
            val (pkg, channelId) = key
            channelRankingDao.upsert(
                ChannelRanking(
                    packageName = pkg,
                    channelId = channelId,
                    eloScore = state.elo,
                    wins = state.wins,
                    losses = state.losses,
                    ties = state.ties,
                    judgments = state.judgments,
                    updatedAtMs = nowMs,
                )
            )
        }
        Log.d(TAG, "replayElo: wrote ${appStates.size} app ranking(s), ${channelStates.size} channel ranking(s)")
    }

    // ── 2. Category weight fit ─────────────────────────────────────────────────

    private suspend fun fitCategoryWeights() {
        val allJudgments = trainingJudgmentDao.getAllWithNotifications()

        // Feature vector length = n_categories + 1.
        // Index 0..n-1 : category confidence differences (same as before).
        // Index n       : rank_delta = winner_rank - loser_rank.
        //
        // The rank_delta feature absorbs residual position bias from the cascade-filtered
        // implicit rows. It is a nuisance/debiasing parameter only — the learned coefficient
        // b_rank is NOT applied at scorer inference time (option A from the plan §3/§4).
        // It is stored separately in Prefs.RANK_DELTA_WEIGHT for diagnostic logging only.
        val nCat = CATEGORY_ORDER.size
        val nFeatures = nCat + 1  // +1 for rank_delta

        // TrainingRow carries a weight field:
        //   - Explicit pairs: weight = 1.0
        //   - Implicit same-channel pairs: weight = IMPLICIT_SAMPLE_WEIGHT (0.25)
        data class TrainingRow(val features: DoubleArray, val label: Double, val weight: Double = 1.0)

        val rows = mutableListOf<TrainingRow>()

        // ── Explicit same-channel pairs ────────────────────────────────────────
        for (j in allJudgments) {
            if (j.choice != "left" && j.choice != "right") continue
            val lChan = j.leftChan ?: continue
            val rChan = j.rightChan ?: continue
            // Same (pkg, channel) on both sides → notification pair
            if (j.leftPkg != j.rightPkg || lChan != rChan) continue
            // Need classification data to produce features
            val lClass = j.leftAiClassification ?: continue
            val rClass = j.rightAiClassification ?: continue
            val lConf = j.leftConfidence?.toDouble() ?: continue
            val rConf = j.rightConfidence?.toDouble() ?: continue

            // Feature vector: x[k] = (leftConf if leftClass==k else 0) - (rightConf if rightClass==k else 0)
            // x[nCat] = 0.0 — explicit judgments have no rank context
            val x = DoubleArray(nFeatures)
            for ((idx, cat) in CATEGORY_ORDER.withIndex()) {
                val leftContrib = if (lClass == cat) lConf else 0.0
                val rightContrib = if (rClass == cat) rConf else 0.0
                x[idx] = leftContrib - rightContrib
            }
            x[nCat] = 0.0  // no rank context for explicit pairs
            val y = if (j.choice == "left") 1.0 else 0.0
            rows.add(TrainingRow(x, y, weight = 1.0))
        }

        // ── Implicit same-channel pairs (weight = 0.25) ────────────────────────
        // These rows carry content preference signal between notifications from the
        // same channel. Screen-off rows are excluded (noisier signal).
        // Rows with null AI classification are excluded (no category features to fit).
        val implicitSameChannel = implicitJudgmentDao.getSameChannelChronological()
        var implicitAdded = 0
        for (j in implicitSameChannel) {
            if (!j.screenWasOn) continue
            val wClass = j.winnerAiClass ?: continue
            val lClass = j.loserAiClass ?: continue
            val wConf = j.winnerAiConf?.toDouble() ?: continue
            val lConf = j.loserAiConf?.toDouble() ?: continue

            // Feature vector: x[k] = (winnerConf if winnerClass==k else 0) - (loserConf if loserClass==k else 0)
            // x[nCat] = winner_rank - loser_rank (rank_delta for position-bias absorption)
            val x = DoubleArray(nFeatures)
            for ((idx, cat) in CATEGORY_ORDER.withIndex()) {
                val winnerContrib = if (wClass == cat) wConf else 0.0
                val loserContrib = if (lClass == cat) lConf else 0.0
                x[idx] = winnerContrib - loserContrib
            }
            x[nCat] = (j.winnerRank - j.loserRank).toDouble()
            // label = 1.0: winner side always wins (by construction of the implicit judgment)
            rows.add(TrainingRow(x, label = 1.0, weight = IMPLICIT_SAMPLE_WEIGHT))
            implicitAdded++
        }

        Log.d(TAG, "fitCategoryWeights: ${rows.size} total training row(s) " +
            "(explicit=${rows.size - implicitAdded}, implicit=$implicitAdded)")

        if (rows.size < WEIGHT_FIT_MIN_PAIRS) {
            Log.d(TAG, "fitCategoryWeights: skipping — need $WEIGHT_FIT_MIN_PAIRS, have ${rows.size}")
            return
        }

        // Weighted sum of row weights (replaces rows.size in gradient normalization)
        val totalWeight = rows.sumOf { it.weight }

        // Gradient descent logistic regression — no intercept (channel bias handles that).
        // Weight vector has nFeatures elements: nCat category weights + 1 rank_delta weight.
        val w = DoubleArray(nFeatures) { 0.0 }  // start at zero

        repeat(LR_ITERATIONS) {
            val grad = DoubleArray(nFeatures)
            for (row in rows) {
                // Predicted probability: σ(w · x)
                val dot = w.indices.sumOf { k -> w[k] * row.features[k] }
                val pred = sigmoid(dot)
                val err = pred - row.label
                for (k in 0 until nFeatures) {
                    // Multiply gradient contribution by sample weight
                    grad[k] += err * row.features[k] * row.weight
                }
            }
            // Gradient step with L2 regularization toward 0 (normalize by total weight)
            for (k in 0 until nFeatures) {
                w[k] -= LR_RATE * (grad[k] / totalWeight + LR_LAMBDA * w[k] / totalWeight)
            }
        }

        // The last element w[nCat] is the rank_delta coefficient — a nuisance parameter
        // used during refit to absorb residual position bias from implicit pairs.
        // It is NOT read by Scorer.categoryBias() at inference time (option A from plan §3).
        // Store it separately in Prefs.RANK_DELTA_WEIGHT for diagnostic logging.
        val rankDeltaWeight = w[nCat]

        // Persist the first nCat elements as the category weights string.
        // Format: nCat floats (backward-compat: readers expecting 6 or 7 elements both work).
        // Scorer.categoryBias() reads only the first nCat elements; the 7th (rank_delta) is
        // persisted under RANK_DELTA_WEIGHT, not in CATEGORY_WEIGHTS, so old readers are safe.
        val categoryWeights = w.take(nCat).toDoubleArray()
        val weightsStr = categoryWeights.joinToString(",")
        sharedPreferences.edit()
            .putString(Prefs.CATEGORY_WEIGHTS, weightsStr)
            .putFloat(Prefs.RANK_DELTA_WEIGHT, rankDeltaWeight.toFloat())
            .apply()

        Log.d(TAG, "fitCategoryWeights: category weights=${CATEGORY_ORDER.zip(categoryWeights.toList()).map { (cat, v) -> "$cat=${"%.3f".format(v)}" }}")
        Log.d(TAG, "fitCategoryWeights: rank_delta_weight=${"%.4f".format(rankDeltaWeight)} (refit-only, not applied at inference)")
    }

    // ── 3. Quantile recompute ─────────────────────────────────────────────────

    private suspend fun recomputeQuantiles() {
        val windowMs = QUANTILE_WINDOW_DAYS * 24L * 60L * 60L * 1000L
        val sinceMs = System.currentTimeMillis() - windowMs

        val recentRecords = notificationDao.getAllSince(sinceMs)
        Log.d(TAG, "recomputeQuantiles: ${recentRecords.size} notification(s) in last $QUANTILE_WINDOW_DAYS day(s)")

        if (recentRecords.size < QUANTILE_MIN_SAMPLE) {
            Log.d(TAG, "recomputeQuantiles: skipping — need $QUANTILE_MIN_SAMPLE, have ${recentRecords.size}")
            return
        }

        val scores = mutableListOf<Double>()
        for (record in recentRecords) {
            try {
                val result = scorer.score(
                    packageName = record.packageName,
                    channelId = record.channelId,
                    aiClassification = record.aiClassification,
                    aiConfidence = record.aiConfidence,
                    isFromContact = record.isFromContact,
                )
                scores.add(result.score)
            } catch (e: Exception) {
                Log.w(TAG, "recomputeQuantiles: score failed for id=${record.id}", e)
            }
        }

        if (scores.size < QUANTILE_MIN_SAMPLE) {
            Log.d(TAG, "recomputeQuantiles: skipping — only ${scores.size} successfully scored")
            return
        }

        scores.sort()
        val n = scores.size
        // Percentile via nearest-rank method
        val q20 = scores[(0.20 * n).toInt().coerceIn(0, n - 1)]
        val q60 = scores[(0.60 * n).toInt().coerceIn(0, n - 1)]
        val q90 = scores[(0.90 * n).toInt().coerceIn(0, n - 1)]

        scoreQuantilesDao.upsert(
            ScoreQuantiles(
                id = 0,
                windowDays = QUANTILE_WINDOW_DAYS,
                q20 = q20,
                q60 = q60,
                q90 = q90,
                computedAtMs = System.currentTimeMillis(),
                sampleSize = n,
            )
        )

        Log.d(TAG, "recomputeQuantiles: q20=${"%.3f".format(q20)}, q60=${"%.3f".format(q60)}, q90=${"%.3f".format(q90)}, n=$n")
    }
}

// ── Pure math helpers (local to ScoringRefit) ─────────────────────────────────

private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
