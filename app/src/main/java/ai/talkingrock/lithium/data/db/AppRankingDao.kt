package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.AppBattleJudgment
import ai.talkingrock.lithium.data.model.AppRanking
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRankingDao {

    /** Upsert a ranking row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ranking: AppRanking)

    @Query("SELECT * FROM app_rankings WHERE package_name = :pkg")
    suspend fun get(pkg: String): AppRanking?

    @Query("SELECT * FROM app_rankings ORDER BY elo_score DESC")
    fun getAllFlow(): Flow<List<AppRanking>>

    @Query("SELECT * FROM app_rankings ORDER BY elo_score DESC")
    suspend fun getAll(): List<AppRanking>

    @Query("SELECT COUNT(*) FROM app_rankings")
    suspend fun count(): Int

    /**
     * Distinct package names that have at least one eligible notification
     * (tier > 0, not ongoing, has body). This is the candidate pool for
     * app battles — we never rank apps the user hasn't actually received.
     */
    @Query(
        "SELECT DISTINCT package_name FROM notifications " +
        "WHERE tier > 0 AND is_ongoing = 0 " +
        "  AND (title IS NOT NULL OR text IS NOT NULL)"
    )
    suspend fun getEligiblePackages(): List<String>
}

@Dao
interface AppBattleJudgmentDao {

    @Insert
    suspend fun insert(judgment: AppBattleJudgment): Long

    @Query("SELECT COUNT(*) FROM app_battle_judgments")
    fun countFlow(): Flow<Int>

    @Query("SELECT COALESCE(SUM(xp_awarded), 0) FROM app_battle_judgments WHERE choice != 'skip'")
    fun totalXpFlow(): Flow<Int>

    /**
     * All app-battle judgments ordered chronologically.
     * Used by ScoringRefit for full Elo replay from scratch.
     */
    @Query("SELECT * FROM app_battle_judgments ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<AppBattleJudgment>
}
