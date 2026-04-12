package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ai.talkingrock.lithium.data.model.TrainingJudgment
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingJudgmentDao {

    @Insert
    suspend fun insert(judgment: TrainingJudgment): Long

    @Query("SELECT COUNT(*) FROM training_judgments")
    fun countFlow(): Flow<Int>

    /**
     * Returns notification IDs that have already appeared on either side of a
     * judgment (including 'skip'). Used to avoid re-surfacing the same row.
     */
    @Query(
        "SELECT left_notification_id FROM training_judgments " +
        "UNION " +
        "SELECT right_notification_id FROM training_judgments"
    )
    suspend fun getJudgedNotificationIds(): List<Long>

    /** Distribution of choice values — used for future dashboards. */
    @Query("SELECT choice, COUNT(*) AS count FROM training_judgments GROUP BY choice")
    suspend fun getChoiceBreakdown(): List<ChoiceCount>

    /**
     * Count of non-skip judgments since [sinceMs]. Used for the daily-goal counter.
     * Skips are excluded — they indicate "couldn't judge," not real training signal.
     */
    @Query("SELECT COUNT(*) FROM training_judgments WHERE created_at_ms >= :sinceMs AND choice != 'skip'")
    fun countSinceFlow(sinceMs: Long): Flow<Int>

    /** Cumulative XP (per-judgment XP + set bonuses) — all non-skip rows. */
    @Query("SELECT COALESCE(SUM(xp_awarded + set_bonus_xp), 0) FROM training_judgments WHERE choice != 'skip'")
    fun totalXpFlow(): Flow<Int>

    /** XP per quest_id (non-skip rows). Used by quest chips + report screen. */
    @Query(
        "SELECT quest_id, COALESCE(SUM(xp_awarded + set_bonus_xp), 0) AS xp " +
        "FROM training_judgments WHERE choice != 'skip' GROUP BY quest_id"
    )
    fun xpByQuestFlow(): Flow<List<QuestXp>>
}

/** Projection for xpByQuestFlow. */
data class QuestXp(
    @androidx.room.ColumnInfo(name = "quest_id") val questId: String,
    val xp: Int
)

data class ChoiceCount(val choice: String, val count: Int)
