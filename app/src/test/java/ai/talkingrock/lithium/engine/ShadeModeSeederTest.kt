package ai.talkingrock.lithium.engine

import android.content.SharedPreferences
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.RuleDao
import ai.talkingrock.lithium.data.db.ShadeModeSeeder
import ai.talkingrock.lithium.data.model.Rule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ShadeModeSeeder].
 *
 * Verifies:
 * - Seeds exactly four rules on first call when [Prefs.SHADE_MODE_SEED_DONE] is false.
 * - Second call (concurrent or sequential) is a no-op — Mutex + flag idempotency.
 * - Self-heal: if seed rules already exist but flag is absent, sets flag without re-seeding.
 * - The four seed rules have the correct tier/action combinations.
 *
 * Fix #3: ShadeModeSeeder now accepts LithiumDatabase for Room withTransaction; the
 * suspend inline extension is mocked via mockkStatic on the Room KTX package.
 */
class ShadeModeSeederTest {

    private lateinit var mockRuleDao: RuleDao
    private lateinit var mockDatabase: LithiumDatabase
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var seeder: ShadeModeSeeder

    // Tracks whether SHADE_MODE_SEED_DONE has been set during the test
    private var seedDone = false

    // Simulated count of seed rules already in DB (for self-heal tests)
    private var existingSeedCount = 0

    @Before
    fun setUp() {
        mockRuleDao = mockk()
        mockEditor = mockk(relaxed = true) {
            every { putBoolean(any(), any()) } returns this
            every { apply() } answers {
                seedDone = true
            }
        }
        mockPrefs = mockk {
            every { getBoolean(Prefs.SHADE_MODE_SEED_DONE, false) } answers { seedDone }
            every { edit() } returns mockEditor
        }

        coEvery { mockRuleDao.insertRule(any()) } returns 1L
        coEvery { mockRuleDao.countSeedRules() } answers { existingSeedCount }

        // LithiumDatabase is only needed to satisfy the constructor; the transactionRunner
        // below bypasses the actual Room withTransaction extension.
        mockDatabase = mockk(relaxed = true)

        seeder = ShadeModeSeeder(mockRuleDao, mockDatabase, mockPrefs)
        // Override transactionRunner: execute block directly (no actual DB transaction needed in tests).
        seeder.transactionRunner = { block -> block() }
    }

    @Test
    fun `seedIfNeeded inserts four rules on first call`() = runBlocking {
        seedDone = false
        existingSeedCount = 0

        seeder.seedIfNeeded()

        coVerify(exactly = 4) { mockRuleDao.insertRule(any()) }
    }

    @Test
    fun `seedIfNeeded is a no-op on second call — idempotent`() = runBlocking {
        seedDone = false
        existingSeedCount = 0

        seeder.seedIfNeeded()
        seeder.seedIfNeeded()

        // Only 4 inserts total — the second call sees SHADE_MODE_SEED_DONE = true and returns early
        coVerify(exactly = 4) { mockRuleDao.insertRule(any()) }
    }

    @Test
    fun `seedIfNeeded does not insert rules when already seeded`() = runBlocking {
        seedDone = true
        existingSeedCount = 4

        seeder.seedIfNeeded()

        coVerify(exactly = 0) { mockRuleDao.insertRule(any()) }
    }

    /**
     * Fix #3 self-heal: if the flag is absent but seed rules already exist in the DB
     * (crash between transaction commit and SharedPreferences write), no re-seeding occurs.
     */
    @Test
    fun `seedIfNeeded self-heals when flag absent but seed rules already exist`() = runBlocking {
        seedDone = false
        existingSeedCount = 4   // rules exist, flag was lost in a crash

        seeder.seedIfNeeded()

        // No inserts — existing rules detected
        coVerify(exactly = 0) { mockRuleDao.insertRule(any()) }
        // Flag is set to prevent future self-heal checks
        verify { mockEditor.putBoolean(Prefs.SHADE_MODE_SEED_DONE, true) }
    }

    @Test
    fun `seed rules cover all four tiers with correct actions`() = runBlocking {
        seedDone = false
        existingSeedCount = 0
        val insertedRules = mutableListOf<Rule>()
        coEvery { mockRuleDao.insertRule(capture(insertedRules)) } returns 1L

        seeder.seedIfNeeded()

        assertEquals("Expected 4 seed rules", 4, insertedRules.size)

        val tier3Rule = insertedRules.first { it.conditionJson.contains("\"tier\":3") }
        val tier2Rule = insertedRules.first { it.conditionJson.contains("\"tier\":2") }
        val tier1Rule = insertedRules.first { it.conditionJson.contains("\"tier\":1") }
        val tier0Rule = insertedRules.first { it.conditionJson.contains("\"tier\":0") }

        assertEquals("Tier 3 should ALLOW", "allow", tier3Rule.action)
        assertEquals("Tier 2 should QUEUE", "queue", tier2Rule.action)
        assertEquals("Tier 1 should SUPPRESS", "suppress", tier1Rule.action)
        assertEquals("Tier 0 should SUPPRESS", "suppress", tier0Rule.action)
    }

    @Test
    fun `seed rules all have approved status`() = runBlocking {
        seedDone = false
        existingSeedCount = 0
        val insertedRules = mutableListOf<Rule>()
        coEvery { mockRuleDao.insertRule(capture(insertedRules)) } returns 1L

        seeder.seedIfNeeded()

        assertTrue("All seed rules must be approved",
            insertedRules.all { it.status == "approved" })
    }

    @Test
    fun `seed rules all have seed source`() = runBlocking {
        seedDone = false
        existingSeedCount = 0
        val insertedRules = mutableListOf<Rule>()
        coEvery { mockRuleDao.insertRule(capture(insertedRules)) } returns 1L

        seeder.seedIfNeeded()

        assertTrue("All seed rules must have source='seed'",
            insertedRules.all { it.source == "seed" })
    }

    @Test
    fun `seed rules use tier_match condition JSON discriminator`() = runBlocking {
        seedDone = false
        existingSeedCount = 0
        val insertedRules = mutableListOf<Rule>()
        coEvery { mockRuleDao.insertRule(capture(insertedRules)) } returns 1L

        seeder.seedIfNeeded()

        assertTrue("All rules must use tier_match discriminator",
            insertedRules.all { it.conditionJson.contains("\"type\":\"tier_match\"") })
    }

    @Test
    fun `seedIfNeeded sets SHADE_MODE_SEED_DONE on completion`() = runBlocking {
        seedDone = false
        existingSeedCount = 0

        seeder.seedIfNeeded()

        verify { mockEditor.putBoolean(Prefs.SHADE_MODE_SEED_DONE, true) }
        verify { mockEditor.apply() }
    }
}
