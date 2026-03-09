package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.SessionRecord
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [SessionRecord].
 *
 * Session records are written by UsageTracker (M2) when a notification tap leads to an app session.
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionRecord): Long

    /** Returns the most recent session, or null if no sessions exist. */
    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC LIMIT 1")
    fun getLatest(): Flow<SessionRecord?>

    /**
     * Returns all sessions that started within the given time range, newest first.
     * Used by PatternAnalyzer (M4) for the 24h reporting window.
     */
    @Query("SELECT * FROM sessions WHERE started_at_ms >= :sinceMs ORDER BY started_at_ms DESC")
    suspend fun getSessionsSince(sinceMs: Long): List<SessionRecord>

    /** Hard-deletes sessions older than [thresholdMs]. Called by data-retention cleanup. */
    @Query("DELETE FROM sessions WHERE started_at_ms < :thresholdMs")
    suspend fun deleteOlderThan(thresholdMs: Long)

    /** Delete all session records. Used by purge-all-data. */
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
