package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.Rule
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [Rule].
 *
 * The RuleRepository maintains an in-memory StateFlow cache of approved rules for fast
 * synchronous access from the RuleEngine hot path. This DAO is the source of truth that
 * populates that cache.
 */
@Dao
interface RuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: Rule): Long

    /**
     * Updates the status of a rule. Used when the user approves or rejects a suggestion
     * on the Briefing screen, or when a proposed rule is promoted to approved.
     */
    @Query("UPDATE rules SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * Returns all approved rules, ordered by creation time (oldest first).
     * The RuleRepository collects this Flow to populate its in-memory cache.
     * Order matters: first-match-wins in the RuleEngine.
     */
    @Query("SELECT * FROM rules WHERE status = 'approved' ORDER BY created_at_ms ASC")
    fun getApprovedRules(): Flow<List<Rule>>

    /** Returns all rules regardless of status, newest first. Used by the Rules management screen. */
    @Query("SELECT * FROM rules ORDER BY created_at_ms DESC")
    fun getAll(): Flow<List<Rule>>

    @Query("SELECT * FROM rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Rule?

    /** Hard-deletes a rule by ID. */
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Returns the count of rules inserted by the seeder (source = 'seed').
     * Used by [ai.talkingrock.lithium.data.db.ShadeModeSeeder] to self-heal when the
     * SHADE_MODE_SEED_DONE flag is absent but seed rules already exist (e.g. after a
     * crash between the Room transaction and the SharedPreferences write).
     */
    @Query("SELECT COUNT(*) FROM rules WHERE source = 'seed'")
    suspend fun countSeedRules(): Int
}
