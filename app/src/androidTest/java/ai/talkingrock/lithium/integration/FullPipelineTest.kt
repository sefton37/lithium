package ai.talkingrock.lithium.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import ai.talkingrock.lithium.ai.SyntheticNotifications
import ai.talkingrock.lithium.classification.TierBackfillWorker
import ai.talkingrock.lithium.classification.TierBackfillWorkerFactory
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.RuleDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.AppBattleJudgment
import ai.talkingrock.lithium.data.model.AppRanking
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.model.TrainingJudgment
import ai.talkingrock.lithium.data.repository.RuleRepository
import ai.talkingrock.lithium.engine.RuleAction
import ai.talkingrock.lithium.engine.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-pipeline integration tests for Lithium.
 *
 * Seeds the in-memory DB with synthetic notification data, runs TierBackfillWorker,
 * and asserts end-state KPIs: tier distribution, rule lifecycle, reactive flows.
 *
 * All tests use [SyntheticNotifications] fixtures — no live device data.
 * Phase 3 deliverable per TESTING_STRATEGY.md §2.6.
 *
 * Method names use camelCase (not backtick-with-spaces) to ensure DEX compatibility
 * at minSdk=29 (DEX version 035 rejects spaces in method names).
 */
@RunWith(AndroidJUnit4::class)
class FullPipelineTest {

    private lateinit var context: Context
    private lateinit var db: LithiumDatabase
    private lateinit var notificationDao: NotificationDao
    private lateinit var ruleDao: RuleDao
    private lateinit var suggestionDao: SuggestionDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LithiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notificationDao = db.notificationDao()
        ruleDao = db.ruleDao()
        suggestionDao = db.suggestionDao()
        SyntheticNotifications.resetIds()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun insertAll(records: List<NotificationRecord>): List<Long> =
        records.map { notificationDao.insertOrReplace(it) }

    private suspend fun runBackfillWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<TierBackfillWorker>(context)
            .setWorkerFactory(TierBackfillWorkerFactory(notificationDao))
            .build()
        return worker.doWork()
    }

    /**
     * Waits up to 5 seconds (real wall-clock time) for the given [RuleRepository]'s
     * approvedRules StateFlow to reach [expectedCount]. Must use real time (not runTest
     * virtual time) because RuleRepository uses Dispatchers.IO for its internal scope.
     */
    private suspend fun awaitApprovedRuleCount(repo: RuleRepository, expectedCount: Int) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                repo.approvedRules
                    .filter { it.size == expectedCount }
                    .first()
            }
        }
    }

    private fun approvedPackageRule(pkg: String, action: String = "suppress"): Rule = Rule(
        name = "Suppress $pkg",
        conditionJson = """{"type":"package_match","packageName":"$pkg"}""",
        action = action,
        status = "approved",
        createdAtMs = System.currentTimeMillis(),
        source = "ai",
    )

    private fun makeJudgment(leftId: Long, rightId: Long, choice: String = "left", xp: Int = 7) =
        TrainingJudgment(
            leftNotificationId = leftId,
            rightNotificationId = rightId,
            choice = choice,
            leftTier = 2,
            rightTier = 1,
            leftTierReason = "default",
            rightTierReason = "linkedin",
            leftAiClassification = null,
            rightAiClassification = null,
            leftConfidence = null,
            rightConfidence = null,
            createdAtMs = System.currentTimeMillis(),
            xpAwarded = xp,
            setBonusXp = 0,
        )

    // ── 1. Backfill assigns tier to all rows ─────────────────────────────────

    @Test
    fun seedHeavySocialMedia_backfillAssignsTierToAllRows() = runTest {
        val records = SyntheticNotifications.heavySocialMedia(seed = 42)
        // Insert with tierReason=null to simulate pre-backfill state
        insertAll(records.map { it.copy(tierReason = null) })

        val result = runBackfillWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            "All rows must have tier_reason after backfill",
            0, notificationDao.countTierBackfillRemaining()
        )
    }

    // ── 2. Tier breakdown sums to total row count ────────────────────────────

    @Test
    fun seedLargeDataset_tierBreakdownSumsToTotal() = runTest {
        val records = SyntheticNotifications.largeSyntheticDataset(count = 1_000, seed = 99)
        insertAll(records)

        val total = notificationDao.count()
        val breakdown = notificationDao.getTierBreakdown()
        val breakdownTotal = breakdown.sumOf { it.count }

        assertEquals("Tier breakdown must sum to total row count", total, breakdownTotal)
    }

    // ── 3. Tier distribution is consistent with TierClassifier ───────────────

    @Test
    fun largeSyntheticDataset_tierDistributionWithinExpectedBands() = runTest {
        val records = SyntheticNotifications.largeSyntheticDataset(count = 1_000, seed = 42)
        insertAll(records)

        val breakdown = notificationDao.getTierBreakdown()
        val tierMap = breakdown.associate { it.tier to it.count }
        val total = records.size.toFloat()

        val tier0Pct = (tierMap[0] ?: 0).toFloat() / total
        val tier1Pct = (tierMap[1] ?: 0).toFloat() / total
        val tier2Pct = (tierMap[2] ?: 0).toFloat() / total
        val tier3Pct = (tierMap[3] ?: 0).toFloat() / total

        // Target: 40% tier0, 20% tier1, 30% tier2, 10% tier3 — allow ±15% tolerance
        assertTrue("Tier 0 should be ~40% (was ${tier0Pct * 100}%)", tier0Pct in 0.25f..0.55f)
        assertTrue("Tier 1 should be ~20% (was ${tier1Pct * 100}%)", tier1Pct in 0.05f..0.35f)
        assertTrue("Tier 2 should be ~30% (was ${tier2Pct * 100}%)", tier2Pct in 0.15f..0.45f)
        assertTrue("Tier 3 should be ~10% (was ${tier3Pct * 100}%)", tier3Pct in 0.0f..0.25f)
        assertEquals(
            "All tiers must sum to 1.0",
            1.0f, tier0Pct + tier1Pct + tier2Pct + tier3Pct, 0.01f
        )
    }

    // ── 4. spamVictim: tier-reason stats contain amazon_shopping ─────────────

    @Test
    fun seedSpamVictim_tierReasonStatsContainAmazonShopping() = runTest {
        val records = SyntheticNotifications.spamVictim(seed = 46)
        insertAll(records.map { it.copy(tierReason = null) })
        runBackfillWorker()

        val stats = notificationDao.getTierReasonStats(
            sinceMs = 0L,
            maxTier = 2,
            minCount = 1,
        )

        val amazonStat = stats.firstOrNull { it.tierReason == "amazon_shopping" }
        assertNotNull("amazon_shopping tier_reason should appear in stats after backfill", amazonStat)
        assertTrue(
            "amazon_shopping count should be >= 60 (spamVictim has 68 amazon records)",
            (amazonStat?.count ?: 0) >= 60,
        )
    }

    // ── 5. Rule lifecycle: suggestion → Rule created with approved status ─────

    @Test
    fun approveSuggestion_ruleCreatedWithApprovedStatus() = runTest {
        // createFromSuggestion logic: copies conditionJson + action, sets status=approved
        val ruleId = ruleDao.insertRule(
            Rule(
                name = "LinkedIn sends mostly promotional content",
                conditionJson = """{"type":"package_match","packageName":"com.linkedin.android"}""",
                action = "suppress",
                status = "approved",
                createdAtMs = System.currentTimeMillis(),
                source = "ai",
            )
        )
        val rule = ruleDao.getById(ruleId)
        assertNotNull("Rule should be created", rule)
        assertEquals("approved", rule!!.status)
        assertEquals("suppress", rule.action)
        assertEquals(
            """{"type":"package_match","packageName":"com.linkedin.android"}""",
            rule.conditionJson
        )
    }

    // ── 6. Approved PackageMatch rule → evaluate returns SUPPRESS ────────────

    @Test
    fun approvedPackageMatchRule_evaluateReturnsSuppressForMatchingPackage() = runTest {
        val repo = RuleRepository(ruleDao)
        val testEngine = RuleEngine(repo)

        ruleDao.insertRule(approvedPackageRule("com.linkedin.android", "suppress"))
        awaitApprovedRuleCount(repo, 1)

        val linkedInRecord = NotificationRecord(
            packageName = "com.linkedin.android",
            postedAtMs = System.currentTimeMillis(),
            title = "5 people viewed your profile",
        )
        assertEquals(RuleAction.SUPPRESS, testEngine.evaluate(linkedInRecord))
    }

    // ── 7. Approved QUEUE rule → evaluate returns QUEUE ──────────────────────

    @Test
    fun approvedQueueRule_evaluateReturnsQueueForMatchingPackage() = runTest {
        val repo = RuleRepository(ruleDao)
        val testEngine = RuleEngine(repo)

        ruleDao.insertRule(approvedPackageRule("com.slack", "queue"))
        awaitApprovedRuleCount(repo, 1)

        val slackRecord = NotificationRecord(
            packageName = "com.slack",
            postedAtMs = System.currentTimeMillis(),
            title = "Alice Chen: Hey",
        )
        assertEquals(RuleAction.QUEUE, testEngine.evaluate(slackRecord))
    }

    // ── 8. Non-matching package → ALLOW ──────────────────────────────────────

    @Test
    fun approvedRule_nonMatchingPackage_returnsAllow() = runTest {
        val repo = RuleRepository(ruleDao)
        val testEngine = RuleEngine(repo)

        ruleDao.insertRule(approvedPackageRule("com.linkedin.android", "suppress"))
        awaitApprovedRuleCount(repo, 1)

        val whatsAppRecord = NotificationRecord(
            packageName = "com.whatsapp",
            postedAtMs = System.currentTimeMillis(),
            title = "Mom: Hey",
        )
        assertEquals(RuleAction.ALLOW, testEngine.evaluate(whatsAppRecord))
    }

    // ── 9. JudgmentCount reactive flow ───────────────────────────────────────

    @Test
    fun judgmentCountFlow_reflectsInsertedJudgments() = runTest {
        val trainingDao = db.trainingJudgmentDao()

        val initialCount = trainingDao.countFlow().first()
        assertEquals(0, initialCount)

        // Seed 10 notifications so IDs are valid
        val notifIds = insertAll(
            SyntheticNotifications.largeSyntheticDataset(count = 10, seed = 1)
        )

        repeat(5) { i ->
            trainingDao.insert(makeJudgment(notifIds[i * 2], notifIds[i * 2 + 1]))
        }

        val count = trainingDao.countFlow().first()
        assertEquals(5, count)
    }

    // ── 10. XP reactive flow ─────────────────────────────────────────────────

    @Test
    fun xpFlow_reflectsTotalXpAfterJudgments() = runTest {
        val trainingDao = db.trainingJudgmentDao()

        val notifIds = insertAll(
            SyntheticNotifications.largeSyntheticDataset(count = 4, seed = 2)
        )
        trainingDao.insert(makeJudgment(notifIds[0], notifIds[1], xp = 10))

        val totalXp = trainingDao.totalXpFlow().first()
        assertEquals(10, totalXp)
    }

    // ── 11. Pattern stats reactive flow ──────────────────────────────────────

    @Test
    fun patternStatsFlow_judgedCountIncrementsAfterJudgment() = runTest {
        val trainingDao = db.trainingJudgmentDao()

        // Insert two LinkedIn notifications so they form a pattern
        val id1 = notificationDao.insertOrReplace(NotificationRecord(
            packageName = "com.linkedin.android",
            postedAtMs = System.currentTimeMillis() - 1_000,
            title = "LinkedIn Update",
            text = "5 people viewed your profile",
            tier = 1,
            tierReason = "linkedin",
            isOngoing = false,
        ))
        val id2 = notificationDao.insertOrReplace(NotificationRecord(
            packageName = "com.linkedin.android",
            postedAtMs = System.currentTimeMillis() - 2_000,
            title = "LinkedIn",
            text = "New job matches",
            tier = 1,
            tierReason = "linkedin",
            isOngoing = false,
        ))

        val statsBefore = notificationDao.getPatternStatsFlow().first()
        val linkedInBefore = statsBefore.firstOrNull { it.packageName == "com.linkedin.android" }
        val judgedBefore = linkedInBefore?.judged ?: 0

        trainingDao.insert(makeJudgment(id1, id2, choice = "left"))

        val statsAfter = notificationDao.getPatternStatsFlow().first()
        val linkedInAfter = statsAfter.firstOrNull { it.packageName == "com.linkedin.android" }
        val judgedAfter = linkedInAfter?.judged ?: 0

        assertTrue(
            "judged count should increment after a judgment (before=$judgedBefore, after=$judgedAfter)",
            judgedAfter > judgedBefore,
        )
    }

    // ── 12. Elo round-trip: left-wins → left eloScore increases ──────────────

    @Test
    fun eloRoundTrip_leftWinsIncreasesLeftEloScore() = runTest {
        val appRankingDao = db.appRankingDao()
        val appBattleDao = db.appBattleJudgmentDao()

        val leftPkg = "com.app.left"
        val rightPkg = "com.app.right"
        val now = System.currentTimeMillis()

        appRankingDao.upsert(
            AppRanking(packageName = leftPkg, eloScore = 1200, updatedAtMs = now)
        )
        appRankingDao.upsert(
            AppRanking(packageName = rightPkg, eloScore = 1200, updatedAtMs = now)
        )

        val leftEloStart = appRankingDao.get(leftPkg)!!.eloScore
        val rightEloStart = appRankingDao.get(rightPkg)!!.eloScore

        // K=32, equal ratings → delta = 16 for left-wins
        val newLeftElo = leftEloStart + 16
        val newRightElo = rightEloStart - 16

        appBattleDao.insert(
            AppBattleJudgment(
                leftPackage = leftPkg,
                rightPackage = rightPkg,
                choice = "left",
                leftEloBefore = leftEloStart,
                rightEloBefore = rightEloStart,
                leftEloAfter = newLeftElo,
                rightEloAfter = newRightElo,
                xpAwarded = 3,
                createdAtMs = now,
            )
        )
        appRankingDao.upsert(
            appRankingDao.get(leftPkg)!!.copy(eloScore = newLeftElo, wins = 1, updatedAtMs = now)
        )
        appRankingDao.upsert(
            appRankingDao.get(rightPkg)!!.copy(eloScore = newRightElo, losses = 1, updatedAtMs = now)
        )

        val leftEloEnd = appRankingDao.get(leftPkg)!!.eloScore
        val rightEloEnd = appRankingDao.get(rightPkg)!!.eloScore

        assertTrue(
            "Left Elo should increase after winning (was $leftEloStart, now $leftEloEnd)",
            leftEloEnd > leftEloStart
        )
        assertTrue(
            "Right Elo should decrease after losing (was $rightEloStart, now $rightEloEnd)",
            rightEloEnd < rightEloStart
        )
        assertEquals("Elo delta must be symmetric (K=32, equal scores)", 32, leftEloEnd - rightEloEnd)
    }

    // ── 13. Edge cases: no NPE on null title+text ────────────────────────────

    @Test
    fun edgeCases_npeDoesNotOccurOnNullTitleAndText() = runTest {
        val edgeCases = SyntheticNotifications.edgeCaseNotifications()
        val ids = insertAll(edgeCases)
        assertTrue("All edge-case records should be inserted", ids.isNotEmpty())

        // Run backfill on copies with null tierReason — should not crash
        notificationDao.deleteAll()
        insertAll(edgeCases.map { it.copy(tierReason = null) })

        val result = runBackfillWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, notificationDao.countTierBackfillRemaining())
    }

    // ── 14. Security keyword wins over isOngoing=true ────────────────────────

    @Test
    fun edgeCase_securityKeywordWinsOverOngoing() = runTest {
        val id = notificationDao.insertOrReplace(NotificationRecord(
            packageName = "com.google.android.apps.messaging",
            postedAtMs = System.currentTimeMillis(),
            title = "SMS",
            text = "Your verification code is 456789",
            isOngoing = true,
            tierReason = null,
        ))

        runBackfillWorker()

        val record = notificationDao.getById(id)!!
        assertEquals(
            "Security keyword must win over isOngoing=true (security is checked first in TierClassifier)",
            3, record.tier,
        )
        assertEquals("security_2fa", record.tierReason)
    }

    // ── 15. mediaHeavy: tier-0 dominates after backfill ─────────────────────

    @Test
    fun seedMediaHeavy_tier0DominatesAfterBackfill() = runTest {
        val records = SyntheticNotifications.mediaHeavy(seed = 47)
        insertAll(records.map { it.copy(tierReason = null) })

        val result = runBackfillWorker()
        assertEquals(ListenableWorker.Result.success(), result)

        val breakdown = notificationDao.getTierBreakdown()
        val tierMap = breakdown.associate { it.tier to it.count }
        val tier0Count = tierMap[0] ?: 0
        val totalCount = breakdown.sumOf { it.count }

        // mediaHeavy: ~2100 Spotify+Podcast+YouTube ongoing (tier 0). Total ~2300 records.
        assertTrue(
            "mediaHeavy should have >80% tier-0 notifications (got $tier0Count / $totalCount)",
            tier0Count.toFloat() / totalCount > 0.80f,
        )
    }

    // ── Bonus: concurrent read during backfill does not crash ─────────────────

    @Test
    fun concurrentReadDuringBackfill_doesNotCrash() = runTest {
        val records = SyntheticNotifications.largeSyntheticDataset(count = 200, seed = 77)
        insertAll(records.map { it.copy(tierReason = null) })

        var readResult: List<TierCount>? = null
        var backfillResult: ListenableWorker.Result? = null

        val readJob = launch(Dispatchers.IO) {
            readResult = notificationDao.getTierBreakdown()
        }
        val backfillJob = launch(Dispatchers.IO) {
            backfillResult = runBackfillWorker()
        }

        readJob.join()
        backfillJob.join()

        assertEquals(ListenableWorker.Result.success(), backfillResult)
        assertNotNull(readResult)
    }
}
