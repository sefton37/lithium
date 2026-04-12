package ai.talkingrock.lithium.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.RuleDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.repository.RuleRepository
import ai.talkingrock.lithium.engine.RuleAction
import ai.talkingrock.lithium.engine.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for the full rule lifecycle: insert Rule via RuleDao →
 * RuleRepository StateFlow updates → RuleEngine.evaluate() returns correct action.
 *
 * Uses an in-memory Room DB (no SQLCipher, no Hilt). This covers the real DB ↔
 * repository ↔ engine path that the notification listener uses in production.
 *
 * Phase 3 deliverable per TESTING_STRATEGY.md §2.2 (RuleEngineIntegrationTest).
 *
 * Method names use camelCase to ensure DEX compatibility at minSdk=29.
 */
@RunWith(AndroidJUnit4::class)
class RuleEngineIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: LithiumDatabase
    private lateinit var ruleDao: RuleDao
    private lateinit var ruleRepository: RuleRepository
    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LithiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleDao = db.ruleDao()
        ruleRepository = RuleRepository(ruleDao)
        engine = RuleEngine(ruleRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Waits (up to 5 seconds) until the RuleRepository StateFlow has the expected
     * number of approved rules. The StateFlow is backed by Room's reactive DB query,
     * which emits asynchronously on the IO dispatcher. This helper avoids brittle
     * fixed-delay sleeps.
     */
    /**
     * Waits up to 5 seconds (real wall-clock time) for the given repository's approvedRules
     * StateFlow to reach [expectedCount]. Must use real time (not runTest virtual time) because
     * RuleRepository uses Dispatchers.IO which doesn't participate in virtual time.
     */
    private suspend fun awaitApprovedRuleCount(expectedCount: Int) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                ruleRepository.approvedRules
                    .filter { it.size == expectedCount }
                    .first()
            }
        }
    }

    private suspend fun insertApprovedRule(
        conditionJson: String,
        action: String = "suppress",
        name: String = "Test rule",
        createdAtMs: Long = System.currentTimeMillis(),
    ): Long = ruleDao.insertRule(
        Rule(
            name = name,
            conditionJson = conditionJson,
            action = action,
            status = "approved",
            createdAtMs = createdAtMs,
            source = "ai",
        )
    )

    private fun record(
        pkg: String = "com.test.app",
        channel: String? = null,
        category: String? = null,
        isFromContact: Boolean = false,
    ) = NotificationRecord(
        packageName = pkg,
        postedAtMs = System.currentTimeMillis(),
        title = "Test",
        text = "Content",
        channelId = channel,
        category = category,
        isFromContact = isFromContact,
    )

    // ── 1. No approved rules → ALLOW ─────────────────────────────────────────

    @Test
    fun noApprovedRules_returnsAllow() = runTest {
        awaitApprovedRuleCount(0)
        assertEquals(RuleAction.ALLOW, engine.evaluate(record("com.any.app")))
    }

    // ── 2. Insert approved PackageMatch → SUPPRESS for matching package ───────

    @Test
    fun insertApprovedPackageMatchRule_suppressesMatchingPackage() = runTest {
        insertApprovedRule("""{"type":"package_match","packageName":"com.linkedin.android"}""")
        awaitApprovedRuleCount(1)

        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record("com.linkedin.android")))
    }

    // ── 3. Insert approved PackageMatch → ALLOW for different package ─────────

    @Test
    fun insertApprovedPackageMatchRule_allowsDifferentPackage() = runTest {
        insertApprovedRule("""{"type":"package_match","packageName":"com.linkedin.android"}""")
        awaitApprovedRuleCount(1)

        assertEquals(RuleAction.ALLOW, engine.evaluate(record("com.whatsapp")))
    }

    // ── 4. Delete rule → cache rebuilds → ALLOW thereafter ───────────────────

    @Test
    fun deleteRule_cacheRebuilds_returnsAllow() = runTest {
        val ruleId = insertApprovedRule("""{"type":"package_match","packageName":"com.temu"}""")
        awaitApprovedRuleCount(1)
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record("com.temu")))

        ruleDao.deleteById(ruleId)
        awaitApprovedRuleCount(0)

        assertEquals(RuleAction.ALLOW, engine.evaluate(record("com.temu")))
    }

    // ── 5. Rule with status='disabled' → ALLOW ───────────────────────────────

    @Test
    fun disabledRule_returnsAllow() = runTest {
        ruleDao.insertRule(Rule(
            name = "Disabled rule",
            conditionJson = """{"type":"package_match","packageName":"com.reddit.frontpage"}""",
            action = "suppress",
            status = "disabled",
            createdAtMs = System.currentTimeMillis(),
            source = "ai",
        ))
        // Disabled rules are not in approvedRules — confirm count stays at 0
        awaitApprovedRuleCount(0)

        assertEquals(RuleAction.ALLOW, engine.evaluate(record("com.reddit.frontpage")))
    }

    // ── 6. Two rules, first matches → first-match-wins ───────────────────────

    @Test
    fun twoRules_firstMatchWins() = runTest {
        val t = System.currentTimeMillis()
        ruleDao.insertRule(Rule(
            name = "Suppress LinkedIn",
            conditionJson = """{"type":"package_match","packageName":"com.linkedin.android"}""",
            action = "suppress",
            status = "approved",
            createdAtMs = t,
            source = "ai",
        ))
        ruleDao.insertRule(Rule(
            name = "Queue LinkedIn (should not fire)",
            conditionJson = """{"type":"package_match","packageName":"com.linkedin.android"}""",
            action = "queue",
            status = "approved",
            createdAtMs = t + 1,
            source = "ai",
        ))
        awaitApprovedRuleCount(2)

        // First-match-wins: oldest rule (suppress) fires first
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record("com.linkedin.android")))
    }

    // ── 7. createFromSuggestion path → rule fires immediately ────────────────

    @Test
    fun createFromSuggestion_approvedRuleFiresImmediately() = runTest {
        awaitApprovedRuleCount(0)
        // Simulate what RuleRepository.createFromSuggestion does
        ruleDao.insertRule(Rule(
            name = "Amazon sends promotional deals",
            conditionJson = """{"type":"package_match","packageName":"com.amazon.mShop.android.shopping"}""",
            action = "suppress",
            status = "approved",
            createdAtMs = System.currentTimeMillis(),
            source = "ai",
        ))
        awaitApprovedRuleCount(1)

        assertEquals(
            RuleAction.SUPPRESS,
            engine.evaluate(record("com.amazon.mShop.android.shopping"))
        )
    }

    // ── 8. CompositeAnd: both conditions must match ───────────────────────────

    @Test
    fun compositeAndRule_bothConditionsMustMatch() = runTest {
        insertApprovedRule(
            """{"type":"composite_and","conditions":[
                {"type":"package_match","packageName":"com.Slack"},
                {"type":"channel_match","packageName":null,"channelId":"promo"}
            ]}""",
            action = "suppress",
        )
        awaitApprovedRuleCount(1)

        // Slack + promo channel → SUPPRESS
        assertEquals(
            RuleAction.SUPPRESS,
            engine.evaluate(record("com.Slack", channel = "promo"))
        )

        // Slack + different channel → ALLOW (CompositeAnd fails)
        assertEquals(
            RuleAction.ALLOW,
            engine.evaluate(record("com.Slack", channel = "messages"))
        )

        // Different package + promo channel → ALLOW
        assertEquals(
            RuleAction.ALLOW,
            engine.evaluate(record("com.other.app", channel = "promo"))
        )
    }

    // ── 9. All-migrations-then-ingest: data survives migration chain ──────────
    // (The migration tests are in MigrationTest.kt; this test verifies that
    //  the fully-migrated schema can accept real DAO operations.)

    @Test
    fun inMemoryDb_supportsAllDaoOperationsAfterMigration() = runTest {
        // The in-memory DB in setUp() already creates the v8 schema.
        val notificationDao = db.notificationDao()
        val id = notificationDao.insertOrReplace(NotificationRecord(
            packageName = "com.post.migration.test",
            postedAtMs = System.currentTimeMillis() - 1_000,
            title = "Post-migration test",
            text = "Inserted after schema creation",
            tier = 2,
            tierReason = "default",
        ))
        val retrieved = notificationDao.getById(id)
        assertEquals("com.post.migration.test", retrieved!!.packageName)
        assertEquals("default", retrieved.tierReason)

        // Insert a rule and verify RuleEngine picks it up
        insertApprovedRule("""{"type":"package_match","packageName":"com.post.migration.test"}""")
        awaitApprovedRuleCount(1)

        assertEquals(
            RuleAction.SUPPRESS,
            engine.evaluate(record("com.post.migration.test"))
        )
    }
}
