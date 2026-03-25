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
}
