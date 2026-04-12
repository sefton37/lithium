package ai.talkingrock.lithium.ai

import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ai.talkingrock.lithium.classification.TierBackfillWorker
import ai.talkingrock.lithium.data.Prefs
import java.util.concurrent.TimeUnit

/**
 * Centralises WorkManager scheduling for Lithium's background workers.
 *
 * Both [ai.talkingrock.lithium.LithiumApp] (on startup) and
 * [ai.talkingrock.lithium.service.BootReceiver] (on reboot) call into this object to
 * ensure the AI analysis job is always scheduled without duplicating the constraint setup.
 *
 * Constraints are user-configurable via Settings. Defaults:
 * - Charging: true (wall charger recommended)
 * - Battery not low: true
 * - Device idle: false (relaxed — original requirement made the worker nearly unreachable)
 */
object WorkScheduler {

    private const val TAG = "WorkScheduler"

    /** One-shot work name for manual "Run Now" triggers. */
    const val MANUAL_WORK_NAME = "lithium_ai_analysis_manual"

    /**
     * Enqueues or verifies the [AiAnalysisWorker] periodic request.
     *
     * Reads constraint preferences from [prefs]. If [prefs] is null, uses defaults.
     *
     * Policy: [ExistingPeriodicWorkPolicy.UPDATE] — updates constraints from prefs on each
     * app launch without resetting the 24-hour interval timer.
     */
    fun scheduleAiAnalysis(workManager: WorkManager, prefs: SharedPreferences? = null) {
        val constraints = buildConstraints(prefs)

        val workRequest = PeriodicWorkRequestBuilder<AiAnalysisWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AiAnalysisWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d(TAG, "scheduleAiAnalysis: enqueued (UPDATE policy)")
    }

    /**
     * Re-enqueues the periodic worker with updated constraints.
     *
     * Uses [ExistingPeriodicWorkPolicy.UPDATE] to replace constraints on the existing
     * work without resetting the period timer.
     */
    fun rescheduleWithNewConstraints(workManager: WorkManager, prefs: SharedPreferences) {
        val constraints = buildConstraints(prefs)

        val workRequest = PeriodicWorkRequestBuilder<AiAnalysisWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AiAnalysisWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d(TAG, "rescheduleWithNewConstraints: updated constraints")
    }

    /**
     * Immediately enqueues a one-shot [AiAnalysisWorker] with no constraints.
     *
     * Uses [ExistingWorkPolicy.REPLACE] so tapping "Run Now" multiple times
     * doesn't queue duplicates.
     */
    fun runNow(workManager: WorkManager) {
        val workRequest = OneTimeWorkRequestBuilder<AiAnalysisWorker>()
            .build()

        workManager.enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "runNow: enqueued immediate one-shot analysis")
    }

    /**
     * Enqueues or verifies the [HealthCheckWorker] periodic request.
     *
     * Runs every 6 hours with no constraints — the check must fire even when the
     * device is not charging, battery is low, or the device is not idle.
     *
     * Policy: [ExistingPeriodicWorkPolicy.KEEP] — if the job already exists, its
     * schedule is preserved unchanged. There are no user-configurable constraints
     * to update, so UPDATE would only reset the period timer needlessly.
     */
    fun scheduleHealthCheck(workManager: WorkManager) {
        val workRequest = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            repeatInterval = 6,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        ).build()

        workManager.enqueueUniquePeriodicWork(
            HealthCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "scheduleHealthCheck: enqueued (KEEP policy, 6h interval, no constraints)")
    }

    /**
     * Enqueues a one-shot [TierBackfillWorker] to retroactively classify rows
     * that predate the v3→v4 migration (tier_reason IS NULL).
     *
     * Policy: [ExistingWorkPolicy.KEEP] — idempotent on app restart; if the
     * job is already queued or running, leave it alone. The worker itself is
     * resumable (queries `tier_reason IS NULL` each batch) so duplicate
     * triggers are harmless but wasteful.
     *
     * No constraints — the work is pure CPU against local SQLCipher and
     * should run to completion on first launch regardless of power state.
     */
    fun scheduleTierBackfill(workManager: WorkManager) {
        val workRequest = OneTimeWorkRequestBuilder<TierBackfillWorker>().build()
        workManager.enqueueUniqueWork(
            TierBackfillWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "scheduleTierBackfill: enqueued (KEEP policy, one-shot, no constraints)")
    }

    private fun buildConstraints(prefs: SharedPreferences?): Constraints {
        val requireCharging = prefs?.getBoolean(
            Prefs.PREF_REQUIRE_CHARGING, Prefs.DEFAULT_REQUIRE_CHARGING
        ) ?: Prefs.DEFAULT_REQUIRE_CHARGING

        val requireBatteryNotLow = prefs?.getBoolean(
            Prefs.PREF_REQUIRE_BATTERY_NOT_LOW, Prefs.DEFAULT_REQUIRE_BATTERY_NOT_LOW
        ) ?: Prefs.DEFAULT_REQUIRE_BATTERY_NOT_LOW

        val requireIdle = prefs?.getBoolean(
            Prefs.PREF_REQUIRE_IDLE, Prefs.DEFAULT_REQUIRE_IDLE
        ) ?: Prefs.DEFAULT_REQUIRE_IDLE

        Log.d(TAG, "buildConstraints: charging=$requireCharging, batteryNotLow=$requireBatteryNotLow, idle=$requireIdle")

        return Constraints.Builder()
            .setRequiresCharging(requireCharging)
            .setRequiresBatteryNotLow(requireBatteryNotLow)
            .setRequiresDeviceIdle(requireIdle)
            .build()
    }
}
