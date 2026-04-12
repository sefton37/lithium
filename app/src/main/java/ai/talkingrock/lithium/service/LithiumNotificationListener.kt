package ai.talkingrock.lithium.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.data.repository.SessionRepository
import ai.talkingrock.lithium.classification.TierClassifier
import ai.talkingrock.lithium.engine.ContactsResolver
import ai.talkingrock.lithium.engine.RuleAction
import ai.talkingrock.lithium.engine.RuleEngine
import ai.talkingrock.lithium.engine.UsageTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The heart of Lithium — intercepts every notification posted on the device.
 *
 * Lifecycle:
 * - Android binds this service when the user grants Notification Access.
 * - The system kills and restarts it on its own schedule. [RuleRepository]'s in-memory
 *   cache rebuilds automatically from the database on each restart.
 * - [onListenerConnected] and [onListenerDisconnected] update [ListenerState] so the
 *   Setup screen reflects accurate status without polling.
 *
 * Threading contract:
 * - [onNotificationPosted] and [onNotificationRemoved] are called on the main thread.
 *   They must return quickly. Rule evaluation is synchronous and in-memory.
 *   Database writes go to [serviceScope] (IO dispatcher) — never block the callback.
 *
 * OEM battery kill mitigation:
 * - [onListenerDisconnected] posts a low-priority notification nudging the user to add
 *   Lithium to the "do not optimize" list. This is the standard pattern for notification
 *   listeners and alarm apps on aggressive OEM configurations (MIUI, Samsung).
 */
@AndroidEntryPoint
class LithiumNotificationListener : NotificationListenerService() {

    @Inject lateinit var notificationRepo: NotificationRepository
    @Inject lateinit var sessionRepo: SessionRepository
    @Inject lateinit var ruleEngine: RuleEngine
    @Inject lateinit var listenerState: ListenerState
    @Inject lateinit var contactsResolver: ContactsResolver
    @Inject lateinit var usageTracker: UsageTracker

    private lateinit var serviceScope: CoroutineScope

    // In-memory map of notification key -> database row ID, used to update removal records.
    // Keyed by the SBN key (package:id:tag) which is stable within a process lifetime.
    private val keyToRowId = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerState.onConnected()
        Log.d(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerState.onDisconnected()
        Log.w(TAG, "Listener disconnected — OEM may have killed the process")
        postReconnectNudge()
        // Request rebind so Android attempts to reconnect us.
        requestRebind(ComponentName(this, LithiumNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val record = buildRecord(sbn)

        // Async DB write — must not block the callback thread.
        serviceScope.launch {
            val rowId = notificationRepo.insert(record)
            keyToRowId[sbn.key] = rowId
        }

        // Rule evaluation is synchronous and in-memory.
        when (ruleEngine.evaluate(record)) {
            RuleAction.SUPPRESS -> {
                cancelNotification(sbn.key)
            }
            RuleAction.QUEUE -> {
                cancelNotification(sbn.key)
                // Enqueuing happens in M2 when QueuedNotification is wired in.
                // For M1, the record is already in the DB; the queue write is a no-op placeholder.
            }
            RuleAction.ALLOW -> Unit
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        val removedAtMs = System.currentTimeMillis()
        val reasonString = removalReasonString(reason)
        val rowId = keyToRowId.remove(sbn.key) ?: return
        val packageName = sbn.packageName

        serviceScope.launch {
            notificationRepo.updateRemoval(rowId, removedAtMs, reasonString)
        }

        // When the user taps a notification, measure the resulting app session.
        // Delay 5 seconds to allow the app to come to foreground before querying usage events.
        if (reason == REASON_CLICK) {
            serviceScope.launch {
                delay(TAP_SESSION_DELAY_MS)
                val session = usageTracker.measureSessionAfterTap(packageName, removedAtMs)
                if (session != null) {
                    sessionRepo.insert(session)
                    Log.d(TAG, "Session recorded for $packageName: duration=${session.durationMs}ms")
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------------------

    private fun buildRecord(sbn: StatusBarNotification): NotificationRecord {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // isSenderInContacts is synchronous; it hits the LRU cache on repeat senders
        // and gracefully returns false if READ_CONTACTS permission is absent.
        val isFromContact = contactsResolver.isSenderInContacts(sbn)
        val (tier, tierReason) = TierClassifier.classify(
            packageName = sbn.packageName,
            title = title,
            text = text,
            isOngoing = sbn.isOngoing,
            category = sbn.notification.category,
        )
        return NotificationRecord(
            packageName = sbn.packageName,
            postedAtMs = sbn.postTime,
            title = title,
            text = text,
            channelId = sbn.notification.channelId,
            category = sbn.notification.category,
            isOngoing = sbn.isOngoing,
            isFromContact = isFromContact,
            tier = tier,
            tierReason = tierReason,
        )
    }

    private fun removalReasonString(reason: Int): String = when (reason) {
        REASON_CLICK -> "click"
        REASON_CANCEL -> "cancel"
        REASON_CANCEL_ALL -> "cancel_all"
        REASON_ERROR -> "error"
        REASON_PACKAGE_CHANGED -> "package_changed"
        REASON_USER_STOPPED -> "user_stopped"
        REASON_PACKAGE_BANNED -> "package_banned"
        REASON_APP_CANCEL -> "app_cancel"
        REASON_APP_CANCEL_ALL -> "app_cancel_all"
        REASON_LISTENER_CANCEL -> "listener_cancel"
        REASON_LISTENER_CANCEL_ALL -> "listener_cancel_all"
        REASON_GROUP_SUMMARY_CANCELED -> "group_summary_canceled"
        REASON_GROUP_OPTIMIZATION -> "group_optimization"
        REASON_PACKAGE_SUSPENDED -> "package_suspended"
        REASON_PROFILE_TURNED_OFF -> "profile_turned_off"
        REASON_UNAUTOBUNDLED -> "unautobundled"
        REASON_CHANNEL_BANNED -> "channel_banned"
        REASON_SNOOZED -> "snoozed"
        REASON_TIMEOUT -> "timeout"
        else -> "unknown_$reason"
    }

    /** Post a low-priority notification nudging the user to exempt Lithium from battery optimization. */
    private fun postReconnectNudge() {
        val channelId = "lithium_system"
        val nm = getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Lithium System",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Service health and permission status"
                }
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Lithium disconnected")
            .setContentText("Tap to open battery settings and add Lithium to Do Not Optimize.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        nm.notify(NUDGE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "LithiumListener"
        private const val NUDGE_NOTIFICATION_ID = 1001

        /** Delay before querying usage events after a notification tap, to allow app launch. */
        private const val TAP_SESSION_DELAY_MS = 5_000L
    }
}
