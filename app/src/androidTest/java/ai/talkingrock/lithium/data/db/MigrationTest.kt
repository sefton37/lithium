package ai.talkingrock.lithium.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.talkingrock.lithium.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for [LithiumDatabase] v1→v8.
 *
 * Uses [MigrationTestHelper] with the exported schema JSON files at `app/schemas/`.
 * These tests must run as instrumented tests (on device or emulator) because
 * [MigrationTestHelper] requires a file-backed SQLite database.
 *
 * No SQLCipher is used here — migration SQL is identical between plain SQLite and
 * SQLCipher. The passphrase only affects encryption at rest, not schema structure.
 *
 * Note: If the content-guard hook trips on the identityHash values in the schema
 * files, commit with --no-verify (only for Room schema hashes — see TESTING_STRATEGY).
 *
 * Method names use camelCase (not backtick-with-spaces) to ensure D8 compatibility
 * at minSdk=29 (DEX version 035 rejects spaces in method names).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    companion object {
        private const val TEST_DB = "migration-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LithiumDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    // ── v1 → v2 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate1to2_notificationsGainsIsFromContact() {
        helper.createDatabase(TEST_DB, 1)
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, DatabaseModule.MIGRATION_1_2)
        val cursor = db.query("SELECT is_from_contact FROM notifications LIMIT 0")
        assertNotNull("is_from_contact column should exist", cursor)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate1to2_sessionsGainsPackageNameAndDurationMs() {
        helper.createDatabase(TEST_DB, 1)
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, DatabaseModule.MIGRATION_1_2)
        val cursor = db.query("SELECT package_name, duration_ms FROM sessions LIMIT 0")
        assertNotNull("sessions should have new columns", cursor)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate1to2_existingNotificationRowsSurviveWithIsFromContactZero() {
        val v1Db = helper.createDatabase(TEST_DB, 1)
        v1Db.execSQL(
            "INSERT INTO notifications (package_name, posted_at_ms, is_ongoing) VALUES ('com.test.app', 1000, 0)"
        )
        v1Db.close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, DatabaseModule.MIGRATION_1_2)
        val cursor = db.query("SELECT is_from_contact FROM notifications WHERE package_name='com.test.app'")
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("existing row should have is_from_contact=0", 0, cursor.getInt(0))
        cursor.close()
        db.close()
    }

    // ── v2 → v3 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate2to3_appBehaviorProfilesTableCreated() {
        helper.createDatabase(TEST_DB, 2)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true, DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3
        )
        val cursor = db.query("SELECT * FROM app_behavior_profiles LIMIT 0")
        assertNotNull("app_behavior_profiles table should exist", cursor)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate2to3_uniqueIndexOnPackageNameAndChannelId() {
        helper.createDatabase(TEST_DB, 2)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true, DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3
        )
        // Insert a row, then try to insert a duplicate — should fail
        db.execSQL(
            """INSERT INTO app_behavior_profiles (package_name, channel_id, dominant_category,
               total_received, total_tapped, total_dismissed, total_auto_removed,
               total_sessions, total_session_ms, category_vote_personal,
               category_vote_engagement_bait, category_vote_promotional,
               category_vote_transactional, category_vote_system, category_vote_social_signal,
               first_seen_ms, last_seen_ms, last_updated_ms, profile_version)
               VALUES ('com.test', 'ch1', 'personal', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1)"""
        )
        var threw = false
        try {
            db.execSQL(
                """INSERT INTO app_behavior_profiles (package_name, channel_id, dominant_category,
                   total_received, total_tapped, total_dismissed, total_auto_removed,
                   total_sessions, total_session_ms, category_vote_personal,
                   category_vote_engagement_bait, category_vote_promotional,
                   category_vote_transactional, category_vote_system, category_vote_social_signal,
                   first_seen_ms, last_seen_ms, last_updated_ms, profile_version)
                   VALUES ('com.test', 'ch1', 'personal', 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 1)"""
            )
        } catch (e: Exception) {
            threw = true
        }
        assertEquals("duplicate (package_name, channel_id) should be rejected", true, threw)
        db.close()
    }

    // ── v3 → v4 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate3to4_notificationsGainsTierColumn() {
        helper.createDatabase(TEST_DB, 3)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 4, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4
        )
        val cursor = db.query("SELECT tier FROM notifications LIMIT 0")
        assertNotNull("tier column should exist", cursor)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate3to4_notificationsGainsTierReasonColumn() {
        helper.createDatabase(TEST_DB, 3)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 4, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4
        )
        val cursor = db.query("SELECT tier_reason FROM notifications LIMIT 0")
        assertNotNull("tier_reason column should exist", cursor)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate3to4_existingRowsGetDefaultTierAndNullReason() {
        val v3Db = helper.createDatabase(TEST_DB, 3)
        v3Db.execSQL(
            "INSERT INTO notifications (package_name, posted_at_ms, is_ongoing, is_from_contact) VALUES ('com.test.app', 1000, 0, 0)"
        )
        v3Db.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 4, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4
        )
        val cursor = db.query("SELECT tier, tier_reason FROM notifications WHERE package_name='com.test.app'")
        cursor.moveToFirst()
        assertEquals("existing row tier should default to 2", 2, cursor.getInt(0))
        assertEquals("existing row tier_reason should be NULL", true, cursor.isNull(1))
        cursor.close()
        db.close()
    }

    // ── v4 → v5 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate4to5_trainingJudgmentsTableCreated() {
        helper.createDatabase(TEST_DB, 4)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5
        )
        val cursor = db.query("SELECT * FROM training_judgments LIMIT 0")
        assertNotNull("training_judgments table should exist", cursor)
        cursor.close()
        db.close()
    }

    // ── v5 → v6 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate5to6_trainingJudgmentsGainsXpColumns() {
        helper.createDatabase(TEST_DB, 5)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 6, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6
        )
        val cursor = db.query("SELECT xp_awarded, set_complete, set_bonus_xp FROM training_judgments LIMIT 0")
        assertNotNull("XP columns should exist", cursor)
        cursor.close()
        db.close()
    }

    // ── v6 → v7 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate6to7_trainingJudgmentsGainsQuestId() {
        val v6Db = helper.createDatabase(TEST_DB, 6)
        // Insert a row at v6 (no quest_id column yet)
        v6Db.execSQL(
            """INSERT INTO training_judgments
               (left_notification_id, right_notification_id, choice,
                left_tier, right_tier, created_at_ms, xp_awarded, set_complete, set_bonus_xp)
               VALUES (1, 2, 'left', 2, 2, 1000, 7, 0, 0)"""
        )
        v6Db.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 7, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6, DatabaseModule.MIGRATION_6_7
        )
        val cursor = db.query("SELECT quest_id FROM training_judgments")
        cursor.moveToFirst()
        assertEquals("existing judgment should get quest_id='free_play'", "free_play", cursor.getString(0))
        cursor.close()
        db.close()
    }

    // ── v7 → v8 ──────────────────────────────────────────────────────────────

    @Test
    fun migrate7to8_appRankingsAndBattleJudgmentsTablesCreated() {
        helper.createDatabase(TEST_DB, 7)
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 8, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6, DatabaseModule.MIGRATION_6_7,
            DatabaseModule.MIGRATION_7_8
        )
        val rankCursor = db.query("SELECT * FROM app_rankings LIMIT 0")
        assertNotNull("app_rankings table should exist", rankCursor)
        rankCursor.close()

        val battleCursor = db.query("SELECT * FROM app_battle_judgments LIMIT 0")
        assertNotNull("app_battle_judgments table should exist", battleCursor)
        battleCursor.close()
        db.close()
    }

    // ── Full chain: v1 → v8 ────────────────────────────────────────────────

    @Test
    fun fullChain1to8_representativeDataSurvivesAllMigrations() {
        val v1Db = helper.createDatabase(TEST_DB, 1)
        v1Db.execSQL(
            "INSERT INTO notifications (package_name, posted_at_ms, is_ongoing) VALUES ('com.chain.test', 9999, 0)"
        )
        v1Db.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 8, true,
            DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6, DatabaseModule.MIGRATION_6_7,
            DatabaseModule.MIGRATION_7_8
        )
        val cursor = db.query(
            "SELECT package_name, posted_at_ms, is_from_contact, tier, tier_reason FROM notifications WHERE package_name='com.chain.test'"
        )
        assertEquals("original row survives full migration chain", 1, cursor.count)
        cursor.moveToFirst()
        assertEquals("com.chain.test", cursor.getString(0))
        assertEquals(9999L, cursor.getLong(1))
        assertEquals("is_from_contact defaults to 0", 0, cursor.getInt(2))
        assertEquals("tier defaults to 2", 2, cursor.getInt(3))
        assertEquals("tier_reason is null", true, cursor.isNull(4))
        cursor.close()
        db.close()
    }

    @Test
    fun fullChain4to8_v4RowsSurviveToV8() {
        val v4Db = helper.createDatabase(TEST_DB, 4)
        v4Db.execSQL(
            """INSERT INTO notifications (package_name, posted_at_ms, is_ongoing, is_from_contact, tier, tier_reason)
               VALUES ('com.v4.test', 5000, 0, 0, 2, NULL)"""
        )
        v4Db.close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 8, true,
            DatabaseModule.MIGRATION_4_5, DatabaseModule.MIGRATION_5_6, DatabaseModule.MIGRATION_6_7,
            DatabaseModule.MIGRATION_7_8
        )
        val cursor = db.query(
            "SELECT tier, tier_reason FROM notifications WHERE package_name='com.v4.test'"
        )
        assertEquals("v4 row survives to v8", 1, cursor.count)
        cursor.moveToFirst()
        assertEquals("tier should still be 2", 2, cursor.getInt(0))
        assertEquals("tier_reason should still be NULL", true, cursor.isNull(1))
        cursor.close()
        db.close()
    }
}
