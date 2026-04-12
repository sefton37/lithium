package ai.talkingrock.lithium.classification

/**
 * Assigns a notification tier at capture time based on package name, content, and metadata.
 *
 * Tiers:
 *   0 — Invisible: auto-dismiss, never report (media players, self, ongoing noise)
 *   1 — Noise: log only, daily count (marketing, LinkedIn, Amazon, Play Store)
 *   2 — Worth seeing: surface in hourly check (Gmail, calendar, financial, GitHub)  [DEFAULT]
 *   3 — Interrupt: immediate attention (SMS, calls, 2FA, security alerts)
 *
 * CRM lookups (checking sender against whitelist) are stubbed — see Phase 2.
 * When CRM is wired in, SMS from family and email from whitelist contacts will
 * be elevated to Tier 3 dynamically.
 */
object TierClassifier {

    fun classify(
        packageName: String,
        title: String?,
        text: String?,
        isOngoing: Boolean,
        category: String?,
    ): Pair<Int, String> {

        val fullText = listOfNotNull(title, text).joinToString(" ").lowercase()

        if (packageName == "ai.talkingrock.lithium") {
            return 0 to "self"
        }

        if (packageName in MEDIA_PLAYER_PACKAGES) {
            return 0 to "media_player"
        }

        if (packageName in SYSTEM_STATUS_PACKAGES) {
            return 0 to "system_status"
        }

        if (category == CATEGORY_CALL || packageName == "com.google.android.dialer") {
            return 3 to "call"
        }

        if (packageName in MESSAGING_PACKAGES) {
            return 3 to "sms"
        }

        if (containsSecurityKeyword(fullText)) {
            return 3 to "security_2fa"
        }

        if (category == CATEGORY_TRANSPORT) {
            return 0 to "media_transport"
        }

        if (isOngoing) {
            return 0 to "ongoing_persistent"
        }

        if (packageName in CALENDAR_PACKAGES || category == CATEGORY_REMINDER) {
            return 2 to "calendar"
        }

        if (packageName == "com.google.android.gm") {
            return 2 to "gmail"
        }

        if (isSchoolPackage(packageName)) {
            return 2 to "school"
        }

        if (isFinancialPackage(packageName)) {
            return 2 to "financial"
        }

        if (packageName == "com.github.android") {
            return 2 to "github"
        }

        if (packageName.startsWith("com.linkedin.")) {
            return 1 to "linkedin"
        }

        if (packageName.startsWith("com.amazon.")) {
            return 1 to "amazon_shopping"
        }

        if (packageName == "com.android.vending") {
            return 1 to "play_store_update"
        }

        if (containsMarketingKeyword(fullText)) {
            return 1 to "marketing_text"
        }

        return 2 to "default"
    }

    private const val CATEGORY_CALL = "call"
    private const val CATEGORY_TRANSPORT = "transport"
    private const val CATEGORY_REMINDER = "reminder"

    private val MEDIA_PLAYER_PACKAGES = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
    )

    private val SYSTEM_STATUS_PACKAGES = setOf(
        "com.tailscale.ipn",
    )

    private val MESSAGING_PACKAGES = setOf(
        "com.google.android.apps.messaging",
    )

    private val CALENDAR_PACKAGES = setOf(
        "com.google.android.calendar",
        "com.android.calendar",
    )

    private fun isSchoolPackage(pkg: String): Boolean =
        pkg.contains("school", ignoreCase = true) ||
        pkg.contains("pikmykid", ignoreCase = true) ||
        pkg.contains("donges", ignoreCase = true)

    private fun isFinancialPackage(pkg: String): Boolean =
        pkg.startsWith("com.chase.") ||
        pkg.startsWith("com.americanexpress.") ||
        pkg.startsWith("com.citi.") ||
        pkg.contains("optum", ignoreCase = true) ||
        pkg.contains("vanguard", ignoreCase = true)

    private val SECURITY_KEYWORDS = listOf(
        "verification code",
        "security code",
        "2fa",
        "two-factor",
        "sign in attempt",
        "login attempt",
        "otp",
        "one-time password",
        "one-time code",
        "sign-in code",
    )

    private fun containsSecurityKeyword(text: String): Boolean =
        SECURITY_KEYWORDS.any { text.contains(it) }

    private val MARKETING_KEYWORDS = listOf(
        "unsubscribe",
        "% off",
        "limited time",
        "deal of the day",
        "flash sale",
        "special offer",
        "promo code",
    )

    private fun containsMarketingKeyword(text: String): Boolean =
        MARKETING_KEYWORDS.any { text.contains(it) }
}
