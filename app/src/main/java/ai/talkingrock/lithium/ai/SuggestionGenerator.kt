package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.RuleCondition
import ai.talkingrock.lithium.data.model.Suggestion
import kotlin.math.max
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
     * Backwards-compatible overload without profiles. Uses 24h data only.
     */
    fun generate(
        byCategory: Map<NotificationCategory, List<NotificationRecord>>,
        appStats: List<AppStats>,
        notifications: List<NotificationRecord>
    ): List<Suggestion> = generate(byCategory, appStats, notifications, profiles = emptyMap())

    /**
     * Profile-aware suggestion generation. Uses blended tap rate (24h + lifetime)
     * when behavioral profiles are available.
     *
     * @param profiles Map keyed by (packageName, channelId). Pass empty map for 24h-only behavior.
     */
    fun generate(
        byCategory: Map<NotificationCategory, List<NotificationRecord>>,
        appStats: List<AppStats>,
        notifications: List<NotificationRecord>,
        profiles: Map<Pair<String, String>, AppBehaviorProfile>
    ): List<Suggestion> {
        val suggestions = mutableListOf<Suggestion>()

        for (stats in appStats) {
            // Look up the package-level profile (channelId = "")
            val profile = profiles[Pair(stats.packageName, "")]

            // Dynamic minimum evidence: high-volume apps need more than 5 per cycle
            val effectiveMin = if (profile != null && profile.totalReceived > 150) {
                max(MIN_NOTIFICATIONS_TO_SUGGEST, profile.totalReceived / 30)
            } else {
                MIN_NOTIFICATIONS_TO_SUGGEST
            }

            // Skip apps with too little data to reason about.
            if (stats.totalCount < effectiveMin) continue

            val category = dominantCategory(stats.packageName, byCategory)

            // Safety: never suggest suppressing personal, transactional, or background notifications.
            // Background (media controls, navigation, etc.) are ongoing by design — no rule needed.
            if (category == NotificationCategory.PERSONAL ||
                category == NotificationCategory.TRANSACTIONAL ||
                category == NotificationCategory.BACKGROUND) continue

            val recentTapRate = if (stats.totalCount > 0) {
                stats.tappedCount.toFloat() / stats.totalCount
            } else 0f

            // Blended tap rate: weight lifetime data more heavily to prevent
            // single-day anomalies from triggering suggestions.
            val effectiveTapRate = if (profile != null && profile.totalReceived >= MINIMUM_PROFILE_EVIDENCE) {
                (recentTapRate * RECENT_TAP_RATE_WEIGHT) + (profile.lifetimeTapRate * LIFETIME_TAP_RATE_WEIGHT)
            } else {
                recentTapRate
            }

            val appName = friendlyName(stats.packageName)
            val hasLifetimeData = profile != null && profile.totalReceived >= MINIMUM_PROFILE_EVIDENCE
            val historyPrefix = if (hasLifetimeData) "Over your history with this app, " else ""

            when {
                // High-volume engagement bait with very low engagement → suppress
                // Suppress requires lifetime evidence to avoid premature suppression
                category == NotificationCategory.ENGAGEMENT_BAIT &&
                stats.totalCount >= HIGH_VOLUME_THRESHOLD &&
                effectiveTapRate < SUPPRESS_TAP_RATE_THRESHOLD &&
                (profile == null || profile.totalReceived >= LIFETIME_SUPPRESS_THRESHOLD) -> {
                    suggestions.add(
                        buildSuppressSuggestion(
                            stats,
                            "${historyPrefix}${appName} sends engagement notifications you almost " +
                            "never open. Suppressing these would remove them before they interrupt you."
                        )
                    )
                }

                // Heavy promotional volume, essentially no engagement → suppress
                category == NotificationCategory.PROMOTIONAL &&
                stats.totalCount >= HIGH_VOLUME_THRESHOLD &&
                effectiveTapRate < SUPPRESS_TAP_RATE_THRESHOLD &&
                (profile == null || profile.totalReceived >= LIFETIME_SUPPRESS_THRESHOLD) -> {
                    suggestions.add(
                        buildSuppressSuggestion(
                            stats,
                            "${historyPrefix}${appName} sends promotional notifications you almost " +
                            "never open — they may be safe to silence."
                        )
                    )
                }

                // Moderate social signals from non-contacts with low tap rate → queue
                category == NotificationCategory.SOCIAL_SIGNAL &&
                stats.totalCount >= MIN_NOTIFICATIONS_TO_SUGGEST &&
                effectiveTapRate < QUEUE_TAP_RATE_THRESHOLD &&
                noContactNotifications(stats.packageName, notifications) -> {
                    suggestions.add(
                        buildQueueSuggestion(
                            stats,
                            "${appName} sent ${stats.totalCount} social notifications from people " +
                            "outside your contacts. Queuing them means you can review them when " +
                            "you're ready rather than being interrupted."
                        )
                    )
                }

                // Moderate engagement-bait with some engagement → queue (not suppress)
                category == NotificationCategory.ENGAGEMENT_BAIT &&
                stats.totalCount >= MIN_NOTIFICATIONS_TO_SUGGEST &&
                effectiveTapRate < QUEUE_TAP_RATE_THRESHOLD -> {
                    suggestions.add(
                        buildQueueSuggestion(
                            stats,
                            "${historyPrefix}most notifications from ${appName} go untouched. " +
                            "Queuing them keeps them available without the constant interruptions."
                        )
                    )
                }

                // Moderate promotional volume with low engagement → queue
                category == NotificationCategory.PROMOTIONAL &&
                stats.totalCount >= MIN_NOTIFICATIONS_TO_SUGGEST &&
                effectiveTapRate < QUEUE_TAP_RATE_THRESHOLD -> {
                    suggestions.add(
                        buildQueueSuggestion(
                            stats,
                            "${historyPrefix}${appName} sends promotional notifications you rarely " +
                            "open. Queuing them keeps deals available without the constant pings."
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

    /** Delegates to shared [AppNames.friendlyName]. */
    private fun friendlyName(packageName: String): String =
        AppNames.friendlyName(packageName)

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

        // ── Behavioral learning thresholds ───────────────────────────────────

        /** Weight for the 24h tap rate in the blended calculation. */
        private const val RECENT_TAP_RATE_WEIGHT = 0.4f

        /** Weight for the lifetime tap rate in the blended calculation. */
        private const val LIFETIME_TAP_RATE_WEIGHT = 0.6f

        /** Minimum lifetime notifications before behavioral data is used. */
        private const val MINIMUM_PROFILE_EVIDENCE = 10

        /** Minimum lifetime notifications before suppress is considered. */
        private const val LIFETIME_SUPPRESS_THRESHOLD = 30
    }
}
