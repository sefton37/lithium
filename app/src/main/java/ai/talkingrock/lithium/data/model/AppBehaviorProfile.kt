package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Accumulated engagement profile for a (packageName, channelId) pair.
 *
 * Updated in [ai.talkingrock.lithium.ai.AiAnalysisWorker] Step 6 after each
 * analysis cycle. Counters accumulate across all time — never reset — giving
 * the classifier and suggestion engine a lifetime view of user behavior with
 * each notification source.
 *
 * Keyed on (package_name, channel_id) because the same app can behave very
 * differently across channels: Instagram DMs are personal, Instagram
 * "suggested posts" are engagement bait. An empty string channel_id is the
 * sentinel for notifications with no declared channel.
 */
@Entity(
    tableName = "app_behavior_profiles",
    indices = [Index(value = ["package_name", "channel_id"], unique = true)]
)
data class AppBehaviorProfile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "package_name")
    val packageName: String = "",

    /** Empty string sentinel for notifications with no channelId. */
    @ColumnInfo(name = "channel_id")
    val channelId: String = "",

    /** The category that holds the majority of classification votes. */
    @ColumnInfo(name = "dominant_category")
    val dominantCategory: String = "unknown",

    // ── Engagement counters (accumulated across all time) ──────────────────

    @ColumnInfo(name = "total_received")
    val totalReceived: Int = 0,

    /** Notifications removed with REASON_CLICK (user tapped). */
    @ColumnInfo(name = "total_tapped")
    val totalTapped: Int = 0,

    /** Notifications removed with REASON_CANCEL (user dismissed). */
    @ColumnInfo(name = "total_dismissed")
    val totalDismissed: Int = 0,

    /** Notifications removed by the app itself (REASON_APP_CANCEL). Not a user signal. */
    @ColumnInfo(name = "total_auto_removed")
    val totalAutoRemoved: Int = 0,

    // ── Session counters ──────────────────────────────────────────────────

    @ColumnInfo(name = "total_sessions")
    val totalSessions: Int = 0,

    @ColumnInfo(name = "total_session_ms")
    val totalSessionMs: Long = 0L,

    // ── Classification vote counters ──────────────────────────────────────

    @ColumnInfo(name = "category_vote_personal")
    val categoryVotePersonal: Int = 0,

    @ColumnInfo(name = "category_vote_engagement_bait")
    val categoryVoteEngagementBait: Int = 0,

    @ColumnInfo(name = "category_vote_promotional")
    val categoryVotePromotional: Int = 0,

    @ColumnInfo(name = "category_vote_transactional")
    val categoryVoteTransactional: Int = 0,

    @ColumnInfo(name = "category_vote_system")
    val categoryVoteSystem: Int = 0,

    @ColumnInfo(name = "category_vote_social_signal")
    val categoryVoteSocialSignal: Int = 0,

    // ── User override (future UI, schema reserved now) ────────────────────

    /** User-supplied reclassification. Takes absolute priority over heuristic/model. */
    @ColumnInfo(name = "user_reclassified")
    val userReclassified: String? = null,

    @ColumnInfo(name = "user_reclassified_at_ms")
    val userReclassifiedAtMs: Long? = null,

    // ── Timestamps ────────────────────────────────────────────────────────

    @ColumnInfo(name = "first_seen_ms")
    val firstSeenMs: Long = 0L,

    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long = 0L,

    @ColumnInfo(name = "last_updated_ms")
    val lastUpdatedMs: Long = 0L,

    @ColumnInfo(name = "profile_version")
    val profileVersion: Int = 1
) {
    /** Lifetime tap rate. Returns 0 if no notifications have been received. */
    val lifetimeTapRate: Float
        get() = if (totalReceived > 0) totalTapped.toFloat() / totalReceived else 0f

    /** Lifetime dismiss rate. Returns 0 if no notifications have been received. */
    val lifetimeDismissRate: Float
        get() = if (totalReceived > 0) totalDismissed.toFloat() / totalReceived else 0f

    /** Total classification votes cast. */
    val totalVotes: Int
        get() = categoryVotePersonal + categoryVoteEngagementBait +
                categoryVotePromotional + categoryVoteTransactional +
                categoryVoteSystem + categoryVoteSocialSignal
}
