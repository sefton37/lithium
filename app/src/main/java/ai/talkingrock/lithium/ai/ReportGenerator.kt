package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.Report
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a plain-language daily briefing report from 24-hour notification data.
 *
 * This is a template-based generator, not LLM-driven. The output is structured JSON
 * stored in [Report.summaryJson], with a human-readable [text] field that the UI surfaces
 * directly. The tone is warm and supportive — written for someone who may be overwhelmed,
 * not a productivity dashboard.
 *
 * Report structure (3–5 sentences):
 *   1. Total count + category breakdown.
 *   2. Which apps sent the most algorithmic/attention-seeking content.
 *   3. Contact vs. algorithmic split.
 *   4. A notable pattern if one exists (e.g. high send / low tap ratio on an app).
 */
@Singleton
class ReportGenerator @Inject constructor() {

    /**
     * Builds a [Report] entity from pre-aggregated [PatternAnalyzer] outputs.
     *
     * @param byCategory    Notifications grouped by AI category.
     * @param appStats      Per-app statistics sorted by volume.
     * @param contactVsAlgo (contactCount, algorithmicCount) pair.
     * @param generatedAtMs Timestamp for the report (normally [System.currentTimeMillis]).
     */
    fun generate(
        byCategory: Map<NotificationCategory, List<ai.talkingrock.lithium.data.model.NotificationRecord>>,
        appStats: List<AppStats>,
        contactVsAlgo: Pair<Int, Int>,
        generatedAtMs: Long = System.currentTimeMillis()
    ): Report {
        val total = byCategory.values.sumOf { it.size }
        val text = buildReportText(total, byCategory, appStats, contactVsAlgo)
        val json = buildSummaryJson(total, byCategory, appStats, contactVsAlgo, text)

        return Report(
            generatedAtMs = generatedAtMs,
            summaryJson = json,
            reviewed = false
        )
    }

    // -----------------------------------------------------------------------------------------
    // Text assembly
    // -----------------------------------------------------------------------------------------

    private fun buildReportText(
        total: Int,
        byCategory: Map<NotificationCategory, List<ai.talkingrock.lithium.data.model.NotificationRecord>>,
        appStats: List<AppStats>,
        contactVsAlgo: Pair<Int, Int>
    ): String = buildString {
        if (total == 0) {
            append("It was a quiet day — no notifications arrived in the past 24 hours.")
            return@buildString
        }

        // Sentence 1: total + breakdown
        append(buildCountSentence(total, byCategory))
        append(" ")

        // Sentence 2: top attention-consuming apps (by total volume of engagement_bait/social_signal)
        val noisyApps = appStats
            .filter { it.totalCount >= MIN_NOTABLE_COUNT }
            .take(3)
        if (noisyApps.isNotEmpty()) {
            append(buildTopAppsSentence(noisyApps))
            append(" ")
        }

        // Sentence 3: contact vs algorithmic ratio
        val (contactCount, algoCount) = contactVsAlgo
        if (contactCount > 0 || algoCount > 0) {
            append(buildRatioSentence(contactCount, algoCount))
            append(" ")
        }

        // Sentence 4: notable pattern (only if clearly worth surfacing)
        val notablePattern = findNotablePattern(appStats)
        if (notablePattern != null) {
            append(notablePattern)
        }
    }.trim()

    private fun buildCountSentence(
        total: Int,
        byCategory: Map<NotificationCategory, List<ai.talkingrock.lithium.data.model.NotificationRecord>>
    ): String {
        val breakdown = DISPLAY_ORDER
            .mapNotNull { cat ->
                val count = byCategory[cat]?.size ?: 0
                if (count > 0) "$count ${cat.displayName()}" else null
            }
            .joinToString(", ")

        return if (breakdown.isBlank()) {
            "You received $total notification${if (total != 1) "s" else ""} in the past 24 hours."
        } else {
            "You received $total notification${if (total != 1) "s" else ""} in the past 24 hours: $breakdown."
        }
    }

    private fun buildTopAppsSentence(topApps: List<AppStats>): String {
        val appNames = topApps.joinToString(", ") { friendlyName(it.packageName) }
        return if (topApps.size == 1) {
            "Most of that came from $appNames."
        } else {
            "The busiest sources were $appNames."
        }
    }

    private fun buildRatioSentence(contactCount: Int, algoCount: Int): String {
        return when {
            contactCount == 0 && algoCount == 0 -> ""
            contactCount == 0 ->
                "None of these came from people in your contacts — they were all algorithmic."
            algoCount == 0 ->
                "Every notification came from someone you know. That's a good sign."
            contactCount > algoCount ->
                "More of these were from real people ($contactCount) than algorithmic content ($algoCount)."
            algoCount > contactCount * 3 ->
                "Only $contactCount came from people you know — $algoCount were algorithmically generated."
            else ->
                "$contactCount were from people in your contacts; $algoCount were algorithmic."
        }
    }

    /**
     * Returns a sentence about a single notable pattern if one is clear enough to mention.
     * Avoids surfacing marginal patterns that would feel accusatory or inaccurate.
     */
    private fun findNotablePattern(appStats: List<AppStats>): String? {
        // High send / low tap: app sent many but user almost never tapped
        val highSendLowTap = appStats.firstOrNull { stats ->
            stats.totalCount >= HIGH_SEND_THRESHOLD &&
            stats.tappedCount == 0
        }
        if (highSendLowTap != null) {
            val name = friendlyName(highSendLowTap.packageName)
            return "$name sent ${highSendLowTap.totalCount} notifications — you didn't open any of them."
        }

        // Moderate send, very low tap rate
        val lowEngagement = appStats.firstOrNull { stats ->
            stats.totalCount >= MIN_NOTABLE_COUNT &&
            stats.tappedCount > 0 &&
            stats.tappedCount.toFloat() / stats.totalCount < LOW_TAP_RATE_THRESHOLD
        }
        if (lowEngagement != null) {
            val name = friendlyName(lowEngagement.packageName)
            return "$name sent ${lowEngagement.totalCount} notifications and you only tapped ${lowEngagement.tappedCount}."
        }

        return null
    }

    // -----------------------------------------------------------------------------------------
    // JSON summary (stored in Report.summaryJson for structured access)
    // -----------------------------------------------------------------------------------------

    private fun buildSummaryJson(
        total: Int,
        byCategory: Map<NotificationCategory, List<ai.talkingrock.lithium.data.model.NotificationRecord>>,
        appStats: List<AppStats>,
        contactVsAlgo: Pair<Int, Int>,
        text: String
    ): String {
        val obj = buildJsonObject {
            put("text", text)
            put("total", total)
            putJsonObject("by_category") {
                byCategory.forEach { (cat, records) ->
                    put(cat.label, records.size)
                }
            }
            putJsonObject("contact_vs_algorithmic") {
                put("contact", contactVsAlgo.first)
                put("algorithmic", contactVsAlgo.second)
            }
            // Top 5 apps by volume
            put("top_apps", kotlinx.serialization.json.buildJsonArray {
                appStats.take(5).forEach { stats ->
                    add(buildJsonObject {
                        put("package", stats.packageName)
                        put("total", stats.totalCount)
                        put("tapped", stats.tappedCount)
                        put("ignored", stats.ignoredCount)
                        put("avg_session_ms", stats.avgSessionDurationMs)
                    })
                }
            })
        }
        return Json.encodeToString(obj)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** Returns a human-readable display name for a notification category. */
    private fun NotificationCategory.displayName(): String = when (this) {
        NotificationCategory.PERSONAL        -> "personal"
        NotificationCategory.ENGAGEMENT_BAIT -> "engagement bait"
        NotificationCategory.PROMOTIONAL     -> "promotional"
        NotificationCategory.TRANSACTIONAL   -> "transactional"
        NotificationCategory.SYSTEM          -> "system"
        NotificationCategory.SOCIAL_SIGNAL   -> "social signal"
        NotificationCategory.UNKNOWN         -> "uncategorised"
    }

    /**
     * Derives a short, readable app name from an Android package name.
     * Best-effort: extracts the last segment and capitalises it.
     * e.g. "com.instagram.android" → "Instagram"
     */
    internal fun friendlyName(packageName: String): String {
        val KNOWN_NAMES = mapOf(
            "com.instagram.android"              to "Instagram",
            "com.facebook.katana"                to "Facebook",
            "com.twitter.android"                to "Twitter / X",
            "com.zhiliaoapp.musically"           to "TikTok",
            "com.snapchat.android"               to "Snapchat",
            "com.reddit.frontpage"               to "Reddit",
            "com.linkedin.android"               to "LinkedIn",
            "com.google.android.youtube"         to "YouTube",
            "com.whatsapp"                       to "WhatsApp",
            "org.telegram.messenger"             to "Telegram",
            "com.discord"                        to "Discord",
            "com.slack"                          to "Slack",
            "com.microsoft.teams"                to "Microsoft Teams",
            "com.google.android.gm"              to "Gmail",
            "com.microsoft.office.outlook"       to "Outlook",
            "org.thoughtcrime.securesms"         to "Signal",
            "com.pinterest"                      to "Pinterest",
            "com.tumblr"                         to "Tumblr"
        )
        KNOWN_NAMES[packageName]?.let { return it }

        // Strip leading segments: "com.example.myapp" → "myapp"
        val last = packageName.substringAfterLast('.')
        // Split on non-alphanumeric and capitalise each word
        return last
            .replace(Regex("[^A-Za-z0-9]"), " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .ifBlank { packageName }
    }

    companion object {
        /** Minimum notification count for an app to appear in the "top apps" sentence. */
        private const val MIN_NOTABLE_COUNT = 3

        /** Apps that sent this many or more and received zero taps are highlighted. */
        private const val HIGH_SEND_THRESHOLD = 10

        /** Tap rate below this fraction triggers a low-engagement note. */
        private const val LOW_TAP_RATE_THRESHOLD = 0.10f

        /** The order in which categories appear in the count sentence breakdown. */
        private val DISPLAY_ORDER = listOf(
            NotificationCategory.PERSONAL,
            NotificationCategory.ENGAGEMENT_BAIT,
            NotificationCategory.SOCIAL_SIGNAL,
            NotificationCategory.PROMOTIONAL,
            NotificationCategory.TRANSACTIONAL,
            NotificationCategory.SYSTEM,
            NotificationCategory.UNKNOWN
        )
    }
}
