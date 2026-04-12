package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [NotificationRecord].
 *
 * Insert/update methods are suspend functions — called from the NotificationListenerService
 * coroutine scope. Query methods returning lists use Flow for reactive UI observation.
 */
/** Projection used by [NotificationDao.getTierBreakdown]. */
data class TierCount(val tier: Int, val count: Int)

/**
 * Pattern-coverage projection. Each row represents one notification "pattern"
 * in plain language — e.g., "Messages · sms_unknown" — with how many rows in
 * the pool share it and how many have been judged so far.
 */
data class PatternStat(
    val pattern: String,
    val packageName: String,
    val tierReason: String,
    val total: Int,
    val judged: Int
)

/** Projection for per-(package, tier_reason) aggregation. */
data class TierReasonStat(
    @androidx.room.ColumnInfo(name = "package_name") val packageName: String,
    @androidx.room.ColumnInfo(name = "tier_reason") val tierReason: String,
    val tier: Int,
    val count: Int,
    val tapped: Int
)

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: NotificationRecord): Long

    /**
     * Record the removal time and reason for a notification that was previously inserted.
     * Called from [onNotificationRemoved] in the listener service.
     */
    @Query("UPDATE notifications SET removed_at_ms = :removedAtMs, removal_reason = :reason WHERE id = :id")
    suspend fun updateRemoval(id: Long, removedAtMs: Long, reason: String)

    /**
     * Returns all notifications posted at or after [sinceMs], newest first.
     * Primary query for the debug notification log and briefing screen.
     */
    @Query("SELECT * FROM notifications WHERE posted_at_ms >= :sinceMs ORDER BY posted_at_ms DESC")
    fun getRecent(sinceMs: Long): Flow<List<NotificationRecord>>

    /**
     * Returns up to [limit] notifications that have not yet been classified by the AI worker.
     * Oldest first so the worker processes in chronological order.
     * Not a Flow — called once inside the WorkManager worker, not observed.
     */
    @Query("SELECT * FROM notifications WHERE ai_classification IS NULL ORDER BY posted_at_ms ASC LIMIT :limit")
    suspend fun getUnclassified(limit: Int): List<NotificationRecord>

    /**
     * Returns ongoing notifications that were previously classified as 'unknown'.
     * Used for one-time reclassification after the BACKGROUND category was introduced.
     */
    @Query("SELECT * FROM notifications WHERE is_ongoing = 1 AND ai_classification = 'unknown' ORDER BY posted_at_ms ASC LIMIT :limit")
    suspend fun getOngoingMisclassified(limit: Int): List<NotificationRecord>

    /**
     * Returns all notifications from a specific package, newest first.
     */
    @Query("SELECT * FROM notifications WHERE package_name = :packageName ORDER BY posted_at_ms DESC")
    fun getByPackage(packageName: String): Flow<List<NotificationRecord>>

    /**
     * Hard-deletes notifications older than [thresholdMs].
     * Called periodically to enforce the retention policy.
     */
    @Query("DELETE FROM notifications WHERE posted_at_ms < :thresholdMs")
    suspend fun deleteOlderThan(thresholdMs: Long)

    /** Returns all notifications, newest first. Used by the debug log screen. */
    @Query("SELECT * FROM notifications ORDER BY posted_at_ms DESC")
    fun getAll(): Flow<List<NotificationRecord>>

    /**
     * One-shot (non-Flow) variant of [getRecent] for use in WorkManager workers and
     * analytics passes that need a snapshot without setting up a reactive observer.
     */
    @Query("SELECT * FROM notifications WHERE posted_at_ms >= :sinceMs ORDER BY posted_at_ms DESC")
    suspend fun getAllSince(sinceMs: Long): List<NotificationRecord>

    @Query("SELECT * FROM notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NotificationRecord?

    /**
     * Writes the AI classification result for a single notification.
     * Called by [ai.talkingrock.lithium.ai.AiAnalysisWorker] after each inference pass.
     */
    @Query("UPDATE notifications SET ai_classification = :classification, ai_confidence = :confidence WHERE id = :id")
    suspend fun updateClassification(id: Long, classification: String, confidence: Float)

    /**
     * Returns distinct package names from all recorded notifications, sorted alphabetically.
     * Used by the Add Rule screen to populate the app selector.
     */
    @Query("SELECT DISTINCT package_name FROM notifications ORDER BY package_name ASC")
    suspend fun getDistinctPackageNames(): List<String>

    /** Returns approximate count of rows in the notifications table. */
    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun count(): Int

    /** Returns the count of notifications that have been classified by the AI worker. */
    @Query("SELECT COUNT(*) FROM notifications WHERE ai_classification IS NOT NULL")
    suspend fun countClassified(): Int

    /** Returns the number of distinct apps that have at least one classified notification. */
    @Query("SELECT COUNT(DISTINCT package_name) FROM notifications WHERE ai_classification IS NOT NULL")
    suspend fun countDistinctClassifiedApps(): Int

    /** Delete all notification records. Used by purge-all-data. */
    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
    /**
     * Returns notifications posted since [sinceMs] filtered to the given tiers, newest first.
     * Used by GET /api/notifications?tier=2&tier=3.
     */
    @Query("SELECT * FROM notifications WHERE posted_at_ms >= :sinceMs AND tier IN (:tiers) ORDER BY posted_at_ms DESC")
    suspend fun getAllSinceWithTiers(sinceMs: Long, tiers: List<Int>): List<NotificationRecord>

    /**
     * Returns a count per tier across all recorded notifications.
     * Used by GET /api/stats to produce a tier breakdown.
     */
    @Query("SELECT tier, COUNT(*) AS count FROM notifications GROUP BY tier ORDER BY tier ASC")
    suspend fun getTierBreakdown(): List<TierCount>

    /** Tier counts since [sinceMs]. Used by the briefing screen's 24-hour summary. */
    @Query("SELECT tier, COUNT(*) AS count FROM notifications WHERE posted_at_ms >= :sinceMs GROUP BY tier ORDER BY tier ASC")
    fun getTierBreakdownSince(sinceMs: Long): kotlinx.coroutines.flow.Flow<List<TierCount>>

    /**
     * Returns the next batch of rows with no tier_reason set.
     * Rows created before the v3→v4 migration got tier=2 and tier_reason=NULL;
     * this query finds them for retroactive classification.
     */
    @Query("SELECT * FROM notifications WHERE tier_reason IS NULL ORDER BY id ASC LIMIT :limit")
    suspend fun getTierBackfillBatch(limit: Int): List<NotificationRecord>

    /** Count of rows still needing tier backfill. */
    @Query("SELECT COUNT(*) FROM notifications WHERE tier_reason IS NULL")
    suspend fun countTierBackfillRemaining(): Int

    /** Updates tier and tier_reason for a single row. */
    @Query("UPDATE notifications SET tier = :tier, tier_reason = :reason WHERE id = :id")
    suspend fun updateTier(id: Long, tier: Int, reason: String)

    /**
     * Returns notifications that are good candidates for training-pair judgment.
     *
     * Ambiguity ranking (lower = more ambiguous, surfaced first):
     *   - AI-unclassified rows treated as maximum ambiguity (assigned 0.5 boundary)
     *   - Classified rows ranked by |confidence - 0.5| ascending
     * tier = 0 (Invisible: media, ongoing) excluded — not useful training signal.
     * Row IDs in [excludeIds] are omitted (already judged).
     *
     * The query returns up to [limit] rows; the caller pairs them with a
     * diversity policy.
     */
    @Query(
        "SELECT * FROM notifications " +
        "WHERE tier > 0 AND is_ongoing = 0 " +
        "  AND (title IS NOT NULL OR text IS NOT NULL) " +
        "  AND id NOT IN (:excludeIds) " +
        "ORDER BY " +
        "  CASE WHEN ai_classification IS NULL THEN 0.0 " +
        "       ELSE ABS(ai_confidence - 0.5) END ASC, " +
        "  RANDOM() " +
        "LIMIT :limit"
    )
    suspend fun getAmbiguousCandidates(limit: Int, excludeIds: List<Long>): List<NotificationRecord>

    /**
     * Same ordering as [getAmbiguousCandidates] but restricted to rows where
     * ai_classification IS NULL. Used by the "Label the Unknown" quest.
     */
    @Query(
        "SELECT * FROM notifications " +
        "WHERE tier > 0 AND is_ongoing = 0 AND ai_classification IS NULL " +
        "  AND (title IS NOT NULL OR text IS NOT NULL) " +
        "  AND id NOT IN (:excludeIds) " +
        "ORDER BY RANDOM() " +
        "LIMIT :limit"
    )
    suspend fun getUnclassifiedCandidates(limit: Int, excludeIds: List<Long>): List<NotificationRecord>

    /**
     * Total count of eligible training candidates in the DB.
     */
    @Query(
        "SELECT COUNT(*) FROM notifications " +
        "WHERE tier > 0 AND is_ongoing = 0 " +
        "  AND (title IS NOT NULL OR text IS NOT NULL)"
    )
    fun countAmbiguityPoolFlow(): kotlinx.coroutines.flow.Flow<Int>

    /**
     * Pattern coverage — one row per unique (package, tier_reason) combo
     * in the eligible pool, with the count of rows in the pool and the
     * count that have been judged (non-skip). Drives pattern-based
     * leveling and active-learning pair selection.
     */
    @Query(
        "SELECT (n.package_name || '|' || COALESCE(n.tier_reason, 'none')) AS pattern, " +
        "       n.package_name AS packageName, " +
        "       COALESCE(n.tier_reason, 'none') AS tierReason, " +
        "       COUNT(*) AS total, " +
        "       SUM(CASE WHEN n.id IN (" +
        "         SELECT left_notification_id FROM training_judgments WHERE choice != 'skip' " +
        "         UNION " +
        "         SELECT right_notification_id FROM training_judgments WHERE choice != 'skip'" +
        "       ) THEN 1 ELSE 0 END) AS judged " +
        "FROM notifications n " +
        "WHERE n.tier > 0 AND n.is_ongoing = 0 " +
        "  AND (n.title IS NOT NULL OR n.text IS NOT NULL) " +
        "GROUP BY pattern"
    )
    fun getPatternStatsFlow(): kotlinx.coroutines.flow.Flow<List<PatternStat>>

    /**
     * Per-(package, tier_reason) aggregation for tier ≤ [maxTier], since [sinceMs].
     * Used by SuggestionGenerator to propose suppress/queue rules from the tier
     * classifier's deterministic reason codes (marketing_text, linkedin, etc.)
     * — independent of the ML category path.
     */
    @Query(
        "SELECT package_name, tier_reason, tier, COUNT(*) AS count, " +
        "SUM(CASE WHEN removal_reason = 'click' THEN 1 ELSE 0 END) AS tapped " +
        "FROM notifications " +
        "WHERE posted_at_ms >= :sinceMs AND tier <= :maxTier AND tier_reason IS NOT NULL " +
        "GROUP BY package_name, tier_reason, tier " +
        "HAVING count >= :minCount " +
        "ORDER BY count DESC"
    )
    suspend fun getTierReasonStats(sinceMs: Long, maxTier: Int, minCount: Int): List<TierReasonStat>
}
