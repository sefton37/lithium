package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.Suggestion
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [Suggestion]. Phase 0 stub — full implementation in M1.
 */
@Dao
interface SuggestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestions(suggestions: List<Suggestion>)

    @Query("SELECT * FROM suggestions WHERE report_id = :reportId AND status = 'pending'")
    fun getPendingForReport(reportId: Long): Flow<List<Suggestion>>

    @Query("UPDATE suggestions SET status = :status, user_comment = :comment WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, comment: String?)
}
