package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted notification event.
 *
 * Full schema implementation in M1. This stub satisfies Room's requirement
 * that every entity in @Database has at least a @PrimaryKey.
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
    val isFromContact: Boolean = false
)
