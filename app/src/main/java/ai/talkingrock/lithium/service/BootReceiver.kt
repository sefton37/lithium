package ai.talkingrock.lithium.service

import ai.talkingrock.lithium.ai.WorkScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager

/**
 * Receives [Intent.ACTION_BOOT_COMPLETED] after device reboot.
 *
 * The [LithiumNotificationListener] reconnects automatically after boot because Android
 * rebinds all services with BIND_NOTIFICATION_LISTENER_SERVICE granted.
 *
 * M3: Re-enqueues the WorkManager AI analysis job here. WorkManager periodic work does not
 * reliably survive reboot on all devices despite platform documentation claiming otherwise.
 * Re-enqueuing with [androidx.work.ExistingPeriodicWorkPolicy.KEEP] is idempotent — if the
 * job survived the reboot, the existing schedule is preserved without resetting the timer.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — listener will reconnect automatically; re-enqueuing workers")
        val workManager = WorkManager.getInstance(context)
        WorkScheduler.scheduleAiAnalysis(workManager)
        WorkScheduler.scheduleHealthCheck(workManager)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
