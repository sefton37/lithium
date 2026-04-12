package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted notification event.
 *
 * Schema version history:
 *   v1: initial scaffold
 *   v2: added is_from_contact
 *   v3: behavioral learning (app_behavior_profiles table)
 *   v4: added tier (0-3) and tier_reason for notification tier classification
 *   v9: added disposition — how Lithium handled this notification
 */
@Entity(tableName = "notifications")
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "package_name")
    val packageName: String = "",

    @ColumnInfo(name = "posted_at_ms")
    val postedAtMs: Long = 0L,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "text")
    val text: String? = null,

    @ColumnInfo(name = "channel_id")
    val channelId: String? = null,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "is_ongoing")
    val isOngoing: Boolean = false,

    @ColumnInfo(name = "removed_at_ms")
    val removedAtMs: Long? = null,

    @ColumnInfo(name = "removal_reason")
    val removalReason: String? = null,

    @ColumnInfo(name = "ai_classification")
    val aiClassification: String? = null,

    @ColumnInfo(name = "ai_confidence")
    val aiConfidence: Float? = null,

    @ColumnInfo(name = "rule_id_matched")
    val ruleIdMatched: Long? = null,

    /** True if the notification sender was found in the device contacts. Set by ContactsResolver. */
    @ColumnInfo(name = "is_from_contact")
    val isFromContact: Boolean = false,

    /**
     * Tier assigned by [ai.talkingrock.lithium.classification.TierClassifier] at capture time.
     *   0 = Invisible (media, ongoing noise, self)
     *   1 = Noise (marketing, LinkedIn, Amazon)
     *   2 = Worth seeing (Gmail, calendar, financial, GitHub) — DEFAULT
     *   3 = Interrupt (SMS, calls, 2FA, security)
     */
    @ColumnInfo(name = "tier")
    val tier: Int = 2,

    /** Short code explaining why this tier was assigned. E.g. "sms", "media_player", "default". */
    @ColumnInfo(name = "tier_reason")
    val tierReason: String? = null,

    /**
     * How Lithium handled this notification. Populated by [LithiumNotificationListener].
     *
     * Values:
     *   "allowed"       — notification passed through unmodified (no cancel).
     *   "suppressed"    — notification was cancelled by a SUPPRESS rule.
     *   "queued"        — notification was cancelled and placed in the review queue.
     *   "resurfaced"    — notification was cancelled and reposted as a Lithium curated copy.
     *   "safety_exempt" — notification was exempted from cancellation by [SafetyAllowlist].
     *
     * Null for records written before v9 (pre-shade-mode-alpha).
     */
    @ColumnInfo(name = "disposition")
    val disposition: String? = null,
)
