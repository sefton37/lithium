package ai.talkingrock.lithium.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that owns the Ktor API server lifecycle.
 *
 * Android kills background services aggressively. Running as a foreground
 * service with a persistent notification keeps the process alive as long as
 * the user has the notification visible.
 *
 * Lifecycle:
 * - [onStartCommand]: starts the Ktor server (idempotent) and promotes to foreground.
 * - [onDestroy]: stops the Ktor server and releases its CIO dispatcher.
 *
 * Started from [ai.talkingrock.lithium.LithiumApp.onCreate] on every app launch.
 * Uses START_STICKY so Android restarts it if the process is killed.
 */
@AndroidEntryPoint
class LithiumApiService : Service() {

    @Inject
    lateinit var apiServer: LithiumApiServer

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        apiServer.start()
        Log.i(TAG, "LithiumApiService started")
        return START_STICKY
    }

    override fun onDestroy() {
        apiServer.stop()
        Log.i(TAG, "LithiumApiService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): android.app.Notification {
        val channelId = "lithium_api"
        val nm = getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Lithium API",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Lithium API server status"
                    setShowBadge(false)
                }
            )
        }

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Lithium API active")
            .setContentText("Listening on :${LithiumApiServer.PORT}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LithiumApiService"
        private const val NOTIFICATION_ID = 1002
    }
}
