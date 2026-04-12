package ai.talkingrock.lithium.classification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.talkingrock.lithium.data.db.NotificationDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Retroactively applies [TierClassifier] to notifications that predate the
 * v3→v4 migration. Those rows carry tier=2 and tier_reason=NULL by default.
 *
 * The worker pulls them in batches of [BATCH_SIZE], re-derives (tier,
 * tier_reason) from the persisted fields, and writes the result back.
 * It self-retries until no unclassified rows remain or the batch is empty.
 *
 * Designed to run once as a OneTimeWorkRequest. If cancelled or killed, the
 * next invocation picks up exactly where it left off because the filter is
 * `tier_reason IS NULL` — classified rows are never re-processed.
 *
 * Note: `isFromContact` is persisted on each row from capture time, so the
 * backfill respects the contact whitelist just like live classification.
 */
@HiltWorker
class TierBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: NotificationDao,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val startRemaining = dao.countTierBackfillRemaining()
        Log.i(TAG, "doWork: starting tier backfill, $startRemaining rows remaining")

        if (startRemaining == 0) {
            Log.i(TAG, "doWork: nothing to do")
            return Result.success()
        }

        var totalProcessed = 0
        while (true) {
            val batch = dao.getTierBackfillBatch(BATCH_SIZE)
            if (batch.isEmpty()) break

            for (row in batch) {
                val (tier, reason) = TierClassifier.classify(
                    packageName = row.packageName,
                    title = row.title,
                    text = row.text,
                    isOngoing = row.isOngoing,
                    category = row.category,
                    isFromContact = row.isFromContact,
                )
                dao.updateTier(row.id, tier, reason)
            }
            totalProcessed += batch.size

            if (totalProcessed % PROGRESS_LOG_INTERVAL == 0) {
                Log.i(TAG, "doWork: processed $totalProcessed / $startRemaining")
            }
        }

        Log.i(TAG, "doWork: backfill complete, $totalProcessed rows updated")
        return Result.success()
    }

    companion object {
        private const val TAG = "TierBackfillWorker"

        /** Batch size — large enough to amortize SQLCipher overhead, small enough to checkpoint often. */
        private const val BATCH_SIZE = 500

        /** How often to log progress (every N rows). */
        private const val PROGRESS_LOG_INTERVAL = 5_000

        /** Unique work name for scheduling and de-duping. */
        const val WORK_NAME = "lithium_tier_backfill"
    }
}
