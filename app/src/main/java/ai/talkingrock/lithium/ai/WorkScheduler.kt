package ai.talkingrock.lithium.ai

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Centralises WorkManager scheduling for Lithium's background workers.
 *
 * Both [ai.talkingrock.lithium.LithiumApp] (on startup) and
 * [ai.talkingrock.lithium.service.BootReceiver] (on reboot) call into this object to
 * ensure the AI analysis job is always scheduled without duplicating the constraint setup.
 */
object WorkScheduler {

    private const val TAG = "WorkScheduler"

    /**
     * Enqueues or verifies the [AiAnalysisWorker] periodic request.
     *
     * Constraints:
     * - Charging: avoids battery drain during inference
     * - Battery not low: belt-and-suspenders battery safety
     * - Device idle: inference does not compete with foreground work
     *
     * Policy: [ExistingPeriodicWorkPolicy.KEEP] — the existing enqueue is left unchanged
     * if the work is already scheduled. The 24-hour interval timer is not reset on app launch.
     *
     * @param workManager Caller-provided [WorkManager] instance (avoids re-initialising it).
     */
    fun scheduleAiAnalysis(workManager: WorkManager) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<AiAnalysisWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AiAnalysisWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "scheduleAiAnalysis: enqueued (KEEP policy)")
    }
}
