package ai.talkingrock.lithium.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives [Intent.ACTION_BOOT_COMPLETED] after device reboot.
 *
 * M1: No-op placeholder. The [NotificationListenerService] reconnects automatically after
 * boot because Android rebinds all services with BIND_NOTIFICATION_LISTENER_SERVICE granted.
 *
 * M3: Will re-enqueue the WorkManager AI analysis job here, because WorkManager periodic
 * work does not reliably survive reboot on all devices despite the platform documentation
 * claiming otherwise.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — listener will reconnect automatically")
        // M3: re-enqueue WorkManager AI job here.
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
