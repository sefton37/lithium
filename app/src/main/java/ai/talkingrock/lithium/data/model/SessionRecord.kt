package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * App usage session record — correlates a notification tap with an app session.
 *
 * Written by UsageTracker (M2) when a notification tap (REASON_CLICK) leads to
 * an app session. [startedAtMs] is the ACTIVITY_RESUMED event time, [endedAtMs]
 * is the ACTIVITY_PAUSED event time. [durationMs] is derived but stored for query
 * convenience. [packageName] identifies which app was opened.
 */
@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Package name of the app opened after the notification tap. */
    @ColumnInfo(name = "package_name")
    val packageName: String = "",

    /** Timestamp of ACTIVITY_RESUMED event (milliseconds UTC). */
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long = 0L,

    /** Timestamp of ACTIVITY_PAUSED event, or null if the session has not ended. */
    @ColumnInfo(name = "ended_at_ms")
    val endedAtMs: Long? = null,

    /** Session duration in milliseconds. Null if the session has not ended. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    /** Number of notifications received during this session window (unused in M2, for M4). */
    @ColumnInfo(name = "notifications_received")
    val notificationsReceived: Int = 0
)
