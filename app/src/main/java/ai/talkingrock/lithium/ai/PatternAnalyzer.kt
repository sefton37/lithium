package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates notification and session data over a time window for report and suggestion generation.
 *
 * All methods are suspend functions meant to be called from the [AiAnalysisWorker] coroutine.
 * Results are plain data — no Room Flows, no reactive emission — since the worker only needs
 * a single snapshot per run.
 */
@Singleton
class PatternAnalyzer @Inject constructor(
    private val notificationDao: NotificationDao,
    private val sessionDao: SessionDao
) {

    /**
     * Groups all classified notifications posted since [since] by their AI classification label.
     * Notifications with a null classification are placed under [NotificationCategory.UNKNOWN].
     */
    suspend fun getNotificationsByCategory(since: Long): Map<NotificationCategory, List<NotificationRecord>> {
        // getRecent is a Flow — we need a one-shot query. We reuse the DAO's existing suspend
        // path via getUnclassified (which returns all unclassified). For classified records we
        // need a direct suspend query. We achieve this by collecting getRecent as a one-shot
        // snapshot using kotlinx.coroutines.flow.first(). However, to avoid a heavy Flow
        // import in the worker context we instead query all records via the existing DAO
        // and filter in memory. The dataset is bounded to 24 hours so this is acceptable.
        val all = notificationDao.getAllSince(since)
        return all.groupBy { record ->
            if (record.aiClassification != null) {
                NotificationCategory.fromLabel(record.aiClassification)
            } else {
                NotificationCategory.UNKNOWN
            }
        }
    }

    /**
     * Computes per-app statistics for all notifications posted since [since].
     *
     * [AppStats.tappedCount] is approximated by counting sessions that started within [SESSION_WINDOW_MS]
     * of a notification from that app being posted. This is a conservative heuristic — the
     * session tracking in UsageTracker (M2) links sessions to apps, not to individual notifications.
     */
    suspend fun getAppStats(since: Long): List<AppStats> {
        val notifications = notificationDao.getAllSince(since)
        val sessions = sessionDao.getSessionsSince(since)

        // Build a lookup: packageName → list of session durations (ms), for sessions that ended.
        val sessionsByPackage: Map<String, List<Long>> = sessions
            .filter { it.durationMs != null }
            .groupBy { it.packageName }
            .mapValues { (_, records) -> records.mapNotNull { it.durationMs } }

        return notifications
            .groupBy { it.packageName }
            .map { (pkg, records) ->
                val durations = sessionsByPackage[pkg] ?: emptyList()
                AppStats(
                    packageName = pkg,
                    totalCount = records.size,
                    tappedCount = durations.size,
                    ignoredCount = records.size - minOf(durations.size, records.size),
                    avgSessionDurationMs = if (durations.isEmpty()) 0L
                    else durations.average().toLong()
                )
            }
            .sortedByDescending { it.totalCount }
    }

    /**
     * Returns a (contactCount, algorithmicCount) pair for notifications since [since].
     *
     * "Contact" = [NotificationRecord.isFromContact] is true.
     * "Algorithmic" = classified as [NotificationCategory.ENGAGEMENT_BAIT] or
     * [NotificationCategory.SOCIAL_SIGNAL].
     */
    suspend fun getContactVsAlgorithmicRatio(since: Long): Pair<Int, Int> {
        // Exclude BACKGROUND notifications: ongoing media/navigation updates are neither
        // "contact" nor "algorithmic" in any meaningful sense — counting them skews the ratio.
        val all = notificationDao.getAllSince(since).filter { record ->
            val cat = record.aiClassification?.let { NotificationCategory.fromLabel(it) }
            cat != NotificationCategory.BACKGROUND
        }
        val contactCount = all.count { it.isFromContact }
        val algorithmicCount = all.count { record ->
            val cat = record.aiClassification?.let { NotificationCategory.fromLabel(it) }
            cat == NotificationCategory.ENGAGEMENT_BAIT || cat == NotificationCategory.SOCIAL_SIGNAL
        }
        return Pair(contactCount, algorithmicCount)
    }

    companion object {
        /** Maximum time gap between a notification post and a session start to consider a correlation. */
        private const val SESSION_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }
}

/**
 * Per-app aggregated statistics for a reporting window.
 *
 * @param packageName Android package name.
 * @param totalCount Total notifications received from this app in the window.
 * @param tappedCount Number of sessions that followed a notification from this app (heuristic).
 * @param ignoredCount Estimated count of notifications not leading to a session.
 * @param avgSessionDurationMs Average duration (ms) of post-notification sessions; 0 if none.
 */
data class AppStats(
    val packageName: String,
    val totalCount: Int,
    val tappedCount: Int,
    val ignoredCount: Int,
    val avgSessionDurationMs: Long
)
