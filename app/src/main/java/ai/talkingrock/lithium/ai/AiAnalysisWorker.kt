package ai.talkingrock.lithium.ai

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.repository.ReportRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that classifies unclassified notifications then produces the daily briefing.
 *
 * Scheduled as a periodic job (every 24 hours) with constraints:
 *   - device charging
 *   - battery not low
 *   - device idle
 *
 * These constraints ensure the analysis pass runs quietly in the background without
 * impacting user-perceived performance or battery.
 *
 * See [ai.talkingrock.lithium.LithiumApp] and [ai.talkingrock.lithium.service.BootReceiver]
 * for scheduling and re-scheduling logic.
 *
 * Worker logic (M4):
 * 1. Classify up to [MAX_BATCH_SIZE] unclassified records (same as M3).
 * 2. Run [PatternAnalyzer] to aggregate stats over the past 24 hours.
 * 3. Run [ReportGenerator] to produce a plain-language report and insert it via [ReportRepository].
 * 4. Run [SuggestionGenerator] to produce rule suggestions and insert them linked to the new report.
 */
@HiltWorker
class AiAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val classifier: NotificationClassifier,
    private val patternAnalyzer: PatternAnalyzer,
    private val reportGenerator: ReportGenerator,
    private val suggestionGenerator: SuggestionGenerator,
    private val reportRepository: ReportRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: starting analysis pass")

        // ---------------------------------------------------------------------------------
        // Step 1: Classify unclassified notifications
        // ---------------------------------------------------------------------------------
        val unclassified = try {
            notificationDao.getUnclassified(limit = MAX_BATCH_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to query unclassified notifications", e)
            return Result.failure()
        }

        if (unclassified.isNotEmpty()) {
            Log.d(TAG, "doWork: classifying ${unclassified.size} record(s)")
            var successCount = 0
            var failureCount = 0

            for (record in unclassified) {
                try {
                    val result = classifier.classify(record)
                    notificationDao.updateClassification(
                        id = record.id,
                        classification = result.label,
                        confidence = result.confidence
                    )
                    successCount++
                } catch (e: Exception) {
                    Log.e(TAG, "doWork: classification failed for id=${record.id}", e)
                    failureCount++
                }
            }
            Log.d(TAG, "doWork: classification complete — classified=$successCount, failed=$failureCount")
        } else {
            Log.d(TAG, "doWork: no unclassified notifications")
        }

        // ---------------------------------------------------------------------------------
        // Step 2: Aggregate patterns for the past 24 hours
        // ---------------------------------------------------------------------------------
        val since = System.currentTimeMillis() - ANALYSIS_WINDOW_MS
        Log.d(TAG, "doWork: aggregating patterns since=$since")

        val byCategory = try {
            patternAnalyzer.getNotificationsByCategory(since)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: pattern analysis failed", e)
            return Result.failure()
        }

        val appStats = patternAnalyzer.getAppStats(since)
        val contactVsAlgo = patternAnalyzer.getContactVsAlgorithmicRatio(since)

        val totalInWindow = byCategory.values.sumOf { it.size }
        Log.d(TAG, "doWork: found $totalInWindow notifications in window")

        // ---------------------------------------------------------------------------------
        // Step 3: Generate and persist the report
        // ---------------------------------------------------------------------------------
        val report = reportGenerator.generate(
            byCategory = byCategory,
            appStats = appStats,
            contactVsAlgo = contactVsAlgo
        )
        val reportId = try {
            reportRepository.insertReport(report)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to insert report", e)
            return Result.failure()
        }
        Log.d(TAG, "doWork: inserted report id=$reportId")

        // ---------------------------------------------------------------------------------
        // Step 4: Generate and persist suggestions linked to the new report
        // ---------------------------------------------------------------------------------
        val allNotifications = byCategory.values.flatten()
        val rawSuggestions = suggestionGenerator.generate(
            byCategory = byCategory,
            appStats = appStats,
            notifications = allNotifications
        )
        // Attach the report ID to each suggestion before insertion.
        val linkedSuggestions = rawSuggestions.map { it.copy(reportId = reportId) }

        if (linkedSuggestions.isNotEmpty()) {
            try {
                reportRepository.insertSuggestions(linkedSuggestions)
            } catch (e: Exception) {
                Log.e(TAG, "doWork: failed to insert suggestions", e)
                // Non-fatal: the report already exists; suggestions can be retried on next run.
            }
            Log.d(TAG, "doWork: inserted ${linkedSuggestions.size} suggestion(s)")
        } else {
            Log.d(TAG, "doWork: no suggestions generated")
        }

        Log.d(TAG, "doWork: analysis pass complete")
        return Result.success()
    }

    companion object {
        private const val TAG = "AiAnalysisWorker"

        /** Maximum records classified per worker run. Prevents unbounded processing time. */
        const val MAX_BATCH_SIZE = 500

        /** Unique work name used for scheduling and re-enqueuing. */
        const val WORK_NAME = "lithium_ai_analysis"

        /** Analysis window: 24 hours in milliseconds. */
        const val ANALYSIS_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
