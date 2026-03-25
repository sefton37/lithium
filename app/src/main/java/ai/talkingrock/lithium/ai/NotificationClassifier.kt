package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Classifies a [NotificationRecord] into one of the six [NotificationCategory] labels.
 *
 * Architecture: three-tier cascade.
 *
 * Tier 1 — ONNX model inference via [AiEngine.classify]. Fine-tuned DistilBERT/MobileBERT
 * for 7-class notification classification. ~10ms per notification. Used when a `.onnx` model
 * file has been sideloaded to the device.
 *
 * Tier 2 — llama.cpp generative inference via [LlamaEngine.classify]. Few-shot prompt-based
 * classification using a small GGUF model (SmolLM-135M or Qwen2-0.5B). ~300ms per notification.
 * Used when a `.gguf` model has been sideloaded but no ONNX model is available.
 *
 * Tier 3 — Deterministic heuristic classifier. Rule-based logic covering the most common
 * patterns. Always available as the final fallback. High precision for easy cases (contacts,
 * system apps, 2FA), lower confidence for ambiguous social content.
 *
 * Tier selection happens once per worker batch, not per-notification. Consistency within a
 * report cycle matters more than per-record optimality.
 *
 * The input prompt format (consistent with PLAN.md §M3.2):
 *   [APP: {packageName}] [CHANNEL: {channelId}] [TITLE: {title}] [TEXT: {text}]
 */
@Singleton
class NotificationClassifier @Inject constructor(
    private val aiEngine: AiEngine,
    private val llamaEngine: LlamaEngine
) {

    /**
     * Classifies [record] and returns a [ClassificationResult].
     *
     * Never throws. Returns [NotificationCategory.UNKNOWN] with 0.0 confidence on failure.
     */
    suspend fun classify(record: NotificationRecord): ClassificationResult =
        classify(record, profile = null)

    /**
     * Profile-aware classification. The [profile] provides lifetime behavioral data
     * that can adjust the base classification's confidence or override its category.
     */
    suspend fun classify(record: NotificationRecord, profile: AppBehaviorProfile?): ClassificationResult {
        val prompt = buildPrompt(record)

        // Tier 1: ONNX model — fast (~10ms), highest quality when available
        val onnxResult = aiEngine.classify(prompt)
        if (onnxResult != null) {
            Log.d(TAG, "classify: ONNX result ${onnxResult.label} (${onnxResult.confidence}) for pkg=${record.packageName}")
            return applyBehavioralAdjustment(onnxResult, profile)
        }

        // Tier 2: llama.cpp — slower (~300ms), few-shot generative classification
        val llamaResult = llamaEngine.classify(
            packageName = record.packageName,
            channelId = record.channelId,
            title = record.title,
            text = record.text
        )
        if (llamaResult != null) {
            Log.d(TAG, "classify: llama.cpp result ${llamaResult.label} (${llamaResult.confidence}) for pkg=${record.packageName}")
            return applyBehavioralAdjustment(llamaResult, profile)
        }

        // Tier 3: heuristic fallback — always available
        val heuristicResult = classifyHeuristic(record)
        Log.d(TAG, "classify: heuristic result ${heuristicResult.label} (${heuristicResult.confidence}) for pkg=${record.packageName}")
        return applyBehavioralAdjustment(heuristicResult, profile)
    }

    // -----------------------------------------------------------------------------------------
    // Behavioral adjustment
    // -----------------------------------------------------------------------------------------

    /**
     * Adjusts a base classification result using lifetime behavioral data.
     *
     * Rules (in priority order):
     * A. User override — absolute priority.
     * B. Category lock — strong behavioral evidence overrides the base classification.
     * C. Tap-rate boost — high engagement on personal/social content boosts confidence.
     * D. Dismiss-rate penalty — consistently dismissed "personal" content gets penalised.
     */
    private fun applyBehavioralAdjustment(
        baseResult: ClassificationResult,
        profile: AppBehaviorProfile?
    ): ClassificationResult {
        if (profile == null) return baseResult
        if (profile.totalVotes < MINIMUM_VOTE_THRESHOLD) return baseResult

        // Rule A: User override — absolute priority
        profile.userReclassified?.let { userLabel ->
            val userCat = NotificationCategory.fromLabel(userLabel)
            if (userCat != NotificationCategory.UNKNOWN) {
                return ClassificationResult(label = userCat.label, confidence = 0.99f)
            }
        }

        // Rule B: Category lock — strong behavioral evidence
        val dominantCat = NotificationCategory.fromLabel(profile.dominantCategory)
        if (dominantCat != NotificationCategory.UNKNOWN) {
            val topVotes = maxVoteCount(profile)
            val voteShare = topVotes.toFloat() / profile.totalVotes

            if (voteShare > CATEGORY_LOCK_PERCENT && profile.totalVotes >= CATEGORY_LOCK_THRESHOLD) {
                if (baseResult.label != dominantCat.label) {
                    // Strong evidence: override the base classification
                    if (profile.totalVotes >= STRONG_EVIDENCE_THRESHOLD) {
                        Log.d(TAG, "behavioral: overriding ${baseResult.label} → ${dominantCat.label} " +
                                "(${profile.totalVotes} votes, ${(voteShare * 100).toInt()}% share)")
                        return ClassificationResult(label = dominantCat.label, confidence = 0.90f)
                    }
                    // Moderate evidence: lower confidence on the base classification
                    return baseResult.copy(confidence = maxOf(0.0f, baseResult.confidence - 0.15f))
                }
                // Agreement: boost confidence
                return baseResult.copy(confidence = min(baseResult.confidence + 0.10f, 0.99f))
            }
        }

        // Rule C: Tap-rate boost for consistently-tapped personal/social content
        if (profile.lifetimeTapRate > TAP_BOOST_THRESHOLD &&
            baseResult.label in setOf(NotificationCategory.PERSONAL.label, NotificationCategory.SOCIAL_SIGNAL.label)) {
            return baseResult.copy(confidence = min(baseResult.confidence + 0.10f, 0.99f))
        }

        // Rule D: Dismiss-rate penalty for consistently-dismissed "personal" content
        if (profile.lifetimeDismissRate > DISMISS_PENALTY_THRESHOLD &&
            baseResult.label == NotificationCategory.PERSONAL.label) {
            return baseResult.copy(confidence = maxOf(0.0f, baseResult.confidence - 0.20f))
        }

        return baseResult
    }

    /** Returns the highest vote count across all category vote columns. */
    private fun maxVoteCount(profile: AppBehaviorProfile): Int = maxOf(
        profile.categoryVotePersonal,
        profile.categoryVoteEngagementBait,
        profile.categoryVotePromotional,
        profile.categoryVoteTransactional,
        profile.categoryVoteSystem,
        profile.categoryVoteSocialSignal
    )

    /**
     * Builds the input string for the ONNX model from notification metadata.
     *
     * Format: [APP: {pkg}] [CHANNEL: {channel}] [TITLE: {title}] [TEXT: {text}]
     */
    private fun buildPrompt(record: NotificationRecord): String = buildString {
        append("[APP: ${record.packageName}]")
        record.channelId?.let { append(" [CHANNEL: $it]") }
        record.title?.let { append(" [TITLE: $it]") }
        record.text?.let { append(" [TEXT: $it]") }
    }

    // -----------------------------------------------------------------------------------------
    // Tier 2: Heuristic Classifier
    // -----------------------------------------------------------------------------------------

    private fun classifyHeuristic(record: NotificationRecord): ClassificationResult {
        val pkg = record.packageName.lowercase()
        val title = record.title?.lowercase() ?: ""
        val text = record.text?.lowercase() ?: ""
        val channel = record.channelId?.lowercase() ?: ""
        val combined = "$title $text"

        // Rule 0: Ongoing/persistent notifications are background — not user-facing alerts.
        // This is the highest-priority rule: media controls, navigation, weather widgets, etc.
        // are definitionally non-interruptive regardless of sender.
        if (record.isOngoing || isBackgroundPackage(pkg)) {
            return result(NotificationCategory.BACKGROUND, 0.95f)
        }

        // Rule 1: Known contact → personal (highest priority)
        if (record.isFromContact) {
            return result(NotificationCategory.PERSONAL, 0.95f)
        }

        // Rule 2: System / OS packages → system
        if (isSystemPackage(pkg)) {
            return result(NotificationCategory.SYSTEM, 0.90f)
        }

        // Rule 3: Transactional patterns — 2FA, OTP, delivery, payment
        if (isTransactional(combined)) {
            return result(NotificationCategory.TRANSACTIONAL, 0.88f)
        }

        // Rule 4: Known social media packages with algorithmic channel signals → engagement_bait
        if (isSocialPackage(pkg) && isAlgorithmicChannel(channel, combined)) {
            return result(NotificationCategory.ENGAGEMENT_BAIT, 0.80f)
        }

        // Rule 5: Known social interaction signals → social_signal
        if (isSocialSignal(combined)) {
            return result(NotificationCategory.SOCIAL_SIGNAL, 0.75f)
        }

        // Rule 6: Known social media packages (no algorithmic signal) → social_signal
        if (isSocialPackage(pkg)) {
            return result(NotificationCategory.SOCIAL_SIGNAL, 0.65f)
        }

        // Rule 7: Promotional / marketing patterns
        if (isPromotional(combined)) {
            return result(NotificationCategory.PROMOTIONAL, 0.78f)
        }

        // Rule 8: Known e-commerce / retail packages → promotional
        if (isEcommercePackage(pkg)) {
            return result(NotificationCategory.PROMOTIONAL, 0.70f)
        }

        // Rule 9: Email / messaging apps with a human-sounding title → personal (lower confidence)
        if (isMessagingPackage(pkg) && !isAlgorithmicChannel(channel, combined)) {
            return result(NotificationCategory.PERSONAL, 0.60f)
        }

        return result(NotificationCategory.UNKNOWN, 0.0f)
    }

    // -----------------------------------------------------------------------------------------
    // Pattern helpers
    // -----------------------------------------------------------------------------------------

    private fun isBackgroundPackage(pkg: String): Boolean =
        KNOWN_BACKGROUND_PACKAGES.any { pkg == it }

    private fun isSystemPackage(pkg: String): Boolean =
        pkg.startsWith("android") ||
        pkg.startsWith("com.android.") ||
        pkg.startsWith("com.samsung.android.") ||
        pkg.startsWith("com.oneplus.") ||
        pkg.startsWith("com.motorola.") ||
        pkg.startsWith("com.sony.") ||
        KNOWN_SYSTEM_PACKAGES.any { pkg == it }

    private fun isTransactional(combined: String): Boolean =
        OTP_PATTERNS.any { combined.contains(it) } ||
        DELIVERY_PATTERNS.any { combined.contains(it) } ||
        PAYMENT_PATTERNS.any { combined.contains(it) }

    private fun isSocialPackage(pkg: String): Boolean =
        KNOWN_SOCIAL_PACKAGES.any { pkg.contains(it) }

    private fun isEcommercePackage(pkg: String): Boolean =
        KNOWN_ECOMMERCE_PACKAGES.any { pkg.contains(it) }

    private fun isMessagingPackage(pkg: String): Boolean =
        KNOWN_MESSAGING_PACKAGES.any { pkg.contains(it) }

    private fun isAlgorithmicChannel(channel: String, combined: String): Boolean =
        ALGORITHMIC_CHANNEL_KEYWORDS.any { channel.contains(it) } ||
        ALGORITHMIC_TEXT_KEYWORDS.any { combined.contains(it) }

    private fun isSocialSignal(combined: String): Boolean =
        SOCIAL_SIGNAL_PATTERNS.any { combined.contains(it) }

    private fun isPromotional(combined: String): Boolean =
        PROMOTIONAL_PATTERNS.any { combined.contains(it) }

    private fun result(category: NotificationCategory, confidence: Float) =
        ClassificationResult(label = category.label, confidence = confidence)

    // -----------------------------------------------------------------------------------------
    // Pattern dictionaries
    // -----------------------------------------------------------------------------------------

    companion object {
        private const val TAG = "NotificationClassifier"

        // ── Behavioral adjustment thresholds ─────────────────────────────────

        /** Minimum total classification votes before behavioral adjustment applies. */
        private const val MINIMUM_VOTE_THRESHOLD = 10

        /** Vote share threshold for category lock (60%). */
        private const val CATEGORY_LOCK_PERCENT = 0.60f

        /** Minimum votes for category lock to be considered. */
        private const val CATEGORY_LOCK_THRESHOLD = 20

        /** Minimum votes for a strong-evidence override of the base classification. */
        private const val STRONG_EVIDENCE_THRESHOLD = 50

        /** Lifetime tap rate above which personal/social confidence gets boosted. */
        private const val TAP_BOOST_THRESHOLD = 0.30f

        /** Lifetime dismiss rate above which "personal" classification gets penalised. */
        private const val DISMISS_PENALTY_THRESHOLD = 0.70f

        /**
         * Packages that always produce background/ongoing notifications even when
         * [NotificationRecord.isOngoing] is not set. Media players, navigation, and
         * companion apps update their persistent notifications at very high frequency
         * but never require user attention.
         */
        private val KNOWN_BACKGROUND_PACKAGES = setOf(
            "com.spotify.music",                                    // media playback controls
            "com.google.android.apps.maps",                         // navigation
            "com.google.android.projection.gearhead",               // Android Auto
            "com.google.android.apps.wearables.maestro.companion"   // Pixel Watch companion
        )

        private val KNOWN_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.phone",
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.deskclock",
            "com.google.android.dialer",
            "com.google.android.calendar",
            "com.google.android.apps.wellbeing",
            "com.google.android.apps.nexuslauncher",
            "com.android.providers.downloads",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.server.telecom"
        )

        private val KNOWN_SOCIAL_PACKAGES = listOf(
            "instagram", "facebook", "twitter", "tiktok",
            "snapchat", "reddit", "linkedin", "pinterest",
            "tumblr", "mastodon", "threads", "bereal",
            "youtube"  // algorithmic feed only — YouTube messages handled by messaging check
        )

        private val KNOWN_ECOMMERCE_PACKAGES = listOf(
            "amazon", "ebay", "etsy", "shopify", "walmart",
            "com.target", "bestbuy", "wayfair", "wish",
            "aliexpress", "shein", "temu", "zzkko",
            "ubercab.eats", "doordash", "grubhub", "instacart"
        )

        private val KNOWN_MESSAGING_PACKAGES = listOf(
            "com.whatsapp", "org.telegram", "com.discord",
            "com.slack", "com.microsoft.teams", "com.skype",
            "com.viber", "com.line", "com.kakao",
            "com.google.android.talk",  // Hangouts / Chat
            "com.google.android.gm",    // Gmail
            "com.microsoft.office.outlook",
            "org.thoughtcrime.securesms"  // Signal
        )

        private val OTP_PATTERNS = listOf(
            "verification code", "your code is", "otp:", " otp ", "one-time",
            "security code", "login code", "auth code", "passcode", "2fa",
            "two-factor", "two factor", "authentication code"
        )

        private val DELIVERY_PATTERNS = listOf(
            "your order", "has been delivered", "out for delivery",
            "delivery scheduled", "package delivered", "tracking number",
            "shipment", "arriving today", "arriving tomorrow",
            "estimated delivery", "order shipped", "order confirmed",
            "order #", "order number"
        )

        private val PAYMENT_PATTERNS = listOf(
            "payment received", "payment of", "receipt for",
            "transaction of", "charged", "refund", "invoice",
            "subscription renewed", "auto-renew", "billing",
            "amount due", "statement ready"
        )

        private val ALGORITHMIC_CHANNEL_KEYWORDS = listOf(
            "recommendation", "suggested", "trending", "discover",
            "for you", "explore", "highlights", "digest",
            "weekly", "monthly", "roundup", "top posts"
        )

        private val ALGORITHMIC_TEXT_KEYWORDS = listOf(
            "see what's trending", "you might like", "based on your",
            "recommended for you", "people you may know",
            "popular near you", "trending in", "suggested for you",
            "check out", "don't miss", "see what's new",
            "posts you've missed"
        )

        private val SOCIAL_SIGNAL_PATTERNS = listOf(
            "liked your", "reacted to", "commented on",
            "replied to your", "mentioned you", "tagged you",
            "followed you", "new follower", "started following",
            "shared your", "retweeted", "boosted your",
            "upvoted your", "awarded your", "your post",
            "your photo", "your video", "your story"
        )

        private val PROMOTIONAL_PATTERNS = listOf(
            "% off", "save ", "flash sale", "limited time",
            "exclusive deal", "special offer", "discount",
            "promo code", "coupon", "free shipping", "free trial",
            "unsubscribe", "opt out", "manage preferences",
            "you're invited", "early access", "members only",
            "black friday", "cyber monday", "sale ends",
            "deal of the day", "today only", "lightning deal",
            "back in stock", "price drop", "new arrivals",
            "buy ", "shop ", "order now", "just dropped",
            "wish list", "wishlist", "in the cart", "in your cart",
            "points expiring", "redeem", "claim", "grab them"
        )
    }
}
