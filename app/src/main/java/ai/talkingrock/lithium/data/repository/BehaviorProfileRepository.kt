package ai.talkingrock.lithium.data.repository

import ai.talkingrock.lithium.ai.NotificationCategory
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates [AppBehaviorProfile] accumulation and lookup.
 *
 * Called from [ai.talkingrock.lithium.ai.AiAnalysisWorker] Step 6 to record
 * engagement stats for each notification processed in the current cycle.
 */
@Singleton
class BehaviorProfileRepository @Inject constructor(
    private val dao: AppBehaviorProfileDao
) {

    /**
     * Records a single notification's engagement data into its behavioral profile.
     *
     * Resolves tap/dismiss/auto-remove from [record]'s removalReason. Null removalReason
     * (e.g. listener restarted before removal was captured) is treated as neutral —
     * neither tap nor dismiss is incremented.
     *
     * @param record The notification record (must already have aiClassification set).
     * @param classificationLabel The classification label assigned during this cycle.
     * @param nowMs Current timestamp.
     */
    suspend fun recordNotification(
        record: NotificationRecord,
        classificationLabel: String,
        nowMs: Long
    ) {
        val channel = record.channelId ?: ""
        val reason = record.removalReason

        val tapped = if (reason == REASON_CLICK) 1 else 0
        val dismissed = if (reason == REASON_CANCEL) 1 else 0
        val autoRemoved = if (reason != null && reason.startsWith(REASON_APP_CANCEL_PREFIX)) 1 else 0

        // Upsert engagement counters
        dao.incrementStats(
            pkg = record.packageName,
            channel = channel,
            tapped = tapped,
            dismissed = dismissed,
            autoRemoved = autoRemoved,
            categoryLabel = classificationLabel,
            nowMs = nowMs
        )

        // Increment the appropriate category vote column
        incrementCategoryVote(record.packageName, channel, classificationLabel, nowMs)

        // Check if dominant category should be updated
        checkDominantCategory(record.packageName, channel, nowMs)
    }

    /**
     * Records session stats for a package. Sessions are attributed at the package
     * level (channelId = "") because SessionRecord does not track channelId.
     */
    suspend fun recordSessions(
        packageName: String,
        sessionCount: Int,
        totalDurationMs: Long,
        nowMs: Long
    ) {
        if (sessionCount > 0) {
            dao.addSessionStats(packageName, sessionCount, totalDurationMs, nowMs)
        }
    }

    /**
     * Returns all profiles as a map keyed by (packageName, channelId) for fast lookup
     * during classification and suggestion generation.
     */
    suspend fun getProfileMap(): Map<Pair<String, String>, AppBehaviorProfile> {
        return dao.getAllProfiles().associateBy { Pair(it.packageName, it.channelId) }
    }

    /**
     * Increments the vote counter for the given classification label.
     */
    private suspend fun incrementCategoryVote(
        pkg: String,
        channel: String,
        label: String,
        nowMs: Long
    ) {
        val category = NotificationCategory.fromLabel(label)
        if (category == NotificationCategory.UNKNOWN) return
        when (category) {
            NotificationCategory.PERSONAL -> dao.incrementVotePersonal(pkg, channel, nowMs)
            NotificationCategory.ENGAGEMENT_BAIT -> dao.incrementVoteEngagementBait(pkg, channel, nowMs)
            NotificationCategory.PROMOTIONAL -> dao.incrementVotePromotional(pkg, channel, nowMs)
            NotificationCategory.TRANSACTIONAL -> dao.incrementVoteTransactional(pkg, channel, nowMs)
            NotificationCategory.SYSTEM -> dao.incrementVoteSystem(pkg, channel, nowMs)
            NotificationCategory.SOCIAL_SIGNAL -> dao.incrementVoteSocialSignal(pkg, channel, nowMs)
            NotificationCategory.UNKNOWN -> { /* No vote column for unknown */ }
        }
    }

    /**
     * Checks if the dominant category should be updated based on vote distribution.
     *
     * Rules:
     * - Requires at least [CATEGORY_LOCK_THRESHOLD] total votes.
     * - The leading category must hold > [CATEGORY_LOCK_PERCENT]% of votes.
     * - Never auto-reclassifies to PERSONAL (safety-critical category).
     */
    private suspend fun checkDominantCategory(
        pkg: String,
        channel: String,
        nowMs: Long
    ) {
        val profile = dao.getProfile(pkg, channel) ?: return
        if (profile.totalVotes < CATEGORY_LOCK_THRESHOLD) return

        val votes = mapOf(
            NotificationCategory.PERSONAL to profile.categoryVotePersonal,
            NotificationCategory.ENGAGEMENT_BAIT to profile.categoryVoteEngagementBait,
            NotificationCategory.PROMOTIONAL to profile.categoryVotePromotional,
            NotificationCategory.TRANSACTIONAL to profile.categoryVoteTransactional,
            NotificationCategory.SYSTEM to profile.categoryVoteSystem,
            NotificationCategory.SOCIAL_SIGNAL to profile.categoryVoteSocialSignal
        )

        val (topCategory, topCount) = votes.maxByOrNull { it.value } ?: return
        val voteShare = topCount.toFloat() / profile.totalVotes

        if (voteShare > CATEGORY_LOCK_PERCENT) {
            // Safety: never auto-reclassify to PERSONAL
            if (topCategory == NotificationCategory.PERSONAL &&
                profile.dominantCategory != NotificationCategory.PERSONAL.label) {
                return
            }

            if (profile.dominantCategory != topCategory.label) {
                dao.updateDominantCategory(pkg, channel, topCategory.label, nowMs)
            }
        }
    }

    companion object {
        /** Minimum total votes before dominant category can be locked. */
        const val CATEGORY_LOCK_THRESHOLD = 20

        /** Vote share above which the dominant category is updated (60%). */
        const val CATEGORY_LOCK_PERCENT = 0.60f

        // Removal reason constants matching LithiumNotificationListener
        private const val REASON_CLICK = "click"
        private const val REASON_CANCEL = "cancel"
        private const val REASON_APP_CANCEL_PREFIX = "app_cancel"
    }
}
