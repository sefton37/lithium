package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Query
import ai.talkingrock.lithium.data.model.AppBehaviorProfile

/**
 * DAO for [AppBehaviorProfile].
 *
 * Uses raw SQL upserts (INSERT ... ON CONFLICT DO UPDATE) to atomically
 * increment counters without read-then-write races. Requires SQLite >= 3.24
 * (SQLCipher 4.5.x ships SQLite 3.39+).
 */
@Dao
interface AppBehaviorProfileDao {

    /**
     * Upserts a single notification's engagement data into the profile.
     *
     * @param pkg Package name.
     * @param channel Channel ID (empty string for null channels).
     * @param tapped 1 if the notification was tapped, 0 otherwise.
     * @param dismissed 1 if the notification was dismissed, 0 otherwise.
     * @param autoRemoved 1 if the app cancelled the notification, 0 otherwise.
     * @param categoryLabel The classification label assigned to this notification.
     * @param nowMs Current timestamp.
     */
    @Query("""
        INSERT INTO app_behavior_profiles (
            package_name, channel_id, total_received, total_tapped, total_dismissed,
            total_auto_removed, dominant_category, total_sessions, total_session_ms,
            category_vote_personal, category_vote_engagement_bait, category_vote_promotional,
            category_vote_transactional, category_vote_system, category_vote_social_signal,
            first_seen_ms, last_seen_ms, last_updated_ms, profile_version
        ) VALUES (
            :pkg, :channel, 1, :tapped, :dismissed, :autoRemoved, :categoryLabel,
            0, 0,
            0, 0, 0, 0, 0, 0,
            :nowMs, :nowMs, :nowMs, 1
        )
        ON CONFLICT(package_name, channel_id) DO UPDATE SET
            total_received     = total_received + 1,
            total_tapped       = total_tapped + :tapped,
            total_dismissed    = total_dismissed + :dismissed,
            total_auto_removed = total_auto_removed + :autoRemoved,
            last_seen_ms       = :nowMs,
            last_updated_ms    = :nowMs
    """)
    suspend fun incrementStats(
        pkg: String,
        channel: String,
        tapped: Int,
        dismissed: Int,
        autoRemoved: Int,
        categoryLabel: String,
        nowMs: Long
    )

    /** Increments the vote counter for 'personal' classification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET category_vote_personal = category_vote_personal + 1, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun incrementVotePersonal(pkg: String, channel: String, nowMs: Long)

    /** Increments the vote counter for 'engagement_bait' classification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET category_vote_engagement_bait = category_vote_engagement_bait + 1, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun incrementVoteEngagementBait(pkg: String, channel: String, nowMs: Long)

    /** Increments the vote counter for 'promotional' classification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET category_vote_promotional = category_vote_promotional + 1, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun incrementVotePromotional(pkg: String, channel: String, nowMs: Long)

    /** Increments the vote counter for 'transactional' classification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET category_vote_transactional = category_vote_transactional + 1, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun incrementVoteTransactional(pkg: String, channel: String, nowMs: Long)

    /** Increments the vote counter for 'system' classification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET category_vote_system = category_vote_system + 1, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun incrementVoteSystem(pkg: String, channel: String, nowMs: Long)

    /** Increments the vote counter for 'social_signal' classification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET category_vote_social_signal = category_vote_social_signal + 1, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun incrementVoteSocialSignal(pkg: String, channel: String, nowMs: Long)

    /** Adds session stats to the profile (sessions are attributed at package level). */
    @Query("""
        INSERT INTO app_behavior_profiles (
            package_name, channel_id, total_sessions, total_session_ms,
            total_received, total_tapped, total_dismissed, total_auto_removed,
            dominant_category, category_vote_personal, category_vote_engagement_bait,
            category_vote_promotional, category_vote_transactional, category_vote_system,
            category_vote_social_signal, first_seen_ms, last_seen_ms, last_updated_ms,
            profile_version
        ) VALUES (
            :pkg, '', :sessionCount, :sessionMs,
            0, 0, 0, 0,
            'unknown', 0, 0, 0, 0, 0, 0,
            :nowMs, :nowMs, :nowMs, 1
        )
        ON CONFLICT(package_name, channel_id) DO UPDATE SET
            total_sessions  = total_sessions + :sessionCount,
            total_session_ms = total_session_ms + :sessionMs,
            last_updated_ms = :nowMs
    """)
    suspend fun addSessionStats(pkg: String, sessionCount: Int, sessionMs: Long, nowMs: Long)

    /** Updates the dominant category when vote lock threshold is met. */
    @Query("""
        UPDATE app_behavior_profiles
        SET dominant_category = :category, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun updateDominantCategory(pkg: String, channel: String, category: String, nowMs: Long)

    /** Returns the profile for a specific (package, channel) pair, or null. */
    @Query("SELECT * FROM app_behavior_profiles WHERE package_name = :pkg AND channel_id = :channel")
    suspend fun getProfile(pkg: String, channel: String): AppBehaviorProfile?

    /** Returns all profiles. Used to build the profile map for classification. */
    @Query("SELECT * FROM app_behavior_profiles")
    suspend fun getAllProfiles(): List<AppBehaviorProfile>

    /** Returns all profiles for a given package (all channels). */
    @Query("SELECT * FROM app_behavior_profiles WHERE package_name = :pkg")
    suspend fun getProfilesForPackage(pkg: String): List<AppBehaviorProfile>

    /** Stores a user-initiated reclassification. */
    @Query("""
        UPDATE app_behavior_profiles
        SET user_reclassified = :category, user_reclassified_at_ms = :nowMs, last_updated_ms = :nowMs
        WHERE package_name = :pkg AND channel_id = :channel
    """)
    suspend fun setUserReclassification(pkg: String, channel: String, category: String, nowMs: Long)

    /** Delete all profiles. Used by purge-all-data. */
    @Query("DELETE FROM app_behavior_profiles")
    suspend fun deleteAll()
}
