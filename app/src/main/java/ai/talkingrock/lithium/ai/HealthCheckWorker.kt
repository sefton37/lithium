package ai.talkingrock.lithium.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.talkingrock.lithium.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that verifies the NotificationListenerService is still enabled.
 *
 * Scheduled as a periodic job every 6 hours with no constraints so it runs regardless
 * of battery state, charging status, or device idle state. The check must be reliable
 * because a disconnected listener means Lithium is silently blind.
 *
 * Behavior:
 * - If the listener package is found in the enabled set: logs and returns success quietly.
 * - If the listener package is missing: posts a HIGH priority "Lithium was disconnected"
 *   notification on channel "lithium_health" with a PendingIntent to open MainActivity,
 *   which routes to the Setup screen when notification access is absent.
 */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: checking NotificationListenerService status")

        val enabledPackages = NotificationManagerCompat
            .getEnabledListenerPackages(applicationContext)

        return if (enabledPackages.contains(applicationContext.packageName)) {
            Log.d(TAG, "doWork: listener is connected — no action needed")
            Result.success()
        } else {
            Log.w(TAG, "doWork: listener is NOT in enabled set — posting health alert")
            postDisconnectedNotification()
            Result.success()
        }
    }

    private fun postDisconnectedNotification() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)

        // Create the channel if it doesn't exist yet (safe to call repeatedly)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Lithium Health",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when Lithium's notification monitoring has stopped"
                }
            )
        }

        // Tapping the notification opens MainActivity, which routes to Setup
        // when notification access is absent (see MainActivity.onCreate routing logic).
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Lithium was disconnected")
            .setContentText("Notification monitoring has stopped. Tap to restore access.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "postDisconnectedNotification: alert posted")
    }

    companion object {
        private const val TAG = "HealthCheckWorker"

        /** Channel ID for health-check alerts. */
        private const val CHANNEL_ID = "lithium_health"

        /** Notification ID for the disconnection alert. Stable so repeat fires replace it. */
        private const val NOTIFICATION_ID = 3001

        /** PendingIntent request code — must be unique within the app. */
        private const val REQUEST_CODE = 3001

        /** Unique work name used for scheduling and re-enqueuing. */
        const val WORK_NAME = "lithium_health_check"
    }
}
