package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.NotificationRecord
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a [NotificationRecord] into one of the six [NotificationCategory] labels.
 *
 * Architecture: two-tier.
 *
 * Tier 1 — ONNX model inference via [AiEngine.classify]. Used when a model file has been
 * downloaded and loaded. For the initial MVP the model integration is a stub (see AiEngine),
 * so Tier 1 always falls through to Tier 2 for now.
 *
 * Tier 2 — Deterministic heuristic classifier. Rule-based logic covering the most common
 * patterns. Produces the classification for every record in the MVP. High precision for
 * the easy cases (contacts, system apps, 2FA), lower confidence for ambiguous social content.
 * This is the real MVP classifier — ONNX can be swapped in transparently via Tier 1 when ready.
 *
 * The input prompt format (consistent with PLAN.md §M3.2):
 *   [APP: {packageName}] [CHANNEL: {channelId}] [TITLE: {title}] [TEXT: {text}]
 */
@Singleton
class NotificationClassifier @Inject constructor(
    private val aiEngine: AiEngine
) {

    /**
     * Classifies [record] and returns a [ClassificationResult].
     *
     * Never throws. Returns [NotificationCategory.UNKNOWN] with 0.0 confidence on failure.
     */
    fun classify(record: NotificationRecord): ClassificationResult {
        val prompt = buildPrompt(record)

        // Tier 1: ONNX model (no-op until model is downloaded and inference is wired)
        val modelResult = aiEngine.classify(prompt)
        if (modelResult != null) {
            Log.d(TAG, "classify: ONNX result ${modelResult.label} (${modelResult.confidence}) for pkg=${record.packageName}")
            return modelResult
        }

        // Tier 2: heuristic fallback
        val heuristicResult = classifyHeuristic(record)
        Log.d(TAG, "classify: heuristic result ${heuristicResult.label} (${heuristicResult.confidence}) for pkg=${record.packageName}")
        return heuristicResult
    }

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
