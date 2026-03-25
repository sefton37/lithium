package ai.talkingrock.lithium.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ai.talkingrock.lithium.MainActivity

/**
 * Sends a one-time local notification when Lithium has collected enough data
 * to produce meaningful recommendations.
 *
 * Called from [ai.talkingrock.lithium.ai.AiAnalysisWorker] after the classification
 * step crosses the readiness threshold. The worker guards against repeat calls
 * via a SharedPreferences flag — this object is stateless.
 */
object DataReadinessNotifier {

    private const val CHANNEL_ID = "lithium_readiness"
    private const val NOTIFICATION_ID = 2001

    /**
     * Posts a notification telling the user that Lithium is ready.
     *
     * Idempotent per notification ID — calling multiple times just replaces
     * the same notification.
     */
    fun notify(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Create channel if needed (safe to call repeatedly)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Lithium Ready",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifies you when Lithium has learned enough to make recommendations"
                }
            )
        }

        // Tap opens the app to the Briefing screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lithium is ready")
            .setContentText("Your first briefing is waiting — tap to see what Lithium learned.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
