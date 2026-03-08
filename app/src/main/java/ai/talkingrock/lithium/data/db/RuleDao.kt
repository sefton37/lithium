package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.Rule
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [Rule]. Phase 0 stub — full implementation in M1.
 */
@Dao
interface RuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: Rule): Long

    @Query("SELECT * FROM rules WHERE status = 'approved' ORDER BY created_at_ms ASC")
    fun getApprovedRules(): Flow<List<Rule>>

    @Query("SELECT * FROM rules ORDER BY created_at_ms DESC")
    fun getAll(): Flow<List<Rule>>
}
