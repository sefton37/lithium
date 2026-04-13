package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ai.talkingrock.lithium.data.model.ImplicitJudgment

@Dao
interface ImplicitJudgmentDao {

    /** Single-row insert. Prefer [insertAll] for batch writes from a single capture event. */
    @Insert
    suspend fun insert(row: ImplicitJudgment)

    /**
     * Batch insert from a single capture event. Room wraps the list insert in one
     * SQLite transaction automatically.
     */
    @Insert
    suspend fun insertAll(rows: List<ImplicitJudgment>)

    /** Total row count — for diagnostics and monitoring table growth. */
    @Query("SELECT COUNT(*) FROM implicit_judgments")
    suspend fun count(): Int

    /**
     * All rows ordered chronologically. Used by ScoringRefit for full Elo replay
     * (replayImplicitElo) — processes in insertion order so early signals are not
     * over-weighted relative to recent ones.
     */
    @Query("SELECT * FROM implicit_judgments ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<ImplicitJudgment>

    /**
     * All rows ordered chronologically — alias used by ScoringRefit's replay pass.
     * Identical to [getAll]; named separately to match the plan's method name.
     */
    @Query("SELECT * FROM implicit_judgments ORDER BY created_at_ms ASC")
    suspend fun getAllChronological(): List<ImplicitJudgment>

    /**
     * Same-channel rows only: pairs where winner and loser share the same package
     * and channel. Used by ScoringRefit's category weight fit (Step 2) — same-channel
     * pairs carry content preference signal rather than channel-level preference.
     */
    @Query("""
        SELECT * FROM implicit_judgments
        WHERE winner_package = loser_package
          AND winner_channel_id = loser_channel_id
        ORDER BY created_at_ms ASC
    """)
    suspend fun getSameChannelChronological(): List<ImplicitJudgment>

    /**
     * Most-recent [limit] rows ordered newest-first.
     * Used by the dev diagnostics screen to dump recent implicit judgments.
     */
    @Query("SELECT * FROM implicit_judgments ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ImplicitJudgment>

    /**
     * Count of rows where [pkg] / [channelId] appears on either the winner or loser side.
     * Used by Scorer to compute nCombined for the AppBehaviorProfile bias fade-out (§5).
     */
    @Query("""
        SELECT COUNT(*) FROM implicit_judgments
        WHERE (winner_package = :pkg AND winner_channel_id = :channelId)
           OR (loser_package  = :pkg AND loser_channel_id  = :channelId)
    """)
    suspend fun countForChannel(pkg: String, channelId: String): Int
}
