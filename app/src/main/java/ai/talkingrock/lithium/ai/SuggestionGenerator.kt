package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.RuleCondition
import ai.talkingrock.lithium.data.model.Suggestion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates actionable rule suggestions from 24-hour notification data.
 *
 * Conservative by design:
 * - Never suggests suppressing [NotificationCategory.PERSONAL] or [NotificationCategory.TRANSACTIONAL].
 * - Prefers 'queue' over 'suppress' when engagement evidence is ambiguous.
 * - Only acts on apps with a clear, sustained pattern — not one-off outliers.
 *
 * Each [Suggestion] includes a human-readable [Suggestion.rationale] that the user sees
 * in the Briefing screen. The [Suggestion.conditionJson] encodes a [RuleCondition] ready
 * for the rule engine if the user accepts.
 */
@Singleton
class SuggestionGenerator @Inject constructor() {

    /**
     * Analyses [byCategory], [appStats], and the raw [notifications] list to produce
     * zero or more [Suggestion] entities. Results are unlinked to a report until the
     * caller sets [Suggestion.reportId].
     */
    fun generate(
        byCategory: Map<NotificationCategory, List<NotificationRecord>>,
        appStats: List<AppStats>,
        notifications: List<NotificationRecord>
    ): List<Suggestion> {
        val suggestions = mutableListOf<Suggestion>()

        for (stats in appStats) {
            // Skip apps with too little data to reason about.
            if (stats.totalCount < MIN_NOTIFICATIONS_TO_SUGGEST) continue

            val category = dominantCategory(stats.packageName, byCategory)

            // Safety: never suggest suppressing personal or transactional notifications.
            if (category == NotificationCategory.PERSONAL ||
                category == NotificationCategory.TRANSACTIONAL) continue

            val tapRate = if (stats.totalCount > 0) {
                stats.tappedCount.toFloat() / stats.totalCount
            } else 0f

            when {
                // High-volume engagement bait with very low engagement → suppress
                category == NotificationCategory.ENGAGEMENT_BAIT &&
                stats.totalCount >= HIGH_VOLUME_THRESHOLD &&
                tapRate < SUPPRESS_TAP_RATE_THRESHOLD -> {
                    suggestions.add(
                        buildSuppressSuggestion(
                            stats,
                            "You received ${stats.totalCount} engagement notifications from " +
                            "${friendlyName(stats.packageName)} and tapped almost none of them. " +
                            "Suppressing these would remove them before they interrupt you."
                        )
                    )
                }

                // Heavy promotional volume, essentially no engagement → suppress
                category == NotificationCategory.PROMOTIONAL &&
                stats.totalCount >= HIGH_VOLUME_THRESHOLD &&
                tapRate < SUPPRESS_TAP_RATE_THRESHOLD -> {
                    suggestions.add(
                        buildSuppressSuggestion(
                            stats,
                            "${friendlyName(stats.packageName)} sent ${stats.totalCount} promotional " +
                            "notifications. You opened none of them — they may be safe to silence."
                        )
                    )
                }

                // Moderate social signals from non-contacts with low tap rate → queue
                category == NotificationCategory.SOCIAL_SIGNAL &&
                stats.totalCount >= MIN_NOTIFICATIONS_TO_SUGGEST &&
                tapRate < QUEUE_TAP_RATE_THRESHOLD &&
                noContactNotifications(stats.packageName, notifications) -> {
                    suggestions.add(
                        buildQueueSuggestion(
                            stats,
                            "${friendlyName(stats.packageName)} sent ${stats.totalCount} social " +
                            "notifications from people outside your contacts. Queuing them means " +
                            "you can review them when you're ready rather than being interrupted."
                        )
                    )
                }

                // Moderate engagement-bait with some engagement → queue (not suppress)
                category == NotificationCategory.ENGAGEMENT_BAIT &&
                stats.totalCount >= MIN_NOTIFICATIONS_TO_SUGGEST &&
                tapRate < QUEUE_TAP_RATE_THRESHOLD -> {
                    suggestions.add(
                        buildQueueSuggestion(
                            stats,
                            "You occasionally tap notifications from ${friendlyName(stats.packageName)}, " +
                            "but most of them go untouched. Queuing them keeps them available " +
                            "without the constant interruptions."
                        )
                    )
                }
            }
        }

        // Cap to avoid overwhelming the user with suggestions in one briefing.
        return suggestions.take(MAX_SUGGESTIONS_PER_REPORT)
    }

    // -----------------------------------------------------------------------------------------
    // Suggestion builders
    // -----------------------------------------------------------------------------------------

    private fun buildSuppressSuggestion(stats: AppStats, rationale: String): Suggestion {
        val condition = RuleCondition.PackageMatch(packageName = stats.packageName)
        return Suggestion(
            conditionJson = Json.encodeToString(condition),
            action = "suppress",
            rationale = rationale,
            status = "pending"
        )
    }

    private fun buildQueueSuggestion(stats: AppStats, rationale: String): Suggestion {
        val condition = RuleCondition.PackageMatch(packageName = stats.packageName)
        return Suggestion(
            conditionJson = Json.encodeToString(condition),
            action = "queue",
            rationale = rationale,
            status = "pending"
        )
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Returns the most common non-UNKNOWN category for notifications from [packageName],
     * or [NotificationCategory.UNKNOWN] if no classified notifications exist.
     */
    private fun dominantCategory(
        packageName: String,
        byCategory: Map<NotificationCategory, List<NotificationRecord>>
    ): NotificationCategory {
        var best: NotificationCategory = NotificationCategory.UNKNOWN
        var bestCount = 0
        for ((cat, records) in byCategory) {
            if (cat == NotificationCategory.UNKNOWN) continue
            val count = records.count { it.packageName == packageName }
            if (count > bestCount) {
                bestCount = count
                best = cat
            }
        }
        return best
    }

    /**
     * Returns true if none of the notifications from [packageName] are from a contact.
     * Used to avoid queuing/suppressing apps where the user has real relationships.
     */
    private fun noContactNotifications(
        packageName: String,
        notifications: List<NotificationRecord>
    ): Boolean = notifications
        .filter { it.packageName == packageName }
        .none { it.isFromContact }

    /** Derives a short human-readable app name from a package name (same logic as ReportGenerator). */
    private fun friendlyName(packageName: String): String {
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
        val last = packageName.substringAfterLast('.')
        return last
            .replace(Regex("[^A-Za-z0-9]"), " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .ifBlank { packageName }
    }

    companion object {
        /** Minimum total notifications from an app to consider generating a suggestion. */
        private const val MIN_NOTIFICATIONS_TO_SUGGEST = 5

        /** Tap rate below this fraction (when volume is high) triggers a suppress suggestion. */
        private const val SUPPRESS_TAP_RATE_THRESHOLD = 0.05f

        /** Tap rate below this fraction triggers a queue suggestion instead. */
        private const val QUEUE_TAP_RATE_THRESHOLD = 0.20f

        /** Volume threshold above which 'suppress' is considered (not just 'queue'). */
        private const val HIGH_VOLUME_THRESHOLD = 10

        /** Maximum suggestions to surface in a single report. Avoids decision fatigue. */
        private const val MAX_SUGGESTIONS_PER_REPORT = 3
    }
}
