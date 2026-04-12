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
class ReportGenerator @Inject constructor(
    private val appLabels: AppLabelResolver
) {

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
        val backgroundCount = byCategory[NotificationCategory.BACKGROUND]?.size ?: 0
        val alertCount = total - backgroundCount

        if (total == 0) {
            append("It was a quiet day — no notifications arrived in the past 24 hours.")
            return@buildString
        }

        // Sentence 1: alert count + category breakdown (BACKGROUND excluded from breakdown)
        append(buildCountSentence(alertCount, backgroundCount, byCategory))
        append(" ")

        // Sentence 2: top attention-consuming apps — exclude purely-background apps
        val alertApps = appStats.filter { stats ->
            val dominantCat = byCategory
                .filter { (_, records) -> records.any { it.packageName == stats.packageName } }
                .maxByOrNull { (_, records) -> records.count { it.packageName == stats.packageName } }
                ?.key
            dominantCat != NotificationCategory.BACKGROUND
        }
        val noisyApps = alertApps
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

        // Sentence 4: notable pattern — exclude background-only apps (high send / zero tap
        // is expected behaviour for media players, not a meaningful insight)
        val alertAppStats = appStats.filter { stats ->
            val dominantCat = byCategory
                .filter { (_, records) -> records.any { it.packageName == stats.packageName } }
                .maxByOrNull { (_, records) -> records.count { it.packageName == stats.packageName } }
                ?.key
            dominantCat != NotificationCategory.BACKGROUND
        }
        val notablePattern = findNotablePattern(alertAppStats)
        if (notablePattern != null) {
            append(notablePattern)
        }
    }.trim()

    private fun buildCountSentence(
        alertCount: Int,
        backgroundCount: Int,
        byCategory: Map<NotificationCategory, List<ai.talkingrock.lithium.data.model.NotificationRecord>>
    ): String {
        // Breakdown excludes BACKGROUND — those are not meaningful alert categories.
        val breakdown = DISPLAY_ORDER
            .filter { it != NotificationCategory.BACKGROUND }
            .mapNotNull { cat ->
                val count = byCategory[cat]?.size ?: 0
                if (count > 0) "$count ${cat.displayName()}" else null
            }
            .joinToString(", ")

        val alertWord = if (alertCount != 1) "alerts" else "alert"
        val base = if (breakdown.isBlank()) {
            "You received $alertCount $alertWord in the past 24 hours."
        } else {
            "You received $alertCount $alertWord in the past 24 hours: $breakdown."
        }

        return if (backgroundCount > 0) {
            "$base ($backgroundCount background updates were filtered.)"
        } else {
            base
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
            contactCount == 0 && algoCount > 0 ->
                "None of these came from people in your contacts — $algoCount were algorithmic."
            algoCount == 0 && contactCount > 0 ->
                "$contactCount came from people in your contacts and none were algorithmically generated."
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
        val backgroundCount = byCategory[NotificationCategory.BACKGROUND]?.size ?: 0
        val alertCount = total - backgroundCount

        val obj = buildJsonObject {
            put("text", text)
            put("total", total)
            put("alert_count", alertCount)
            put("background_count", backgroundCount)
            putJsonObject("by_category") {
                // Background is a separate top-level field — keep it out of the category breakdown.
                byCategory.forEach { (cat, records) ->
                    if (cat != NotificationCategory.BACKGROUND) {
                        put(cat.label, records.size)
                    }
                }
            }
            putJsonObject("contact_vs_algorithmic") {
                put("contact", contactVsAlgo.first)
                put("algorithmic", contactVsAlgo.second)
            }
            // Top 5 apps by volume (all apps, including background, for full data fidelity)
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
        NotificationCategory.BACKGROUND      -> "background"
        NotificationCategory.SOCIAL_SIGNAL   -> "social signal"
        NotificationCategory.UNKNOWN         -> "uncategorised"
    }

    /** Uses the injected resolver so report text matches launcher labels. */
    internal fun friendlyName(packageName: String): String =
        appLabels.label(packageName)

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
            NotificationCategory.BACKGROUND,
            NotificationCategory.UNKNOWN
        )
    }
}
