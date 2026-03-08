package ai.talkingrock.lithium.data.repository

import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [SessionRecord].
 *
 * Written by [UsageTracker] (M2) when a notification tap leads to a measurable app session.
 * Read by PatternAnalyzer (M4) to include session duration in 24h reports.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: SessionDao
) {

    /** Insert a session record. Returns the generated row ID. */
    suspend fun insert(session: SessionRecord): Long = withContext(Dispatchers.IO) {
        dao.insert(session)
    }

    /** Returns all sessions that started at or after [sinceMs], newest first. */
    suspend fun getSessionsSince(sinceMs: Long): List<SessionRecord> =
        withContext(Dispatchers.IO) {
            dao.getSessionsSince(sinceMs)
        }

    /** Reactive stream of the most recent session record. */
    fun getLatest(): Flow<SessionRecord?> = dao.getLatest()
}
