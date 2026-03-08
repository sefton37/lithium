package ai.talkingrock.lithium.engine

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import ai.talkingrock.lithium.data.model.SessionRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Correlates notification taps with app usage sessions via [UsageStatsManager].
 *
 * Call [measureSessionAfterTap] from a coroutine (with a 5–10 second delay after the tap)
 * to let the target app come to foreground before querying usage events.
 *
 * When PACKAGE_USAGE_STATS permission is absent, returns null silently. No crash.
 */
@Singleton
class UsageTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val usageStatsManager: UsageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    /**
     * Measures the app session that followed a notification tap.
     *
     * Queries usage events from [tapTime] to [tapTime] + 4 hours:
     * - Finds the first ACTIVITY_RESUMED for [packageName] within 5 seconds of [tapTime].
     * - Then finds the next ACTIVITY_PAUSED for [packageName] to determine session end.
     *
     * Returns a [SessionRecord] with duration, or null if:
     * - PACKAGE_USAGE_STATS permission is absent
     * - No ACTIVITY_RESUMED event is found within the 5-second window
     * - Any error occurs during the query
     */
    @WorkerThread
    fun measureSessionAfterTap(packageName: String, tapTime: Long): SessionRecord? {
        if (!hasUsageStatsPermission()) {
            Log.d(TAG, "PACKAGE_USAGE_STATS not granted, skipping session measurement")
            return null
        }

        return try {
            querySession(packageName, tapTime)
        } catch (e: Exception) {
            Log.w(TAG, "measureSessionAfterTap failed for $packageName: ${e.message}")
            null
        }
    }

    private fun querySession(packageName: String, tapTime: Long): SessionRecord? {
        val windowEnd = tapTime + QUERY_WINDOW_MS
        val events = usageStatsManager.queryEvents(tapTime, windowEnd)

        var resumedTime: Long? = null
        val usageEvent = UsageEvents.Event()

        // Walk events in order: find RESUMED within tap window, then PAUSED
        while (events.hasNextEvent()) {
            events.getNextEvent(usageEvent)

            if (usageEvent.packageName != packageName) continue

            when (usageEvent.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (resumedTime == null) {
                        val lag = usageEvent.timeStamp - tapTime
                        if (lag <= RESUME_WINDOW_MS) {
                            resumedTime = usageEvent.timeStamp
                            Log.d(TAG, "Found RESUMED for $packageName at ${usageEvent.timeStamp} (lag ${lag}ms)")
                        }
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (resumedTime != null) {
                        val endedAt = usageEvent.timeStamp
                        val duration = endedAt - resumedTime
                        Log.d(TAG, "Found PAUSED for $packageName at $endedAt, duration ${duration}ms")
                        return SessionRecord(
                            packageName = packageName,
                            startedAtMs = resumedTime,
                            endedAtMs = endedAt,
                            durationMs = duration
                        )
                    }
                }
            }
        }

        // RESUMED found but no PAUSED yet (session still ongoing at query time)
        if (resumedTime != null) {
            Log.d(TAG, "Session for $packageName started at $resumedTime but not yet ended")
            return SessionRecord(
                packageName = packageName,
                startedAtMs = resumedTime,
                endedAtMs = null,
                durationMs = null
            )
        }

        Log.d(TAG, "No ACTIVITY_RESUMED found for $packageName within ${RESUME_WINDOW_MS}ms of tap")
        return null
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val TAG = "UsageTracker"

        /** Query window: tap time to tap time + 4 hours. */
        private const val QUERY_WINDOW_MS = 4 * 60 * 60 * 1000L

        /** Maximum lag between tap and ACTIVITY_RESUMED to count as a tap-driven session. */
        private const val RESUME_WINDOW_MS = 5_000L
    }
}
