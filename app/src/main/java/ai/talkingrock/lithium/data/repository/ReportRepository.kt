package ai.talkingrock.lithium.data.repository

import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [Report] and [Suggestion].
 *
 * Wraps both [ReportDao] and [SuggestionDao] to provide the [BriefingViewModel] with a
 * unified access point. No business logic here — pure data access.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val reportDao: ReportDao,
    private val suggestionDao: SuggestionDao
) {

    /** Insert a new AI-generated report. Returns the generated row ID. */
    suspend fun insertReport(report: Report): Long = withContext(Dispatchers.IO) {
        reportDao.insertReport(report)
    }

    /** Reactive stream of the most recent unreviewed report, or null if all are reviewed. */
    fun getLatestUnreviewed(): Flow<Report?> = reportDao.getLatestUnreviewed()

    /** Mark a report as reviewed after the user has actioned all its suggestions. */
    suspend fun markReviewed(id: Long) = withContext(Dispatchers.IO) {
        reportDao.markReviewed(id)
    }

    /** Insert a batch of AI-generated suggestions for a report. */
    suspend fun insertSuggestions(suggestions: List<Suggestion>) = withContext(Dispatchers.IO) {
        suggestionDao.insertSuggestions(suggestions)
    }

    /** Reactive stream of pending suggestions for a given report. */
    fun getPendingForReport(reportId: Long): Flow<List<Suggestion>> =
        suggestionDao.getPendingForReport(reportId)

    /** Update the status of a suggestion after user action (accept/reject). */
    suspend fun updateSuggestionStatus(id: Long, status: String, comment: String?) =
        withContext(Dispatchers.IO) {
            suggestionDao.updateStatus(id, status, comment)
        }

    /**
     * Returns the number of pending (unreviewed) suggestions for a report.
     * When this reaches zero after the user actions the last suggestion, the report
     * should be marked reviewed.
     */
    suspend fun countPendingSuggestions(reportId: Long): Int = withContext(Dispatchers.IO) {
        suggestionDao.countPendingForReport(reportId)
    }
}
