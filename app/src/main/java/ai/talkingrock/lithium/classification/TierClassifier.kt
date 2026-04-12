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
 * Contact whitelist: the device's native contact list is the trust source.
 * SMS/calls/Gmail from a known contact → Tier 3 (interrupt). Same channels from
 * an unknown sender → Tier 2 (worth seeing). Security/2FA codes always → Tier 3
 * regardless of sender (checked before channel rules).
 */
object TierClassifier {

    fun classify(
        packageName: String,
        title: String?,
        text: String?,
        isOngoing: Boolean,
        category: String?,
        isFromContact: Boolean = false,
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

        // Security / 2FA codes are always Tier 3 — checked before channel rules
        // so an OTP from a short-code (unknown sender) isn't demoted.
        if (containsSecurityKeyword(fullText)) {
            return 3 to "security_2fa"
        }

        // Alarms are always Tier 3 — checked BEFORE the ongoing/transport checks.
        // CATEGORY_ALARM is plan-specified (Uncancellable Notification table, line ~360).
        // The SafetyAllowlist already bypasses cancellation for alarms; this classifier
        // entry ensures that if somehow an alarm notification reaches rule evaluation it
        // is always treated as an interrupt, not suppressed or queued.
        if (category == CATEGORY_ALARM) {
            return 3 to "alarm"
        }

        // Ongoing and transport notifications are always invisible — checked
        // BEFORE channel rules so e.g. "Messages is doing work in background"
        // (ongoing from com.google.android.apps.messaging) isn't misclassified
        // as Tier 2 sms_unknown.
        if (category == CATEGORY_TRANSPORT) {
            return 0 to "media_transport"
        }

        if (isOngoing) {
            return 0 to "ongoing_persistent"
        }

        if (category == CATEGORY_CALL || packageName == "com.google.android.dialer") {
            return if (isFromContact) 3 to "call_known" else 2 to "call_unknown"
        }

        if (packageName in MESSAGING_PACKAGES) {
            return if (isFromContact) 3 to "sms_known" else 2 to "sms_unknown"
        }

        if (packageName in CALENDAR_PACKAGES || category == CATEGORY_REMINDER) {
            return 2 to "calendar"
        }

        if (packageName == "com.google.android.gm") {
            return if (isFromContact) 3 to "gmail_known" else 2 to "gmail"
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

    private const val CATEGORY_ALARM = "alarm"
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
