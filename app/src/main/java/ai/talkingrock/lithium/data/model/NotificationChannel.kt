package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Cached channel display-name entry for a (packageName, channelId) pair.
 *
 * Populated by [ai.talkingrock.lithium.service.LithiumNotificationListener] at notification
 * ingest time via the RankingMap channel name API. The display name is nullable — the system
 * does not surface it for all channels on all API levels.
 *
 * [lastSeenMs] tracks freshness; used to detect stale entries without requiring deletion.
 *
 * Schema version history:
 *   v10: initial table — added as part of channel-level training mode.
 */
@Entity(
    tableName = "notification_channels",
    primaryKeys = ["package_name", "channel_id"]
)
data class NotificationChannel(
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "channel_id")
    val channelId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String?,

    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long,
)
