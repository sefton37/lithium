package ai.talkingrock.lithium.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dagger.hilt.InstallIn
import ai.talkingrock.lithium.data.db.AppBattleJudgmentDao
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.AppRankingDao
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.RuleDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import javax.inject.Singleton

/**
 * Replaces [DatabaseModule] in @HiltAndroidTest classes.
 *
 * Uses a plain in-memory Room database (no SQLCipher, no Keystore) so that
 * Compose UI tests can run on a real device without needing the full encryption
 * stack. The passphrase path is tested separately in instrumented migration tests.
 *
 * @UninstallModules annotation is placed on individual test classes that need it,
 * not here — this module is installed globally into SingletonComponent.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(): LithiumDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return Room.inMemoryDatabaseBuilder(context, LithiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Provides
    fun provideNotificationDao(db: LithiumDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideSessionDao(db: LithiumDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideRuleDao(db: LithiumDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideReportDao(db: LithiumDatabase): ReportDao = db.reportDao()

    @Provides
    fun provideSuggestionDao(db: LithiumDatabase): SuggestionDao = db.suggestionDao()

    @Provides
    fun provideQueueDao(db: LithiumDatabase): QueueDao = db.queueDao()

    @Provides
    fun provideBehaviorProfileDao(db: LithiumDatabase): AppBehaviorProfileDao =
        db.behaviorProfileDao()

    @Provides
    fun provideTrainingJudgmentDao(db: LithiumDatabase): TrainingJudgmentDao =
        db.trainingJudgmentDao()

    @Provides
    fun provideAppRankingDao(db: LithiumDatabase): AppRankingDao = db.appRankingDao()

    @Provides
    fun provideAppBattleJudgmentDao(db: LithiumDatabase): AppBattleJudgmentDao =
        db.appBattleJudgmentDao()
}
