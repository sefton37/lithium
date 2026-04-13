package ai.talkingrock.lithium.engine

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Hardcoded safety allowlist for notifications that Lithium must never cancel.
 *
 * These categories are either uncancellable by a [NotificationListenerService] (Android
 * silently ignores the cancel call) or life-critical (calls, alarms) where suppression
 * would be dangerous to the user.
 *
 * This object is intentionally not user-configurable. The allowlist is checked at the very
 * top of the notification pipeline, before any rule evaluation.
 *
 * Categories that are exempt:
 * - Ongoing notifications (FLAG_ONGOING_EVENT) — Android ignores cancel for these.
 * - CATEGORY_CALL — incoming calls; may be a full-screen intent, safety-critical.
 * - CATEGORY_ALARM — alarms; FLAG_NO_CLEAR prevents listener cancellation.
 * - CATEGORY_TRANSPORT — media transport controls; ongoing media session.
 * - System packages: android, com.android.systemui — cancel silently fails.
 * - Known dialer packages — belt-and-suspenders alongside CATEGORY_CALL.
 * - Lithium's own package (ai.talkingrock.lithium) — prevents re-surface loops.
 * - FLAG_FOREGROUND_SERVICE set — cancel silently fails on API 34+ where foreground service notifications are protected.
 */
object SafetyAllowlist {

    private val SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.samsung.android.incallui",
        "com.google.android.dialer",
    )

    // Base package prefix — covers both release ("ai.talkingrock.lithium") and debug
    // ("ai.talkingrock.lithium.debug") build flavors. A startsWith check is safe because
    // no other package on the device shares this prefix.
    private const val LITHIUM_PACKAGE_PREFIX = "ai.talkingrock.lithium"

    // FLAG_FOREGROUND_SERVICE (android.app.Notification, value 0x00000040) — cancel silently fails on API 34+
    // where foreground service notifications are protected.
    private const val FLAG_FOREGROUND_SERVICE = 0x00000040

    /**
     * Returns true if this notification must be exempted from cancellation.
     *
     * Callers should write the record with [disposition] = "safety_exempt" and return
     * without calling [android.service.notification.NotificationListenerService.cancelNotification].
     */
    fun isSafetyExempt(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification

        // Lithium's own notifications — never cancel, would cause re-surface loops.
        // startsWith covers both release and debug build flavors.
        if (sbn.packageName.startsWith(LITHIUM_PACKAGE_PREFIX)) return true

        // Known system packages — cancel silently fails.
        if (sbn.packageName in SYSTEM_PACKAGES) return true

        // Ongoing (FLAG_ONGOING_EVENT) — Android ignores cancel for these.
        if (sbn.isOngoing) return true

        // Incoming calls — safety-critical, may be full-screen intent.
        if (notification.category == Notification.CATEGORY_CALL) return true

        // Alarms — FLAG_NO_CLEAR prevents listener cancellation.
        if (notification.category == Notification.CATEGORY_ALARM) return true

        // Media transport controls — ongoing media session.
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true

        // Foreground service flag (API 34+) — cancel silently fails.
        if (notification.flags and FLAG_FOREGROUND_SERVICE != 0) return true

        return false
    }
}
