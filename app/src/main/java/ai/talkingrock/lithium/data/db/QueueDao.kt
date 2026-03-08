package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.QueuedNotification
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [QueuedNotification]. Phase 0 stub — full implementation in M1.
 */
@Dao
interface QueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: QueuedNotification): Long

    @Query("SELECT * FROM queue WHERE status = 'pending' ORDER BY queued_at_ms ASC")
    fun getPendingQueue(): Flow<List<QueuedNotification>>

    @Query("UPDATE queue SET status = :action, actioned_at_ms = :actionedAtMs WHERE id = :id")
    suspend fun markReviewed(id: Long, action: String, actionedAtMs: Long)

    @Query("DELETE FROM queue")
    suspend fun clearAll()
}
