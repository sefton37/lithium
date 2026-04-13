package ai.talkingrock.lithium.ai

import android.util.Log
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.repository.BehaviorProfileRepository
import ai.talkingrock.lithium.data.repository.ReportRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a briefing report on demand.
 *
 * Extracted from AiAnalysisWorker steps 3–5. The worker delegates here so the chat tab
 * can trigger the same report-generation flow without WorkManager constraints.
 *
 * This path does NOT classify notifications — classification still runs only in the worker.
 * On-demand briefings reflect whatever classification state already exists.
 */
@Singleton
class BriefingService @Inject constructor(
    private val notificationDao: NotificationDao,
    private val patternAnalyzer: PatternAnalyzer,
    private val reportGenerator: ReportGenerator,
    private val suggestionGenerator: SuggestionGenerator,
    private val reportRepository: ReportRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
) {

    data class Result(
        val report: Report,
        val reportId: Long,
        val suggestionCount: Int,
        /** All classified notifications seen in the analysis window. Exposed for callers
         *  that need to accumulate behavioural profiles (the worker). */
        val allNotifications: List<NotificationRecord>,
        /** Window start timestamp used for the aggregation — callers can reuse this
         *  for follow-on queries (e.g. sessions since the same point). */
        val sinceMs: Long,
    )

    /**
     * Aggregates the last [windowMs] of notification activity, persists a [Report],
     * and writes any generated suggestions linked to that report.
     */
    suspend fun generateReport(
        windowMs: Long = ANALYSIS_WINDOW_MS,
        suggestWindowMs: Long = SUGGEST_WINDOW_MS,
    ): Result {
        val since = System.currentTimeMillis() - windowMs
        val profiles = try {
            behaviorProfileRepository.getProfileMap()
        } catch (e: Exception) {
            Log.w(TAG, "generateReport: failed to load profiles, continuing without", e)
            emptyMap()
        }

        val byCategory = patternAnalyzer.getNotificationsByCategory(since)
        val appStats = patternAnalyzer.getAppStats(since)
        val contactVsAlgo = patternAnalyzer.getContactVsAlgorithmicRatio(since)

        val report = reportGenerator.generate(
            byCategory = byCategory,
            appStats = appStats,
            contactVsAlgo = contactVsAlgo,
        )
        val reportId = reportRepository.insertReport(report)
        Log.d(TAG, "generateReport: inserted report id=$reportId")

        val allNotifications = byCategory.values.flatten()
        val rawSuggestions = suggestionGenerator.generate(
            byCategory = byCategory,
            appStats = appStats,
            notifications = allNotifications,
            profiles = profiles,
        )
        val linkedSuggestions = rawSuggestions.map { it.copy(reportId = reportId) }

        val tierSince = System.currentTimeMillis() - suggestWindowMs
        val tierStats = try {
            notificationDao.getTierReasonStats(
                sinceMs = tierSince,
                maxTier = 1,
                minCount = SuggestionGenerator.TIER_SUGGEST_MIN_VOLUME,
            )
        } catch (e: Exception) {
            Log.w(TAG, "generateReport: tier-reason stats query failed", e)
            emptyList()
        }
        val tierSuggestions = suggestionGenerator
            .generateFromTierReasons(tierStats, rawSuggestions)
            .map { it.copy(reportId = reportId) }

        val allSuggestions = linkedSuggestions + tierSuggestions
        if (allSuggestions.isNotEmpty()) {
            try {
                reportRepository.insertSuggestions(allSuggestions)
            } catch (e: Exception) {
                Log.e(TAG, "generateReport: failed to insert suggestions", e)
            }
        }

        return Result(
            report = report.copy(id = reportId),
            reportId = reportId,
            suggestionCount = allSuggestions.size,
            allNotifications = allNotifications,
            sinceMs = since,
        )
    }

    companion object {
        private const val TAG = "BriefingService"
        const val ANALYSIS_WINDOW_MS = 24L * 60L * 60L * 1000L
        const val SUGGEST_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
