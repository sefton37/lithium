package ai.talkingrock.lithium.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.talkingrock.lithium.MainActivity
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.repository.BehaviorProfileRepository
import ai.talkingrock.lithium.data.repository.ReportRepository
import ai.talkingrock.lithium.service.DataReadinessNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Named

/**
 * WorkManager worker that classifies unclassified notifications then produces the daily briefing.
 *
 * Scheduled as a periodic job (every 24 hours) with constraints:
 *   - device charging
 *   - battery not low
 *   - device idle
 *
 * These constraints ensure the analysis pass runs quietly in the background without
 * impacting user-perceived performance or battery.
 *
 * See [ai.talkingrock.lithium.LithiumApp] and [ai.talkingrock.lithium.service.BootReceiver]
 * for scheduling and re-scheduling logic.
 *
 * Worker logic:
 * 0. Load AI models (ONNX and/or llama.cpp) from the models directory.
 * 1. Load behavioral profiles for profile-aware classification.
 * 2. Classify up to [MAX_BATCH_SIZE] unclassified records (3-tier: ONNX → llama.cpp → heuristic).
 * 3. Run [PatternAnalyzer] to aggregate stats over the past 24 hours.
 * 4. Run [ReportGenerator] to produce a plain-language report.
 * 5. Run [SuggestionGenerator] to produce rule suggestions (with blended tap rate).
 * 6. Data retention cleanup.
 * 7. Accumulate behavioral profiles from this cycle's data.
 * 8. Release AI models.
 */
@HiltWorker
class AiAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val sessionDao: SessionDao,
    private val aiEngine: AiEngine,
    private val llamaEngine: LlamaEngine,
    private val classifier: NotificationClassifier,
    private val patternAnalyzer: PatternAnalyzer,
    private val reportGenerator: ReportGenerator,
    private val suggestionGenerator: SuggestionGenerator,
    private val reportRepository: ReportRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val sharedPreferences: SharedPreferences,
    @Named("modelDir") private val modelDir: String
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: starting analysis pass")

        // ---------------------------------------------------------------------------------
        // Step 0: Load AI models (ONNX and/or llama.cpp) from the models directory
        // ---------------------------------------------------------------------------------
        try {
            aiEngine.loadModel(modelDir)
            llamaEngine.loadModel(modelDir)
            val tier = when {
                aiEngine.isModelLoaded() -> "ONNX"
                llamaEngine.isModelLoaded() -> "llama.cpp"
                else -> "heuristic"
            }
            Log.d(TAG, "doWork: classification tier=$tier")
        } catch (e: Exception) {
            Log.e(TAG, "doWork: model loading failed, proceeding with heuristic", e)
        }

        // ---------------------------------------------------------------------------------
        // Step 1: Load behavioral profiles for profile-aware classification
        // ---------------------------------------------------------------------------------
        val profiles = try {
            behaviorProfileRepository.getProfileMap()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to load behavioral profiles, proceeding without", e)
            emptyMap()
        }
        Log.d(TAG, "doWork: loaded ${profiles.size} behavioral profile(s)")

        // ---------------------------------------------------------------------------------
        // Step 2: Classify unclassified notifications
        // ---------------------------------------------------------------------------------
        val unclassified = try {
            notificationDao.getUnclassified(limit = MAX_BATCH_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to query unclassified notifications", e)
            return Result.failure()
        }

        if (unclassified.isNotEmpty()) {
            Log.d(TAG, "doWork: classifying ${unclassified.size} record(s)")
            var successCount = 0
            var failureCount = 0

            for (record in unclassified) {
                try {
                    val profileKey = Pair(record.packageName, record.channelId ?: "")
                    val profile = profiles[profileKey]
                    val result = classifier.classify(record, profile)
                    notificationDao.updateClassification(
                        id = record.id,
                        classification = result.label,
                        confidence = result.confidence
                    )
                    successCount++
                } catch (e: Exception) {
                    Log.e(TAG, "doWork: classification failed for id=${record.id}", e)
                    failureCount++
                }
            }
            Log.d(TAG, "doWork: classification complete — classified=$successCount, failed=$failureCount")
        } else {
            Log.d(TAG, "doWork: no unclassified notifications")
        }

        // ---------------------------------------------------------------------------------
        // Step 2.1: Reclassify ongoing notifications tagged "unknown" as BACKGROUND
        // One-time migration: before the BACKGROUND category existed, ongoing
        // notifications (media controls, navigation, etc.) were classified as "unknown".
        // ---------------------------------------------------------------------------------
        try {
            val misclassified = notificationDao.getOngoingMisclassified(limit = MAX_BATCH_SIZE)
            if (misclassified.isNotEmpty()) {
                Log.d(TAG, "doWork: reclassifying ${misclassified.size} ongoing→background record(s)")
                for (record in misclassified) {
                    val profileKey = Pair(record.packageName, record.channelId ?: "")
                    val profile = profiles[profileKey]
                    val result = classifier.classify(record, profile)
                    notificationDao.updateClassification(
                        id = record.id,
                        classification = result.label,
                        confidence = result.confidence
                    )
                }
                Log.d(TAG, "doWork: ongoing reclassification complete — ${misclassified.size} record(s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "doWork: ongoing reclassification failed", e)
            // Non-fatal: will retry on next run.
        }

        // ---------------------------------------------------------------------------------
        // Step 2.5: Check data readiness threshold (one-time notification)
        // ---------------------------------------------------------------------------------
        if (!sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false)) {
            try {
                val classifiedCount = notificationDao.countClassified()
                val distinctApps = notificationDao.countDistinctClassifiedApps()

                if (classifiedCount >= Prefs.DATA_READY_MIN_COUNT &&
                    distinctApps >= Prefs.DATA_READY_MIN_APPS) {
                    Log.i(TAG, "doWork: data readiness threshold reached " +
                            "(classified=$classifiedCount, apps=$distinctApps) — notifying user")
                    sharedPreferences.edit()
                        .putBoolean(Prefs.DATA_READY_NOTIFIED, true)
                        .apply()
                    DataReadinessNotifier.notify(applicationContext)
                } else {
                    Log.d(TAG, "doWork: data not yet ready " +
                            "(classified=$classifiedCount/${Prefs.DATA_READY_MIN_COUNT}, " +
                            "apps=$distinctApps/${Prefs.DATA_READY_MIN_APPS})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "doWork: readiness check failed", e)
            }
        }

        // ---------------------------------------------------------------------------------
        // Step 3: Aggregate patterns for the past 24 hours
        // ---------------------------------------------------------------------------------
        val since = System.currentTimeMillis() - ANALYSIS_WINDOW_MS
        Log.d(TAG, "doWork: aggregating patterns since=$since")

        val byCategory = try {
            patternAnalyzer.getNotificationsByCategory(since)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: pattern analysis failed", e)
            return Result.failure()
        }

        val appStats = patternAnalyzer.getAppStats(since)
        val contactVsAlgo = patternAnalyzer.getContactVsAlgorithmicRatio(since)

        val totalInWindow = byCategory.values.sumOf { it.size }
        val backgroundCount = byCategory[NotificationCategory.BACKGROUND]?.size ?: 0
        val alertCount = totalInWindow - backgroundCount
        Log.d(TAG, "doWork: found $totalInWindow notifications in window ($alertCount alerts, $backgroundCount background)")

        // ---------------------------------------------------------------------------------
        // Step 4: Generate and persist the report
        // ---------------------------------------------------------------------------------
        val report = reportGenerator.generate(
            byCategory = byCategory,
            appStats = appStats,
            contactVsAlgo = contactVsAlgo
        )
        val reportId = try {
            reportRepository.insertReport(report)
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to insert report", e)
            return Result.failure()
        }
        Log.d(TAG, "doWork: inserted report id=$reportId")

        // ---------------------------------------------------------------------------------
        // Step 5: Generate and persist suggestions linked to the new report
        // ---------------------------------------------------------------------------------
        val allNotifications = byCategory.values.flatten()
        val rawSuggestions = suggestionGenerator.generate(
            byCategory = byCategory,
            appStats = appStats,
            notifications = allNotifications,
            profiles = profiles
        )
        // Attach the report ID to each suggestion before insertion.
        val linkedSuggestions = rawSuggestions.map { it.copy(reportId = reportId) }

        // Tier-reason based suggestions (wider window, deterministic — works without ML models).
        val tierSince = System.currentTimeMillis() - SUGGEST_WINDOW_MS
        val tierStats = try {
            notificationDao.getTierReasonStats(
                sinceMs = tierSince,
                maxTier = 1,
                minCount = SuggestionGenerator.TIER_SUGGEST_MIN_VOLUME
            )
        } catch (e: Exception) {
            Log.w(TAG, "doWork: tier-reason stats query failed", e)
            emptyList()
        }
        val tierSuggestions = suggestionGenerator
            .generateFromTierReasons(tierStats, rawSuggestions)
            .map { it.copy(reportId = reportId) }
        Log.d(TAG, "doWork: tier-path suggestions=${tierSuggestions.size} (stats rows=${tierStats.size})")

        val allSuggestions = linkedSuggestions + tierSuggestions

        if (allSuggestions.isNotEmpty()) {
            try {
                reportRepository.insertSuggestions(allSuggestions)
            } catch (e: Exception) {
                Log.e(TAG, "doWork: failed to insert suggestions", e)
                // Non-fatal: the report already exists; suggestions can be retried on next run.
            }
            Log.d(TAG, "doWork: inserted ${allSuggestions.size} suggestion(s) (ml=${linkedSuggestions.size}, tier=${tierSuggestions.size})")
        } else {
            Log.d(TAG, "doWork: no suggestions generated")
        }

        // ---------------------------------------------------------------------------------
        // Step 6: Data retention cleanup — delete records older than configured retention
        // ---------------------------------------------------------------------------------
        val retentionDays = sharedPreferences.getInt(Prefs.PREF_RETENTION_DAYS, Prefs.DEFAULT_RETENTION_DAYS)
        val retentionMs = retentionDays * 24L * 60L * 60L * 1000L
        val retentionThresholdMs = System.currentTimeMillis() - retentionMs
        try {
            notificationDao.deleteOlderThan(retentionThresholdMs)
            sessionDao.deleteOlderThan(retentionThresholdMs)
            Log.d(TAG, "doWork: retention cleanup complete — threshold=$retentionThresholdMs (${retentionDays}d)")
        } catch (e: Exception) {
            Log.e(TAG, "doWork: retention cleanup failed", e)
            // Non-fatal: cleanup will retry on the next worker run.
        }

        // ---------------------------------------------------------------------------------
        // Step 7: Accumulate behavioral profiles from this cycle's data
        // ---------------------------------------------------------------------------------
        val nowMs = System.currentTimeMillis()
        try {
            // Record engagement stats for each classified notification in this window
            val allClassified = byCategory.values.flatten()
            Log.d(TAG, "doWork: accumulating profiles for ${allClassified.size} notification(s)")
            for (record in allClassified) {
                val label = record.aiClassification ?: NotificationCategory.UNKNOWN.label
                behaviorProfileRepository.recordNotification(record, label, nowMs)
            }

            // Record session stats per package
            val sessions = sessionDao.getSessionsSince(since)
            val sessionsByPackage = sessions.groupBy { it.packageName }
            for ((pkg, pkgSessions) in sessionsByPackage) {
                if (pkg.isBlank()) continue
                val totalDuration = pkgSessions.sumOf { it.durationMs ?: 0L }
                behaviorProfileRepository.recordSessions(pkg, pkgSessions.size, totalDuration, nowMs)
            }
            Log.d(TAG, "doWork: profile accumulation complete — " +
                    "${allClassified.size} notifications, ${sessions.size} sessions")
        } catch (e: Exception) {
            Log.e(TAG, "doWork: profile accumulation failed", e)
            // Non-fatal: profiles will catch up on next run.
        }

        // Capture suggestion count for the completion notification, before
        // moving on to retention/profile/cleanup steps.
        val finalSuggestionCount = allSuggestions.size

        // ---------------------------------------------------------------------------------
        // Step 8: Release AI models to free native memory
        // ---------------------------------------------------------------------------------
        try {
            aiEngine.releaseModel()
            llamaEngine.releaseModel()
            Log.d(TAG, "doWork: models released")
        } catch (e: Exception) {
            Log.w(TAG, "doWork: model release failed", e)
        }

        Log.d(TAG, "doWork: analysis pass complete")
        postCompletionNotification(
            suggestionCount = finalSuggestionCount,
            hasReport = true
        )
        return Result.success()
    }

    /**
     * Posts a low-priority notification announcing that analysis is complete.
     * Tapping opens [MainActivity] → briefing. The message varies on whether
     * suggestions were produced so users see immediate signal about the run.
     */
    private fun postCompletionNotification(suggestionCount: Int, hasReport: Boolean) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Lithium Briefing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Signals when your daily briefing is ready"
                }
            )
        }

        val title = when {
            !hasReport -> "Analysis finished"
            suggestionCount > 0 -> "Your briefing is ready"
            else -> "Analysis complete"
        }
        val body = when {
            !hasReport -> "No new report this cycle."
            suggestionCount == 1 -> "1 suggestion to review. Tap to open."
            suggestionCount > 0 -> "$suggestionCount suggestions to review. Tap to open."
            else -> "No new suggestions this cycle."
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "AiAnalysisWorker"

        /** Maximum records classified per worker run. Prevents unbounded processing time. */
        const val MAX_BATCH_SIZE = 500

        /** Unique work name used for scheduling and re-enqueuing. */
        const val WORK_NAME = "lithium_ai_analysis"

        /** Analysis window: 24 hours in milliseconds (used for the daily report). */
        const val ANALYSIS_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** Wider window used by the tier-reason suggestion path to catch lifetime patterns. */
        const val SUGGEST_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L

        /** Completion-notification channel and IDs. */
        private const val CHANNEL_ID = "lithium_briefing"
        private const val NOTIFICATION_ID = 4001
        private const val REQUEST_CODE = 4001

        // Pref keys centralised in ai.talkingrock.lithium.data.Prefs
    }
}
