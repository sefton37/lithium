package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [NotificationRecord].
 *
 * Phase 0: minimal method set required for Room to compile the database.
 * Full query set (getRecent, getUnclassified, getByPackage, deleteOlderThan, etc.)
 * implemented in M1.
 */
@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: NotificationRecord): Long

    @Query("SELECT * FROM notifications ORDER BY posted_at_ms DESC")
    fun getAll(): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NotificationRecord?
}
