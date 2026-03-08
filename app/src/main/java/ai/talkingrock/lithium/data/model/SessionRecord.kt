package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Listener service session record — tracks when the service was running.
 * Full schema in M1.
 */
@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long = 0L,

    @ColumnInfo(name = "ended_at_ms")
    val endedAtMs: Long? = null,

    @ColumnInfo(name = "notifications_received")
    val notificationsReceived: Int = 0
)
