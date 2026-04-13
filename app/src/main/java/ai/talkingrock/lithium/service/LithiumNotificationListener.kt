package ai.talkingrock.lithium.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import ai.talkingrock.lithium.MainActivity
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ShadeModeSeeder
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.QueuedNotification
import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.data.repository.SessionRepository
import ai.talkingrock.lithium.data.repository.ShadeModeRepository
import ai.talkingrock.lithium.classification.TierClassifier
import ai.talkingrock.lithium.engine.ContactsResolver
import ai.talkingrock.lithium.engine.RuleAction
import ai.talkingrock.lithium.engine.RuleEngine
import ai.talkingrock.lithium.engine.SafetyAllowlist
import ai.talkingrock.lithium.engine.UsageTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
    @Inject lateinit var shadeModeRepository: ShadeModeRepository
    @Inject lateinit var shadeModeSeeder: ShadeModeSeeder
    @Inject lateinit var queueDao: QueueDao
    @Inject lateinit var notificationResurface: NotificationResurface
    @Inject lateinit var database: LithiumDatabase

    private lateinit var serviceScope: CoroutineScope

    // Coroutine job that collects the shade-mode StateFlow and manages the persistent
    // notification lifecycle. Cancelled with serviceScope in onDestroy.
    private var shadeModeCollectorJob: Job? = null

    // In-memory map of notification key -> database row ID, used to update removal records.
    // Keyed by the SBN key (package:id:tag) which is stable within a process lifetime.
    // ConcurrentHashMap: written from IO coroutine, read from main thread in onNotificationRemoved.
    private val keyToRowId = ConcurrentHashMap<String, Long>()

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

        // Self-heal: if shade mode is enabled but seed rules were never inserted (e.g., seeder
        // threw on first call, or SHADE_MODE_SEED_DONE flag is stale after a DB reset), run
        // the seeder now. ShadeModeSeeder.seedIfNeeded() is idempotent — no-op when seeds exist.
        if (shadeModeRepository.isEnabled.value) {
            serviceScope.launch {
                try {
                    shadeModeSeeder.seedIfNeeded()
                } catch (e: Exception) {
                    Log.e(TAG, "seedIfNeeded failed on listener connect", e)
                }
            }
        }

        // Start collecting the shade-mode StateFlow so the persistent notification
        // is posted/cancelled whenever the user toggles Shade Mode.
        startShadeModeCollector()
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
        // --- Safety allowlist: checked FIRST, before shade-mode gate or rule evaluation ---
        if (SafetyAllowlist.isSafetyExempt(sbn)) {
            // Lithium's own notifications (persistent indicator, reconnect nudge, readiness
            // alerts) are safety-exempt by package prefix. Skip the DB write entirely to
            // avoid polluting the notification history with self-posts.
            if (sbn.packageName.startsWith(LITHIUM_PACKAGE_PREFIX)) return

            val record = buildRecord(sbn, disposition = "safety_exempt")
            serviceScope.launch {
                val rowId = notificationRepo.insert(record)
                keyToRowId[sbn.key] = rowId
            }
            return
        }

        // --- Shade mode gate: if disabled, observe only — no cancellations ---
        if (!shadeModeRepository.isEnabled.value) {
            val record = buildRecord(sbn, disposition = "allowed")
            serviceScope.launch {
                val rowId = notificationRepo.insert(record)
                keyToRowId[sbn.key] = rowId
            }
            return
        }

        // --- Active shade mode: evaluate rules ---
        val record = buildRecord(sbn)
        // Extract SBN fields needed by NotificationResurface before launching the coroutine.
        // StatusBarNotification may be recycled by the framework after onNotificationPosted returns
        // (fix #9 — safe extraction on main thread).
        val sbnKey = sbn.key
        val sbnPkg = sbn.packageName
        val sbnExtras = sbn.notification.extras
        val sbnOriginalTitle = sbnExtras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val sbnOriginalText = sbnExtras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sbnOriginalId = sbn.id

        when (ruleEngine.evaluate(record)) {
            RuleAction.SUPPRESS -> {
                // Fix #1: insert DB record BEFORE cancelling the notification. Avoids the
                // window where the notification is removed from the shade but absent from DB.
                val suppressed = record.copy(disposition = "suppressed")
                serviceScope.launch {
                    try {
                        val rowId = notificationRepo.insert(suppressed)
                        keyToRowId[sbnKey] = rowId
                        cancelNotification(sbnKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "SUPPRESS: DB write failed for key=$sbnKey; cancelNotification skipped", e)
                    }
                }
            }
            RuleAction.QUEUE -> {
                // Fix #1: wrap both inserts in a single Room transaction, then cancel.
                // Crash between insert and cancel → notification reappears in shade (safe failure).
                // Crash between inserts (without transaction) → orphaned notification record (silent loss).
                val queued = record.copy(disposition = "queued")
                serviceScope.launch {
                    try {
                        val rowId = database.withTransaction {
                            val id = notificationRepo.insert(queued)
                            queueDao.enqueue(
                                QueuedNotification(
                                    notificationId = id,
                                    queuedAtMs = System.currentTimeMillis()
                                )
                            )
                            id
                        }
                        keyToRowId[sbnKey] = rowId
                        cancelNotification(sbnKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "QUEUE: DB write failed for key=$sbnKey; cancelNotification skipped", e)
                    }
                }
            }
            RuleAction.RESURFACE -> {
                // Fix #1: post curated notification FIRST (shade is never empty), then insert
                // DB record, then cancel the original. This eliminates the gap window where
                // neither the original nor the curated notification is visible.
                val resurfaced = record.copy(disposition = "resurfaced")
                serviceScope.launch {
                    notificationResurface.post(
                        record = resurfaced,
                        sbnKey = sbnKey,
                        pkg = sbnPkg,
                        originalTitle = sbnOriginalTitle,
                        originalText = sbnOriginalText,
                        originalId = sbnOriginalId,
                    )
                    val rowId = notificationRepo.insert(resurfaced)
                    keyToRowId[sbnKey] = rowId
                    cancelNotification(sbnKey)
                }
            }
            RuleAction.ALLOW -> {
                val allowed = record.copy(disposition = "allowed")
                serviceScope.launch {
                    val rowId = notificationRepo.insert(allowed)
                    keyToRowId[sbnKey] = rowId
                }
            }
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

    private fun buildRecord(sbn: StatusBarNotification, disposition: String? = null): NotificationRecord {
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
            isFromContact = isFromContact,
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
            disposition = disposition,
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

    /**
     * Collects the shade-mode [StateFlow] and manages the persistent notification.
     *
     * Called once from [onListenerConnected]. Any previous job is cancelled first so
     * re-binding (service restart) doesn't double-collect. The job is a child of
     * [serviceScope] and is automatically cancelled in [onDestroy].
     */
    private fun startShadeModeCollector() {
        shadeModeCollectorJob?.cancel()
        shadeModeCollectorJob = serviceScope.launch {
            shadeModeRepository.isEnabled.collect { enabled ->
                if (enabled) {
                    postPersistentNotification()
                } else {
                    cancelPersistentNotification()
                }
            }
        }
    }

    /**
     * Posts (or updates) the persistent Lithium indicator notification in the shade.
     *
     * Uses [FLAG_ONGOING_EVENT] so the user cannot accidentally swipe it away.
     * Lives on the low-importance [NotificationChannelRegistry.CHANNEL_SYSTEM] channel
     * so it produces no sound or vibration.
     *
     * The notification acts as a one-tap shortcut into [MainActivity] and makes it
     * immediately visible to the user that Shade Mode is active.
     */
    private fun postPersistentNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationChannelRegistry.CHANNEL_SYSTEM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lithium — Shade Mode Active")
            .setContentText("Tap to open")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIF_ID_PERSISTENT, notification)
        Log.d(TAG, "Persistent notification posted")
    }

    /**
     * Cancels the persistent Lithium indicator notification.
     * Called when the user disables Shade Mode.
     */
    private fun cancelPersistentNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID_PERSISTENT)
        Log.d(TAG, "Persistent notification cancelled")
    }

    /**
     * Posts a low-priority notification when the listener is disconnected.
     *
     * If Shade Mode is enabled, the nudge communicates urgency ("protection paused").
     * Channel creation is handled by [NotificationChannelRegistry] in [LithiumApp.onCreate].
     */
    private fun postReconnectNudge() {
        val nm = getSystemService(NotificationManager::class.java)
        val shadeModeOn = shadeModeRepository.isEnabled.value

        val contentText = if (shadeModeOn) {
            "Shade Mode is paused — tap to restore protection."
        } else {
            "Tap to open battery settings and add Lithium to Do Not Optimize."
        }

        val notification = NotificationCompat.Builder(this, NotificationChannelRegistry.CHANNEL_SYSTEM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Lithium disconnected")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        nm.notify(NUDGE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "LithiumListener"
        private const val NUDGE_NOTIFICATION_ID = 1001

        /**
         * Notification ID for the persistent "Shade Mode Active" indicator.
         * This notification lives in the shade as long as Shade Mode is enabled,
         * providing the user with a one-tap shortcut into the app.
         */
        const val NOTIF_ID_PERSISTENT = 1002

        /**
         * Lithium's own package prefix — covers both release ("ai.talkingrock.lithium")
         * and debug ("ai.talkingrock.lithium.debug") build flavors.
         */
        private const val LITHIUM_PACKAGE_PREFIX = "ai.talkingrock.lithium"

        /** Delay before querying usage events after a notification tap, to allow app launch. */
        private const val TAP_SESSION_DELAY_MS = 5_000L
    }
}
