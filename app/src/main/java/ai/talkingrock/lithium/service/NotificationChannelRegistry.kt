package ai.talkingrock.lithium.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all Lithium-owned [NotificationChannel]s.
 *
 * Call [registerAll] once from [LithiumApp.onCreate]. All channel creation is
 * idempotent — Android ignores createNotificationChannel calls for channels that
 * already exist (as long as the importance and name are unchanged).
 *
 * Channels:
 *   - lithium_system   : Service health and permission status. LOW importance.
 *   - lithium_readiness: Data-readiness alert. DEFAULT importance.
 *   - lithium_curated  : Curated notification summaries (v2). DEFAULT importance.
 *   - lithium_briefing : Briefing delivery (v2). DEFAULT importance.
 */
@Singleton
class NotificationChannelRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Registers all Lithium notification channels. Safe to call multiple times.
     */
    fun registerAll() {
        val nm = context.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYSTEM,
                "Lithium System",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service health and permission status"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_READINESS,
                "Lithium Ready",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when Lithium has learned enough to make recommendations"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CURATED,
                "Curated by Lithium",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications resurfaced by Lithium with original title and content"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BRIEFING,
                "Lithium Briefing",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Your periodic notification briefing from Lithium"
            }
        )
    }

    companion object {
        const val CHANNEL_SYSTEM = "lithium_system"
        const val CHANNEL_READINESS = "lithium_readiness"
        const val CHANNEL_CURATED = "lithium_curated"
        const val CHANNEL_BRIEFING = "lithium_briefing"
    }
}
