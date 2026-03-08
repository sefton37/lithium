package ai.talkingrock.lithium.ai

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.talkingrock.lithium.data.db.NotificationDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that classifies unclassified notifications from the past 24 hours.
 *
 * Scheduled as a periodic job (every 24 hours) with constraints:
 *   - device charging
 *   - battery not low
 *   - device idle
 *
 * These constraints ensure the classification pass runs quietly in the background without
 * impacting user-perceived performance or battery.
 *
 * See [ai.talkingrock.lithium.LithiumApp] and [ai.talkingrock.lithium.service.BootReceiver]
 * for scheduling and re-scheduling logic.
 *
 * Worker logic (M3 — classification only; report/suggest added in M4):
 * 1. Check if the AI model is loaded. If not, log and return success (no retry needed).
 * 2. Query up to [MAX_BATCH_SIZE] unclassified records from the DAO.
 * 3. For each, run the heuristic/ONNX classifier and write the result back to the DB.
 * 4. Log row counts (not content) in debug builds.
 */
@HiltWorker
class AiAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val classifier: NotificationClassifier
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: starting classification pass")

        // Query unclassified records from the last 24 hours (via the DAO's unclassified query).
        // The DAO orders oldest first, so we process in chronological order.
        val unclassified = try {
            notificationDao.getUnclassified(limit = MAX_BATCH_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to query unclassified notifications", e)
            return Result.failure()
        }

        if (unclassified.isEmpty()) {
            Log.d(TAG, "doWork: no unclassified notifications found — nothing to do")
            return Result.success()
        }

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

        Log.d(TAG, "doWork: complete — classified=$successCount, failed=$failureCount")
        return Result.success()
    }

    companion object {
        private const val TAG = "AiAnalysisWorker"

        /** Maximum records processed per worker run. Prevents unbounded processing time. */
        const val MAX_BATCH_SIZE = 500

        /** Unique work name used for scheduling and re-enqueuing. */
        const val WORK_NAME = "lithium_ai_analysis"
    }
}
