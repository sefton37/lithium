package ai.talkingrock.lithium.debug

import android.util.Log
import ai.talkingrock.lithium.ai.NotificationCategory
import ai.talkingrock.lithium.ai.NotificationClassifier
import ai.talkingrock.lithium.ai.PatternAnalyzer
import ai.talkingrock.lithium.ai.ReportGenerator
import ai.talkingrock.lithium.ai.SuggestionGenerator
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.SessionRecord
import ai.talkingrock.lithium.data.repository.ReportRepository

data class SimulationResult(
    val profileNumber: Int,
    val profileName: String,
    val profileDescription: String,
    val totalNotifications: Int,
    val totalSessions: Int,
    val classificationBreakdown: Map<String, Int>,
    val reportText: String,
    val suggestions: List<String>,
    val topApps: List<String>
)

class SimulationRunner(
    private val notificationDao: NotificationDao,
    private val sessionDao: SessionDao,
    private val reportDao: ReportDao,
    private val suggestionDao: SuggestionDao,
    private val queueDao: QueueDao,
    private val classifier: NotificationClassifier,
    private val patternAnalyzer: PatternAnalyzer,
    private val reportGenerator: ReportGenerator,
    private val suggestionGenerator: SuggestionGenerator,
    private val reportRepository: ReportRepository
) {
    companion object {
        private const val TAG = "LithiumSim"
        private const val ANALYSIS_WINDOW_MS = 24 * 60 * 60 * 1000L

        val PROFILE_NAMES = mapOf(
            1 to ("Doom Scroller Dana" to "Social media heavy, low engagement"),
            2 to ("Shopping Spree Sam" to "E-commerce bombardment"),
            3 to ("Remote Worker Riley" to "Work communication heavy, high engagement"),
            4 to ("Teenage Tyler" to "Social-obsessed teen, very high engagement"),
            5 to ("Minimalist Maya" to "Very few notifications, sparse data"),
            6 to ("Notification Hoarder Hank" to "200+ notifications, zero taps"),
            7 to ("Parent Pat" to "Family-focused, high engagement with personal"),
            8 to ("Gamer Gabe" to "Gaming-focused lifestyle"),
            9 to ("Edge Case Eddie" to "Boundary conditions and weird data")
        )
    }

    suspend fun runProfile(profileNumber: Int): SimulationResult {
        val (name, description) = PROFILE_NAMES[profileNumber]
            ?: throw IllegalArgumentException("Invalid profile: $profileNumber")

        Log.d(TAG, "=== PROFILE $profileNumber: $name ===")
        Log.d(TAG, "Description: $description")

        // Step 0: Clear all tables
        notificationDao.deleteAll()
        sessionDao.deleteAll()
        reportDao.deleteAll()
        suggestionDao.deleteAll()
        queueDao.deleteAll()

        // Step 1: Generate and insert synthetic data
        val (notifications, sessions) = getProfileData(profileNumber)
        Log.d(TAG, "Inserting ${notifications.size} notifications, ${sessions.size} sessions")

        for (n in notifications) {
            notificationDao.insertOrReplace(n)
        }
        for (s in sessions) {
            sessionDao.insert(s)
        }

        // Step 2: Classify all unclassified notifications
        val unclassified = notificationDao.getUnclassified(limit = 500)
        Log.d(TAG, "Classifying ${unclassified.size} notifications")
        for (record in unclassified) {
            try {
                val result = classifier.classify(record)
                notificationDao.updateClassification(
                    id = record.id,
                    classification = result.label,
                    confidence = result.confidence
                )
            } catch (e: Exception) {
                Log.e(TAG, "Classification failed for id=${record.id}: ${e.message}")
            }
        }

        // Step 3: Aggregate patterns
        val since = System.currentTimeMillis() - ANALYSIS_WINDOW_MS
        val byCategory = patternAnalyzer.getNotificationsByCategory(since)
        val appStats = patternAnalyzer.getAppStats(since)
        val contactVsAlgo = patternAnalyzer.getContactVsAlgorithmicRatio(since)

        // Step 4: Generate report
        val report = reportGenerator.generate(byCategory, appStats, contactVsAlgo)
        val reportId = reportRepository.insertReport(report)

        // Step 5: Generate suggestions
        val allNotifications = byCategory.values.flatten()
        val rawSuggestions = suggestionGenerator.generate(byCategory, appStats, allNotifications)
        val linkedSuggestions = rawSuggestions.map { it.copy(reportId = reportId) }
        if (linkedSuggestions.isNotEmpty()) {
            reportRepository.insertSuggestions(linkedSuggestions)
        }

        // Build classification breakdown
        val breakdown = mutableMapOf<String, Int>()
        for ((cat, records) in byCategory) {
            if (records.isNotEmpty()) {
                breakdown[cat.label] = records.size
            }
        }

        // Extract report text from JSON
        val reportText = try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(report.summaryJson)
            json.jsonObject["text"]?.jsonPrimitive?.content ?: "(no text)"
        } catch (e: Exception) {
            report.summaryJson
        }

        val suggestionRationales = linkedSuggestions.map {
            "[${it.action.uppercase()}] ${it.rationale}"
        }

        val topAppNames = appStats.take(5).map {
            "${reportGenerator.friendlyName(it.packageName)}: ${it.totalCount} sent, ${it.tappedCount} tapped"
        }

        // Log results
        Log.d(TAG, "--- REPORT ---")
        Log.d(TAG, reportText)
        Log.d(TAG, "--- CLASSIFICATION ---")
        for ((cat, count) in breakdown) {
            Log.d(TAG, "  $cat: $count")
        }
        Log.d(TAG, "--- SUGGESTIONS (${linkedSuggestions.size}) ---")
        for (s in suggestionRationales) {
            Log.d(TAG, "  $s")
        }
        Log.d(TAG, "--- TOP APPS ---")
        for (a in topAppNames) {
            Log.d(TAG, "  $a")
        }
        Log.d(TAG, "=== END PROFILE $profileNumber ===\n")

        return SimulationResult(
            profileNumber = profileNumber,
            profileName = name,
            profileDescription = description,
            totalNotifications = notifications.size,
            totalSessions = sessions.size,
            classificationBreakdown = breakdown,
            reportText = reportText,
            suggestions = suggestionRationales,
            topApps = topAppNames
        )
    }

    private fun getProfileData(profile: Int): Pair<List<NotificationRecord>, List<SessionRecord>> {
        return when (profile) {
            1 -> SyntheticProfiles1.profile1DoomScroller()
            2 -> SyntheticProfiles1.profile2ShoppingSam()
            3 -> SyntheticProfiles1.profile3RemoteWorkerRiley()
            4 -> SyntheticProfiles1.profile4TeenageTyler()
            5 -> SyntheticProfiles1.profile5MinimalistMaya()
            6 -> SyntheticProfiles2.profile6Hoarder()
            7 -> SyntheticProfiles2.profile7Parent()
            8 -> SyntheticProfiles2.profile8Gamer()
            9 -> SyntheticProfiles2.profile9EdgeCase()
            else -> throw IllegalArgumentException("Invalid profile: $profile")
        }
    }

    private val kotlinx.serialization.json.JsonElement.jsonObject
        get() = this as kotlinx.serialization.json.JsonObject

    private val kotlinx.serialization.json.JsonElement.jsonPrimitive
        get() = this as kotlinx.serialization.json.JsonPrimitive
}
