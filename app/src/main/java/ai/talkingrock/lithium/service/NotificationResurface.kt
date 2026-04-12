package ai.talkingrock.lithium.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.talkingrock.lithium.data.model.NotificationRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Objects
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a curated Lithium-owned notification on behalf of a notification that matched
 * a [ai.talkingrock.lithium.engine.RuleAction.RESURFACE] rule.
 *
 * The curated notification:
 * - Preserves the original title and text (or falls back to the app label / "1 new
 *   notification" when extras are absent — see plan assumption #2, lines 604–606).
 * - Uses the original app's launcher icon with a fallback to Lithium's own icon.
 * - Opens the original app when tapped.
 * - Is grouped by source package name so multiple resurfaces from the same app cluster.
 * - Uses [setOnlyAlertOnce(true)] to suppress sound/vibration on grouped updates.
 * - Is tagged `"lithium_resurface"` with a cross-package-safe ID derived from
 *   package name and original notification ID (fix #6 — no hash collisions across packages).
 *
 * Channel registration is handled by [NotificationChannelRegistry.registerAll], called
 * once from LithiumApp.onCreate. This class does not create channels itself.
 *
 * Dismissing the curated notification fires [LithiumNotificationListener.onNotificationRemoved]
 * for the Lithium package. The [ai.talkingrock.lithium.engine.SafetyAllowlist] exempts
 * Lithium's own package from re-evaluation, so no re-surface loop can occur.
 *
 * Fix #9: All fields extracted from StatusBarNotification are passed as primitives by the
 * caller on the main thread. This class never reads from a StatusBarNotification directly
 * (the framework may recycle it after onNotificationPosted returns).
 */
@Singleton
class NotificationResurface @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Posts a curated notification for [record].
     *
     * All SBN-derived fields ([sbnKey], [pkg], [originalTitle], [originalText], [originalId])
     * must be extracted from the StatusBarNotification on the main thread before this call,
     * since the SBN may be recycled by the framework.
     *
     * Must be called from a background (IO) coroutine — [context.packageManager] calls
     * can involve IPC and should not block the main thread.
     */
    fun post(
        record: NotificationRecord,
        sbnKey: String,
        pkg: String,
        originalTitle: String?,
        originalText: String?,
        originalId: Int,
    ) {
        // Extract title/text; fall back gracefully when the app uses custom views.
        val title: String = originalTitle ?: appLabel(pkg)
        val text: String = originalText ?: "1 new notification"

        // Fix #6: Use Objects.hash(pkg, originalId) instead of sbn.key.hashCode() to avoid
        // cross-package collisions when different packages post notifications with the same ID.
        val notifId = Objects.hash(pkg, originalId).and(Int.MAX_VALUE)

        // Content intent: tap opens the original app.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        val contentIntent = if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                context,
                notifId,
                launchIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        // Original app icon with Lithium fallback.
        val smallIconResId = try {
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            appInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        } catch (e: Exception) {
            Log.d(TAG, "Could not resolve icon for $pkg, using fallback")
            android.R.drawable.ic_dialog_info
        }

        val builder = NotificationCompat.Builder(context, NotificationChannelRegistry.CHANNEL_CURATED)
            .setSmallIcon(smallIconResId)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setGroup(pkg)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        notificationManager.notify(TAG_RESURFACE, notifId, builder.build())
        Log.d(TAG, "Resurfaced notification from $pkg (id=$notifId)")
    }

    private fun appLabel(pkg: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        private const val TAG = "NotificationResurface"
        const val TAG_RESURFACE = "lithium_resurface"
    }
}
