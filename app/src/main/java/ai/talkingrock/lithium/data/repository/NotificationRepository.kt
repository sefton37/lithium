package ai.talkingrock.lithium.data.repository

import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [NotificationRecord].
 *
 * Suspend functions use [Dispatchers.IO] explicitly. Flow queries are passed through directly
 * from the DAO — Room already runs them on its query executor.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val dao: NotificationDao
) {

    /** Insert a new notification record. Returns the generated row ID. */
    suspend fun insert(record: NotificationRecord): Long = withContext(Dispatchers.IO) {
        dao.insertOrReplace(record)
    }

    /** Record removal time and reason after [onNotificationRemoved]. */
    suspend fun updateRemoval(id: Long, removedAtMs: Long, reason: String) =
        withContext(Dispatchers.IO) {
            dao.updateRemoval(id, removedAtMs, reason)
        }

    /** Reactive stream of notifications posted since [sinceMs], newest first. */
    fun getRecent(sinceMs: Long): Flow<List<NotificationRecord>> = dao.getRecent(sinceMs)

    /** One-shot query for unclassified records, used by the AI worker. */
    suspend fun getUnclassified(limit: Int): List<NotificationRecord> =
        withContext(Dispatchers.IO) {
            dao.getUnclassified(limit)
        }

    /** Reactive stream of notifications from a specific package. */
    fun getByPackage(packageName: String): Flow<List<NotificationRecord>> =
        dao.getByPackage(packageName)

    /** Hard-delete notifications older than [thresholdMs]. */
    suspend fun deleteOlderThan(thresholdMs: Long) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(thresholdMs)
    }

    /** Reactive stream of all notifications, newest first. Used by the debug log screen. */
    fun getAll(): Flow<List<NotificationRecord>> = dao.getAll()

    /**
     * Returns distinct package names from all observed notifications, sorted alphabetically.
     * Used by the Add Rule screen to populate the app selector.
     */
    suspend fun getDistinctPackageNames(): List<String> = withContext(Dispatchers.IO) {
        dao.getDistinctPackageNames()
    }
}
