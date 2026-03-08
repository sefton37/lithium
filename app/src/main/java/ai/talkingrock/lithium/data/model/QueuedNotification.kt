package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A notification held in the user-review queue (M2: Queue screen).
 * Stub schema for Room compilation. Full schema in M1.
 */
@Entity(tableName = "queue")
data class QueuedNotification(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "notification_id")
    val notificationId: Long = 0L,

    @ColumnInfo(name = "queued_at_ms")
    val queuedAtMs: Long = 0L,

    /** "pending" | "dismissed" | "actioned" */
    @ColumnInfo(name = "status")
    val status: String = "pending",

    @ColumnInfo(name = "actioned_at_ms")
    val actionedAtMs: Long? = null
)
