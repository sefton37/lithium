package ai.talkingrock.lithium.ai.scoring

import android.content.SharedPreferences
import ai.talkingrock.lithium.ai.NotificationCategory
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.AppRankingDao
import ai.talkingrock.lithium.data.db.ChannelRankingDao
import ai.talkingrock.lithium.data.db.ImplicitJudgmentDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * Hierarchical importance scorer for incoming notifications.
 *
 * Combines three evidence layers in logit space:
 *   1. App-level Elo rating (from app-battle judgments) shrunk toward global mean.
 *   2. Channel-level Elo rating shrunk toward its parent app.
 *   3. Behavioral prior (tap vs. dismiss rates from AppBehaviorProfile).
 *   4. Content category bias (stub — Phase C fits actual weights).
 *   5. Contact bonus (preserves TierClassifier intent).
 *
 * Returns a score s(x) ∈ [0, 1] and a tier suggestion via [TierMapper].
 * When [ScoreResult.hasAppSignal] is false, the caller should fall back to [TierClassifier].
 */
@Singleton
class Scorer @Inject constructor(
    private val appRankingDao: AppRankingDao,
    private val channelRankingDao: ChannelRankingDao,
    private val appBehaviorProfileDao: AppBehaviorProfileDao,
    private val trainingJudgmentDao: TrainingJudgmentDao,
    private val implicitJudgmentDao: ImplicitJudgmentDao,
    private val tierMapper: TierMapper,
    private val sharedPreferences: SharedPreferences,
) {

    companion object {
        /** Pseudo-count shrinking app rating toward global mean (0.5). */
        private const val K0 = 10.0
        /** Pseudo-count shrinking channel rating toward app rating. */
        private const val K1 = 5.0
        /** Pseudo-count shrinking content bias weight β toward 0. */
        private const val K2 = 20.0
        /** Pseudo-count shrinking behavioral bias weight γ toward 0. */
        private const val K3 = 30.0

        private const val REF_ELO = 1200.0
        private const val ELO_SCALE = 400.0

        /** Maximum absolute contribution of behavioral bias to logit. */
        private const val MAX_BEHAVIOR_BIAS = 0.15

        /** Contact bonus in logit space. */
        private const val CONTACT_BONUS = 0.2

        /** Ordered category labels matching the weight vector in [Prefs.CATEGORY_WEIGHTS]. */
        val CATEGORY_ORDER = listOf(
            NotificationCategory.PERSONAL.label,
            NotificationCategory.ENGAGEMENT_BAIT.label,
            NotificationCategory.PROMOTIONAL.label,
            NotificationCategory.TRANSACTIONAL.label,
            NotificationCategory.SYSTEM.label,
            NotificationCategory.BACKGROUND.label,
            NotificationCategory.SOCIAL_SIGNAL.label,
        )
    }

    /**
     * Scores a single incoming notification.
     *
     * All DAO lookups are suspend calls; caller must be in a coroutine context.
     *
     * @param packageName  Android package name.
     * @param channelId    Notification channel ID, or null if absent.
     * @param aiClassification  Category label from NotificationClassifier, or null.
     * @param aiConfidence  Classifier confidence in [0, 1], or null.
     * @param isFromContact  Whether the sender was found in device contacts.
     */
    suspend fun score(
        packageName: String,
        channelId: String?,
        aiClassification: String?,
        aiConfidence: Float?,
        isFromContact: Boolean,
    ): ScoreResult {
        // ── 1. Fetch signals ──────────────────────────────────────────────────
        val appR = appRankingDao.get(packageName)
        val channR = if (!channelId.isNullOrBlank()) {
            channelRankingDao.get(packageName, channelId)
        } else null
        val prof = if (!channelId.isNullOrBlank()) {
            appBehaviorProfileDao.getProfile(packageName, channelId)
        } else null

        val nA = appR?.judgments ?: 0
        val nC = channR?.judgments ?: 0

        // ── 2. Convert Elo → probability, apply hierarchical shrinkage ────────
        val thetaARaw = sigmoid((( appR?.eloScore ?: 1200) - REF_ELO) / ELO_SCALE)
        val thetaCRaw = sigmoid(((channR?.eloScore ?: 1200) - REF_ELO) / ELO_SCALE)

        // θ_a shrunk toward 0.5 (global uninformative prior)
        val thetaA = (nA * thetaARaw + K0 * 0.5) / (nA + K0)
        // θ_c shrunk toward θ_a (parent app prior)
        val thetaCShrunk = (nC * thetaCRaw + K1 * thetaA) / (nC + K1)

        // ── 3. Content bias — category weights fit by ScoringRefit (Phase C) ──
        val biasContent = (aiConfidence?.toDouble() ?: 0.0) * categoryBias(aiClassification)

        // ── 4. Behavioral prior from AppBehaviorProfile — with implicit fade-out ─
        // As pairwise signals (explicit + implicit) accumulate, the AppBehaviorProfile
        // tap/dismiss bias fades out to avoid double-counting: implicit judgments flow
        // through ChannelRanking Elo (which already captures tap/dismiss preference),
        // so re-applying the same signal via AppBehaviorProfile would over-weight it.
        //
        // fade = 1 / (1 + n_combined / 50.0)
        //   n_combined = n_explicit_channel + n_implicit_channel
        // At n_combined=0  → fade=1.0 (full behavior bias, cold-start signal only)
        // At n_combined=50 → fade≈0.5 (half weight)
        // At n_combined→∞  → fade→0.0 (behavior bias disabled; Elo signal dominates)
        val nExplicit = if (!channelId.isNullOrBlank()) {
            trainingJudgmentDao.countByChannel(packageName, channelId)
        } else 0
        val nImplicit = if (!channelId.isNullOrBlank()) {
            implicitJudgmentDao.countForChannel(packageName, channelId)
        } else 0
        val nCombined = nExplicit + nImplicit
        val fade = 1.0 / (1.0 + nCombined / 50.0)

        val tapRate = prof?.lifetimeTapRate?.toDouble() ?: 0.0
        val dismissRate = prof?.lifetimeDismissRate?.toDouble() ?: 0.0
        val biasRaw = 0.3 * (tapRate - dismissRate)
        // Apply fade to the behavioral bias (gamma is session-count shrinkage; fade is signal-count)
        val biasBehavior = fade * clip(biasRaw, -MAX_BEHAVIOR_BIAS, MAX_BEHAVIOR_BIAS)

        // ── 5. Contact bonus (preserves TierClassifier intent) ────────────────
        val biasContact = if (isFromContact) CONTACT_BONUS else 0.0

        // ── 6. Evidence weights ───────────────────────────────────────────────
        val mSameChannel = if (!channelId.isNullOrBlank()) {
            trainingJudgmentDao.countSameChannelPairs(packageName, channelId)
        } else 0
        val beta = mSameChannel / (mSameChannel + K2)
        val totalSessions = prof?.totalSessions ?: 0
        val gamma = totalSessions / (totalSessions + K3)

        // ── 7. Combine in logit space ─────────────────────────────────────────
        val logitC = logit(thetaCShrunk.coerceIn(0.001, 0.999))
        val logitS = logitC + beta * biasContent + gamma * biasBehavior + biasContact
        val s = sigmoid(logitS)

        // ── 8. hasAppSignal: any training data seen for this package? ─────────
        val hasAppSignal = nA > 0 || nC > 0

        val contributions = mapOf(
            "theta_c_shrunk" to thetaCShrunk,
            "theta_a_shrunk" to thetaA,
            "bias_content" to biasContent,
            "bias_behavior" to biasBehavior,
            "bias_contact" to biasContact,
            "beta" to beta,
            "gamma" to gamma,
            "fade" to fade,
            "n_combined" to nCombined.toDouble(),
        )

        val tierSuggestion = tierMapper.mapToTier(s)

        return ScoreResult(
            score = s,
            tierSuggestion = tierSuggestion,
            contributions = contributions,
            hasAppSignal = hasAppSignal,
        )
    }

    /**
     * Category bias weight b[k] for the given AI classification label.
     *
     * Reads the fitted weights written by [ScoringRefit.fitCategoryWeights] from
     * [SharedPreferences]. The weights are stored as a comma-separated string of
     * 7 floats in [Prefs.CATEGORY_WEIGHTS], indexed by [CATEGORY_ORDER].
     *
     * Returns 0.0 when:
     * - No weights have been fit yet (fresh install or < [Prefs.REFIT_MIN_NEW_JUDGMENTS] judgments).
     * - The category is null or [NotificationCategory.UNKNOWN] (no content signal).
     * - The stored weights string is malformed (graceful degradation).
     */
    private fun categoryBias(aiClassification: String?): Double {
        if (aiClassification.isNullOrBlank()) return 0.0
        val weightsStr = sharedPreferences.getString(Prefs.CATEGORY_WEIGHTS, null)
            ?: return 0.0
        val weights = try {
            weightsStr.split(",").map { it.toDouble() }
        } catch (e: NumberFormatException) {
            return 0.0
        }
        val idx = CATEGORY_ORDER.indexOf(aiClassification)
        return if (idx >= 0 && idx < weights.size) weights[idx] else 0.0
    }
}

// ── Pure math helpers ─────────────────────────────────────────────────────────

/** Logistic sigmoid: σ(x) = 1 / (1 + e^(-x)) */
private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

/** Natural log-odds: logit(p) = ln(p / (1 - p)) */
private fun logit(p: Double): Double = ln(p / (1.0 - p))

/** Clamp [value] to [lo, hi]. */
private fun clip(value: Double, lo: Double, hi: Double): Double =
    value.coerceIn(lo, hi)

/**
 * Result of a single [Scorer.score] call.
 *
 * @property score           Continuous importance score s(x) in [0, 1].
 * @property tierSuggestion  Discrete tier 0..3 from [TierMapper].
 * @property contributions   Named intermediate values for debug attribution logging.
 * @property hasAppSignal    False when no training data exists for this package —
 *                           caller should fall back to [TierClassifier].
 */
data class ScoreResult(
    val score: Double,
    val tierSuggestion: Int,
    val contributions: Map<String, Double>,
    val hasAppSignal: Boolean,
)
