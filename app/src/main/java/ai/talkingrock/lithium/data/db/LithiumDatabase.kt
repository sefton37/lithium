package ai.talkingrock.lithium.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.SessionRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.model.QueuedNotification
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
        TrainingJudgment::class
    ],
    version = 5,
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
}
