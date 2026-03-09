package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.Report
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [Report]. Phase 0 stub — full implementation in M1.
 */
@Dao
interface ReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: Report): Long

    @Query("SELECT * FROM reports WHERE reviewed = 0 ORDER BY generated_at_ms DESC LIMIT 1")
    fun getLatestUnreviewed(): Flow<Report?>

    @Query("UPDATE reports SET reviewed = 1 WHERE id = :id")
    suspend fun markReviewed(id: Long)

    /** Delete all report records. Used by purge-all-data. */
    @Query("DELETE FROM reports")
    suspend fun deleteAll()

    /** Returns approximate count of rows in reports table. */
    @Query("SELECT COUNT(*) FROM reports")
    suspend fun count(): Int
}
