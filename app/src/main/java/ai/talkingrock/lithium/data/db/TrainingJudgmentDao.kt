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

    /**
     * Returns the total count of training judgments (all choices).
     * Used by ScoringRefit for debounce: if delta from last refit < threshold, skip.
     */
    @Query("SELECT COUNT(*) FROM training_judgments")
    suspend fun count(): Int

    /**
     * All training judgments joined with both left and right notification rows,
     * ordered chronologically. Used by ScoringRefit for full Elo replay and
     * category-weight logistic regression.
     *
     * Returns one row per judgment with package/channel for both sides.
     * Judgments where either notification no longer exists (deleted by retention)
     * are excluded via INNER JOIN — orphaned judgments cannot be replayed.
     *
     * Note: left_channel_id and right_channel_id are snapshot columns to be added
     * in a later schema migration. Until then they are returned as NULL, which causes
     * ScoringRefit to skip those rows for channel-pair Elo (correct default behaviour).
     */
    @Query(
        "SELECT tj.id, tj.choice, tj.created_at_ms, " +
        "  tj.left_notification_id, tj.right_notification_id, " +
        "  NULL AS left_channel_id, NULL AS right_channel_id, " +
        "  tj.left_ai_classification, tj.right_ai_classification, " +
        "  tj.left_confidence, tj.right_confidence, " +
        "  ln.package_name AS left_pkg, ln.channel_id AS left_chan, " +
        "  rn.package_name AS right_pkg, rn.channel_id AS right_chan " +
        "FROM training_judgments tj " +
        "JOIN notifications ln ON tj.left_notification_id = ln.id " +
        "JOIN notifications rn ON tj.right_notification_id = rn.id " +
        "ORDER BY tj.created_at_ms ASC"
    )
    suspend fun getAllWithNotifications(): List<JudgmentWithNotifications>

    /**
     * Number of judgments where either side belongs to [pkg] + [channelId].
     * Used by Scorer to compute shrinkage weight for the content-model contribution.
     */
    @Query(
        "SELECT COUNT(*) FROM training_judgments tj " +
        "JOIN notifications ln ON tj.left_notification_id = ln.id " +
        "JOIN notifications rn ON tj.right_notification_id = rn.id " +
        "WHERE (ln.package_name = :pkg AND ln.channel_id = :channelId) " +
        "   OR (rn.package_name = :pkg AND rn.channel_id = :channelId)"
    )
    suspend fun countByChannel(pkg: String, channelId: String): Int

    /**
     * Number of same-channel judgments: both sides share [pkg] + [channelId].
     * Used to compute beta shrinkage weight for the content-model contribution.
     */
    @Query(
        "SELECT COUNT(*) FROM training_judgments tj " +
        "JOIN notifications ln ON tj.left_notification_id = ln.id " +
        "JOIN notifications rn ON tj.right_notification_id = rn.id " +
        "WHERE ln.package_name = :pkg AND ln.channel_id = :channelId " +
        "  AND rn.package_name = :pkg AND rn.channel_id = :channelId"
    )
    suspend fun countSameChannelPairs(pkg: String, channelId: String): Int

}

/** Projection for xpByQuestFlow. */
data class QuestXp(
    @androidx.room.ColumnInfo(name = "quest_id") val questId: String,
    val xp: Int
)

data class ChoiceCount(val choice: String, val count: Int)

/**
 * Projection returned by [TrainingJudgmentDao.getAllWithNotifications].
 *
 * Flattens a training_judgment row with the package/channel of both sides,
 * plus the classification snapshot stored at judgment time.
 * Used by ScoringRefit for Elo replay and category-weight logistic regression.
 *
 * [leftChannelId] / [rightChannelId] are snapshot columns from training_judgments
 * (added in a future schema migration). Until then they return NULL, causing
 * channel-pair Elo rows to be skipped -- a safe default.
 */
data class JudgmentWithNotifications(
    @androidx.room.ColumnInfo(name = "id") val id: Long,
    @androidx.room.ColumnInfo(name = "choice") val choice: String,
    @androidx.room.ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @androidx.room.ColumnInfo(name = "left_notification_id") val leftNotificationId: Long,
    @androidx.room.ColumnInfo(name = "right_notification_id") val rightNotificationId: Long,
    @androidx.room.ColumnInfo(name = "left_channel_id") val leftChannelId: String?,
    @androidx.room.ColumnInfo(name = "right_channel_id") val rightChannelId: String?,
    @androidx.room.ColumnInfo(name = "left_ai_classification") val leftAiClassification: String?,
    @androidx.room.ColumnInfo(name = "right_ai_classification") val rightAiClassification: String?,
    @androidx.room.ColumnInfo(name = "left_confidence") val leftConfidence: Float?,
    @androidx.room.ColumnInfo(name = "right_confidence") val rightConfidence: Float?,
    @androidx.room.ColumnInfo(name = "left_pkg") val leftPkg: String,
    @androidx.room.ColumnInfo(name = "left_chan") val leftChan: String?,
    @androidx.room.ColumnInfo(name = "right_pkg") val rightPkg: String,
    @androidx.room.ColumnInfo(name = "right_chan") val rightChan: String?,
)