package ai.talkingrock.lithium.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.SessionRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.model.AppBattleJudgment
import ai.talkingrock.lithium.data.model.AppRanking
import ai.talkingrock.lithium.data.model.ChannelRanking
import ai.talkingrock.lithium.data.model.ImplicitJudgment
import ai.talkingrock.lithium.data.model.QueuedNotification
import ai.talkingrock.lithium.data.model.ScoreQuantiles
import ai.talkingrock.lithium.data.model.TrainingJudgment

/**
 * Room database — the single source of truth for all persisted app data.
 *
 * All data is encrypted at rest via SQLCipher. The database key is derived in
 * [ai.talkingrock.lithium.di.DatabaseModule] using the Android Keystore.
 *
 * WAL mode is enabled in the builder (DatabaseModule) for write concurrency:
 * the NotificationListenerService writes from its callback coroutine while the
 * UI reads on the main thread, both without serialization.
 *
 * Schema exports are written to app/schemas/ (configured via KSP args in
 * app/build.gradle.kts). These files must be committed to version control.
 * They are the migration audit trail.
 *
 * Version history:
 * - 1: Phase 0 scaffold — all 6 tables created.
 * - 2: M2 Correlate — added `is_from_contact` to notifications;
 *      added `package_name` and `duration_ms` to sessions.
 * - 3: Behavioral learning — added `app_behavior_profiles` table.
 * - 4: Tier classification — added `tier` and `tier_reason` to notifications.
 * - 5: Training judgments — added `training_judgments` table.
 * - 6: XP and set-completion — added `xp_awarded`, `set_complete`, `set_bonus_xp` to training_judgments.
 * - 7: Quest tracking — added `quest_id` to training_judgments.
 * - 8: App-battle mode — added `app_rankings` and `app_battle_judgments` tables.
 * - 9: Shade Mode Alpha — added `disposition` TEXT column to notifications.
 * - 10: Channel rankings — added `channel_rankings` table.
 * - 11: Implicit judgments — added `implicit_judgments` table.
 * - 12: Score quantiles — added `score_quantiles` table.
 */
@Database(
    entities = [
        NotificationRecord::class,
        SessionRecord::class,
        Rule::class,
        Report::class,
        Suggestion::class,
        QueuedNotification::class,
        AppBehaviorProfile::class,
        TrainingJudgment::class,
        AppRanking::class,
        AppBattleJudgment::class,
        ChannelRanking::class,
        ImplicitJudgment::class,
        ScoreQuantiles::class,
    ],
    version = 12,
    exportSchema = true
)
abstract class LithiumDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun sessionDao(): SessionDao
    abstract fun ruleDao(): RuleDao
    abstract fun reportDao(): ReportDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun queueDao(): QueueDao
    abstract fun behaviorProfileDao(): AppBehaviorProfileDao
    abstract fun trainingJudgmentDao(): TrainingJudgmentDao
    abstract fun appRankingDao(): AppRankingDao
    abstract fun appBattleJudgmentDao(): AppBattleJudgmentDao
    abstract fun channelRankingDao(): ChannelRankingDao
    abstract fun implicitJudgmentDao(): ImplicitJudgmentDao
    abstract fun scoreQuantilesDao(): ScoreQuantilesDao
}
