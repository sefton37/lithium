package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.SessionRecord
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [SessionRecord]. Phase 0 stub — full implementation in M1.
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionRecord): Long

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC LIMIT 1")
    fun getLatest(): Flow<SessionRecord?>
}
