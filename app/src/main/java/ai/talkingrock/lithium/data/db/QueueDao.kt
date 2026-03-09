package ai.talkingrock.lithium.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.QueuedNotification
import kotlinx.coroutines.flow.Flow

/**
 * Flattened view of a queued notification joined with its source [NotificationRecord].
 *
 * Used by the Queue screen to display the app name (package), title, text, and queue time
 * in a single query without requiring the UI to perform a second lookup.
 */
data class QueuedItem(
    @ColumnInfo(name = "id")           val id: Long,
    @ColumnInfo(name = "queued_at_ms") val queuedAtMs: Long,
    @ColumnInfo(name = "status")       val status: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "title")        val title: String?,
    @ColumnInfo(name = "text")         val text: String?
)

/**
 * DAO for [QueuedNotification]. Phase 0 stub — full implementation in M1.
 */
@Dao
interface QueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: QueuedNotification): Long

    @Query("SELECT * FROM queue WHERE status = 'pending' ORDER BY queued_at_ms ASC")
    fun getPendingQueue(): Flow<List<QueuedNotification>>

    /**
     * Pending queue items joined with source notification data.
     * Used by QueueViewModel to display app/title/text without a second lookup.
     */
    @Query("""
        SELECT q.id, q.queued_at_ms, q.status,
               n.package_name, n.title, n.text
        FROM queue q
        LEFT JOIN notifications n ON q.notification_id = n.id
        WHERE q.status = 'pending'
        ORDER BY q.queued_at_ms ASC
    """)
    fun getPendingQueueItems(): Flow<List<QueuedItem>>

    @Query("UPDATE queue SET status = :action, actioned_at_ms = :actionedAtMs WHERE id = :id")
    suspend fun markReviewed(id: Long, action: String, actionedAtMs: Long)

    /** Delete all queue entries whose status is not 'pending' (i.e. reviewed/dismissed). */
    @Query("DELETE FROM queue WHERE status != 'pending'")
    suspend fun clearReviewed()

    @Query("DELETE FROM queue")
    suspend fun clearAll()

    /** Delete all queue entries for the purge-all-data operation. */
    @Query("DELETE FROM queue")
    suspend fun deleteAll()
}
