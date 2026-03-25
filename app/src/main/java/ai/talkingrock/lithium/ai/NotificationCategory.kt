package ai.talkingrock.lithium.ai

/**
 * Classification categories for notifications.
 *
 * These seven labels are the output space for the AI classifier and the heuristic fallback.
 * The [label] property is the string stored in [ai.talkingrock.lithium.data.model.NotificationRecord.aiClassification].
 */
enum class NotificationCategory(val label: String) {
    /** Direct human communication: messages, calls, emails from real contacts. */
    PERSONAL("personal"),

    /** Algorithmic content designed to pull the user back to an app. */
    ENGAGEMENT_BAIT("engagement_bait"),

    /** Marketing, offers, discounts, newsletters. */
    PROMOTIONAL("promotional"),

    /** 2FA codes, delivery tracking, receipts, payment confirmations, booking confirmations. */
    TRANSACTIONAL("transactional"),

    /** OS, platform, or device notifications: battery, storage, updates, connectivity. */
    SYSTEM("system"),

    /**
     * Ongoing/persistent background updates that are not user-facing alerts:
     * media playback controls, navigation, weather widgets, fitness trackers, ongoing downloads.
     * These are definitionally non-interruptive and should not count against the alert total.
     */
    BACKGROUND("background"),

    /** Social interactions without direct human intent: likes, follows, reactions, comment counts. */
    SOCIAL_SIGNAL("social_signal"),

    /** Could not be classified with sufficient confidence. */
    UNKNOWN("unknown");

    companion object {
        /** Returns the enum member whose [label] matches [value], or [UNKNOWN] if no match. */
        fun fromLabel(value: String): NotificationCategory =
            entries.firstOrNull { it.label == value } ?: UNKNOWN
    }
}
