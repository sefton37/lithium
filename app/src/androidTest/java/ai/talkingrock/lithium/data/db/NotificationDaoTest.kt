package ai.talkingrock.lithium.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.TrainingJudgment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [NotificationDao].
 *
 * Uses in-memory Room (no SQLCipher) — encrypted-at-rest is equivalent to plain SQLite
 * for query correctness. SQLCipher is tested once in the instrumented cold-start path.
 */
@RunWith(AndroidJUnit4::class)
class NotificationDaoTest {

    private lateinit var db: LithiumDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LithiumDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    private fun record(
        id: Long = 0,
        pkg: String = "com.test.app",
        postedAtMs: Long = System.currentTimeMillis(),
        title: String? = "Title",
        text: String? = "Text",
        isOngoing: Boolean = false,
        aiClassification: String? = null,
        aiConfidence: Float? = null,
        tier: Int = 2,
        tierReason: String? = "default",
        isFromContact: Boolean = false,
        category: String? = null,
        removalReason: String? = null,
    ) = NotificationRecord(
        id = id,
        packageName = pkg,
        postedAtMs = postedAtMs,
        title = title,
        text = text,
        isOngoing = isOngoing,
        aiClassification = aiClassification,
        aiConfidence = aiConfidence,
        tier = tier,
        tierReason = tierReason,
        isFromContact = isFromContact,
        category = category,
        removalReason = removalReason,
    )

    // ── 1. insertOrReplace → row readable by getById ──────────────────────────

    @Test
    fun insertOrReplace_rowReadableByGetById() = runTest {
        val id = dao.insertOrReplace(record(pkg = "com.example"))
        val fetched = dao.getById(id)
        assertNotNull(fetched)
        assertEquals("com.example", fetched!!.packageName)
    }

    // ── 2. insertOrReplace same id → replaces row ─────────────────────────────

    @Test
    fun insertOrReplace_sameId_replacesRow() = runTest {
        val id = dao.insertOrReplace(record(pkg = "com.old"))
        dao.insertOrReplace(record(id = id, pkg = "com.new"))
        val fetched = dao.getById(id)
        assertEquals("com.new", fetched!!.packageName)
    }

    // ── 3. updateRemoval sets removed_at_ms and removal_reason ───────────────

    @Test
    fun updateRemoval_setsRemovedAtAndReason() = runTest {
        val id = dao.insertOrReplace(record())
        dao.updateRemoval(id, 9999L, "dismissed")
        val fetched = dao.getById(id)
        assertEquals(9999L, fetched!!.removedAtMs)
        assertEquals("dismissed", fetched.removalReason)
    }

    // ── 4. getRecent filters by sinceMs and orders newest-first ──────────────

    @Test
    fun getRecent_filtersBySinceMs_newestFirst() = runTest {
        dao.insertOrReplace(record(postedAtMs = 100L))
        dao.insertOrReplace(record(postedAtMs = 200L))
        dao.insertOrReplace(record(postedAtMs = 50L))
        val results = dao.getRecent(sinceMs = 100L).first()
        assertEquals(2, results.size)
        assertTrue(results[0].postedAtMs >= results[1].postedAtMs)
    }

    // ── 5. getUnclassified returns only rows where ai_classification IS NULL ──

    @Test
    fun getUnclassified_returnsOnlyNullClassification() = runTest {
        dao.insertOrReplace(record(aiClassification = null))
        dao.insertOrReplace(record(aiClassification = "personal"))
        val results = dao.getUnclassified(limit = 10)
        assertEquals(1, results.size)
        assertNull(results[0].aiClassification)
    }

    // ── 6. getUnclassified respects limit ─────────────────────────────────────

    @Test
    fun getUnclassified_respectsLimit() = runTest {
        repeat(5) { dao.insertOrReplace(record(aiClassification = null)) }
        val results = dao.getUnclassified(limit = 3)
        assertEquals(3, results.size)
    }

    // ── 7. getUnclassified ordered ASC by posted_at_ms ───────────────────────

    @Test
    fun getUnclassified_orderedAscByPostedAtMs() = runTest {
        dao.insertOrReplace(record(postedAtMs = 300L, aiClassification = null))
        dao.insertOrReplace(record(postedAtMs = 100L, aiClassification = null))
        dao.insertOrReplace(record(postedAtMs = 200L, aiClassification = null))
        val results = dao.getUnclassified(limit = 10)
        assertEquals(100L, results[0].postedAtMs)
        assertEquals(200L, results[1].postedAtMs)
        assertEquals(300L, results[2].postedAtMs)
    }

    // ── 8. getOngoingMisclassified returns only is_ongoing=1 AND ai_classification='unknown' ──

    @Test
    fun getOngoingMisclassified_returnsCorrectRows() = runTest {
        dao.insertOrReplace(record(isOngoing = true, aiClassification = "unknown"))
        dao.insertOrReplace(record(isOngoing = false, aiClassification = "unknown"))
        dao.insertOrReplace(record(isOngoing = true, aiClassification = "background"))
        val results = dao.getOngoingMisclassified(limit = 10)
        assertEquals(1, results.size)
        assertTrue(results[0].isOngoing)
        assertEquals("unknown", results[0].aiClassification)
    }

    // ── 9. updateClassification sets ai_classification and ai_confidence ──────

    @Test
    fun updateClassification_setsFields() = runTest {
        val id = dao.insertOrReplace(record(aiClassification = null))
        dao.updateClassification(id, "personal", 0.95f)
        val fetched = dao.getById(id)
        assertEquals("personal", fetched!!.aiClassification)
        assertEquals(0.95f, fetched.aiConfidence!!, 0.001f)
    }

    // ── 10. deleteOlderThan removes rows before threshold, leaves rows after ──

    @Test
    fun deleteOlderThan_removesOldRows() = runTest {
        dao.insertOrReplace(record(postedAtMs = 100L))
        dao.insertOrReplace(record(postedAtMs = 200L))
        dao.insertOrReplace(record(postedAtMs = 300L))
        dao.deleteOlderThan(200L)
        val remaining = dao.getAll().first()
        assertTrue(remaining.all { it.postedAtMs >= 200L })
        assertEquals(2, remaining.size)
    }

    // ── 11. countClassified returns count of non-null ai_classification rows ──

    @Test
    fun countClassified_returnsCorrectCount() = runTest {
        dao.insertOrReplace(record(aiClassification = "personal"))
        dao.insertOrReplace(record(aiClassification = "promotional"))
        dao.insertOrReplace(record(aiClassification = null))
        assertEquals(2, dao.countClassified())
    }

    // ── 12. countDistinctClassifiedApps counts package_name DISTINCT ─────────

    @Test
    fun countDistinctClassifiedApps_countsByDistinctPackage() = runTest {
        dao.insertOrReplace(record(pkg = "com.a", aiClassification = "personal"))
        dao.insertOrReplace(record(pkg = "com.a", aiClassification = "personal"))
        dao.insertOrReplace(record(pkg = "com.b", aiClassification = "promotional"))
        dao.insertOrReplace(record(pkg = "com.c", aiClassification = null))
        assertEquals(2, dao.countDistinctClassifiedApps())
    }

    // ── 13. getTierBreakdown groups by tier correctly ─────────────────────────

    @Test
    fun getTierBreakdown_groupsByTier() = runTest {
        dao.insertOrReplace(record(tier = 0, tierReason = "ongoing_persistent"))
        dao.insertOrReplace(record(tier = 0, tierReason = "media_player"))
        dao.insertOrReplace(record(tier = 2, tierReason = "default"))
        dao.insertOrReplace(record(tier = 3, tierReason = "sms_known"))
        val breakdown = dao.getTierBreakdown()
        val byTier = breakdown.associate { it.tier to it.count }
        assertEquals(2, byTier[0])
        assertEquals(1, byTier[2])
        assertEquals(1, byTier[3])
    }

    // ── 14. getTierBreakdownSince filters by sinceMs ──────────────────────────

    @Test
    fun getTierBreakdownSince_filtersBySinceMs() = runTest {
        dao.insertOrReplace(record(postedAtMs = 100L, tier = 2, tierReason = "default"))
        dao.insertOrReplace(record(postedAtMs = 500L, tier = 3, tierReason = "sms_known"))
        val breakdown = dao.getTierBreakdownSince(300L).first()
        assertEquals(1, breakdown.size)
        assertEquals(3, breakdown[0].tier)
    }

    // ── 15. getTierBackfillBatch returns only tier_reason IS NULL rows in ASC id order ──

    @Test
    fun getTierBackfillBatch_returnsNullTierReasonRowsAscId() = runTest {
        val id1 = dao.insertOrReplace(record(tierReason = null))
        val id2 = dao.insertOrReplace(record(tierReason = "default"))
        val id3 = dao.insertOrReplace(record(tierReason = null))
        val batch = dao.getTierBackfillBatch(limit = 10)
        assertEquals(2, batch.size)
        assertEquals(id1, batch[0].id)
        assertEquals(id3, batch[1].id)
    }

    // ── 16. getTierBackfillBatch respects limit ───────────────────────────────

    @Test
    fun getTierBackfillBatch_respectsLimit() = runTest {
        repeat(5) { dao.insertOrReplace(record(tierReason = null)) }
        val batch = dao.getTierBackfillBatch(limit = 3)
        assertEquals(3, batch.size)
    }

    // ── 17. countTierBackfillRemaining returns correct count ─────────────────

    @Test
    fun countTierBackfillRemaining_returnsCorrectCount() = runTest {
        dao.insertOrReplace(record(tierReason = null))
        dao.insertOrReplace(record(tierReason = null))
        dao.insertOrReplace(record(tierReason = "default"))
        assertEquals(2, dao.countTierBackfillRemaining())
    }

    // ── 18. updateTier sets tier and tier_reason for correct row ─────────────

    @Test
    fun updateTier_setsFields() = runTest {
        val id = dao.insertOrReplace(record(tier = 2, tierReason = null))
        dao.updateTier(id, 3, "sms_known")
        val fetched = dao.getById(id)
        assertEquals(3, fetched!!.tier)
        assertEquals("sms_known", fetched.tierReason)
    }

    // ── 19. getAmbiguousCandidates excludes tier=0 rows ──────────────────────

    @Test
    fun getAmbiguousCandidates_excludesTierZero() = runTest {
        dao.insertOrReplace(record(tier = 0, tierReason = "media_player", isOngoing = false, title = "T"))
        dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = false, title = "T"))
        val results = dao.getAmbiguousCandidates(limit = 10, excludeIds = emptyList())
        assertEquals(1, results.size)
        assertEquals(2, results[0].tier)
    }

    // ── 20. getAmbiguousCandidates excludes is_ongoing=1 rows ────────────────

    @Test
    fun getAmbiguousCandidates_excludesOngoing() = runTest {
        dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = true, title = "T"))
        dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = false, title = "T"))
        val results = dao.getAmbiguousCandidates(limit = 10, excludeIds = emptyList())
        assertEquals(1, results.size)
        assertEquals(false, results[0].isOngoing)
    }

    // ── 21. getAmbiguousCandidates excludes rows in excludeIds ───────────────

    @Test
    fun getAmbiguousCandidates_respectsExcludeIds() = runTest {
        val id1 = dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = false, title = "T"))
        val id2 = dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = false, title = "T"))
        val results = dao.getAmbiguousCandidates(limit = 10, excludeIds = listOf(id1))
        assertEquals(1, results.size)
        assertEquals(id2, results[0].id)
    }

    // ── 22. getAmbiguousCandidates puts ai_classification=NULL rows first ─────

    @Test
    fun getAmbiguousCandidates_nullClassificationFirst() = runTest {
        // Insert classified row with confidence 0.51 (close to 0.5 boundary)
        dao.insertOrReplace(
            record(tier = 2, tierReason = "default", isOngoing = false, title = "T",
                aiClassification = "personal", aiConfidence = 0.51f)
        )
        // Insert unclassified row
        val unclassifiedId = dao.insertOrReplace(
            record(tier = 2, tierReason = "default", isOngoing = false, title = "T",
                aiClassification = null)
        )
        val results = dao.getAmbiguousCandidates(limit = 10, excludeIds = emptyList())
        assertEquals(2, results.size)
        // Unclassified should come first (ORDER BY CASE WHEN IS NULL THEN 0.0 ...)
        assertEquals(unclassifiedId, results[0].id)
    }

    // ── 23. getAmbiguousCandidates orders classified rows by |confidence - 0.5| ascending ──

    @Test
    fun getAmbiguousCandidates_classifiedOrderedByAmbiguity() = runTest {
        // High confidence (low ambiguity: |0.9 - 0.5| = 0.4)
        dao.insertOrReplace(
            record(tier = 2, tierReason = "default", isOngoing = false, title = "T",
                aiClassification = "personal", aiConfidence = 0.9f)
        )
        // Low confidence (high ambiguity: |0.55 - 0.5| = 0.05)
        val ambiguousId = dao.insertOrReplace(
            record(tier = 2, tierReason = "default", isOngoing = false, title = "T",
                aiClassification = "personal", aiConfidence = 0.55f)
        )
        val results = dao.getAmbiguousCandidates(limit = 10, excludeIds = emptyList())
        // Most ambiguous (closest to 0.5) should come first
        assertEquals(ambiguousId, results[0].id)
    }

    // ── 24. getUnclassifiedCandidates only returns ai_classification IS NULL rows ──

    @Test
    fun getUnclassifiedCandidates_returnsOnlyNullClassification() = runTest {
        dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = false, title = "T",
            aiClassification = "personal"))
        dao.insertOrReplace(record(tier = 2, tierReason = "default", isOngoing = false, title = "T",
            aiClassification = null))
        val results = dao.getUnclassifiedCandidates(limit = 10, excludeIds = emptyList())
        assertEquals(1, results.size)
        assertNull(results[0].aiClassification)
    }

    // ── 25. getPatternStatsFlow emits correct (total, judged) per pattern ─────

    @Test
    fun getPatternStatsFlow_emitsCorrectTotals() = runTest {
        dao.insertOrReplace(record(pkg = "com.a", tier = 2, tierReason = "default",
            isOngoing = false, title = "T", id = 0))
        dao.insertOrReplace(record(pkg = "com.a", tier = 2, tierReason = "default",
            isOngoing = false, title = "T", id = 0))
        val stats = dao.getPatternStatsFlow().first()
        val stat = stats.find { it.packageName == "com.a" && it.tierReason == "default" }
        assertNotNull(stat)
        assertEquals(2, stat!!.total)
        assertEquals(0, stat.judged)
    }

    // ── 26. getPatternStatsFlow judged count increments when training_judgments row inserted ──

    @Test
    fun getPatternStatsFlow_judgedIncrements() = runTest {
        val id1 = dao.insertOrReplace(record(pkg = "com.b", tier = 2, tierReason = "default",
            isOngoing = false, title = "T"))
        val id2 = dao.insertOrReplace(record(pkg = "com.b", tier = 2, tierReason = "default",
            isOngoing = false, title = "T"))

        // Before judgment
        val beforeStats = dao.getPatternStatsFlow().first()
        val beforeStat = beforeStats.find { it.packageName == "com.b" }
        assertEquals(0, beforeStat!!.judged)

        // Insert a judgment
        db.trainingJudgmentDao().insert(
            TrainingJudgment(
                leftNotificationId = id1,
                rightNotificationId = id2,
                choice = "left",
                leftTier = 2,
                rightTier = 2,
                leftTierReason = "default",
                rightTierReason = "default",
                leftAiClassification = null,
                rightAiClassification = null,
                leftConfidence = null,
                rightConfidence = null,
                createdAtMs = System.currentTimeMillis(),
                xpAwarded = 7,
            )
        )

        val afterStats = dao.getPatternStatsFlow().first()
        val afterStat = afterStats.find { it.packageName == "com.b" }
        // Both id1 and id2 appear in training_judgments so both are "judged"
        assertEquals(2, afterStat!!.judged)
    }

    // ── 27. getTierReasonStats filters by tier<=maxTier, since, minCount, counts tapped ──

    @Test
    fun getTierReasonStats_filtersCorrectly() = runTest {
        val now = System.currentTimeMillis()
        // Qualifying row: tier=1, recent, count will be 3 (>= minCount 2), 1 tapped
        repeat(2) {
            dao.insertOrReplace(record(pkg = "com.linkedin.android", tier = 1, tierReason = "linkedin",
                postedAtMs = now - 1000L))
        }
        dao.insertOrReplace(record(pkg = "com.linkedin.android", tier = 1, tierReason = "linkedin",
            postedAtMs = now - 1000L, removalReason = "click"))
        // Non-qualifying: tier=2 (above maxTier=1)
        dao.insertOrReplace(record(pkg = "com.example", tier = 2, tierReason = "default",
            postedAtMs = now - 1000L))
        // Non-qualifying: old (before sinceMs)
        dao.insertOrReplace(record(pkg = "com.old", tier = 1, tierReason = "linkedin",
            postedAtMs = 100L))

        val stats = dao.getTierReasonStats(sinceMs = now - 5000L, maxTier = 1, minCount = 2)
        assertEquals(1, stats.size)
        assertEquals("com.linkedin.android", stats[0].packageName)
        assertEquals("linkedin", stats[0].tierReason)
        assertEquals(3, stats[0].count)
        assertEquals(1, stats[0].tapped)
    }

    // ── 28. getAllSinceWithTiers: filters by tier list and sinceMs ────────────

    @Test
    fun getAllSinceWithTiers_filtersByTierAndTime() = runTest {
        val now = System.currentTimeMillis()
        dao.insertOrReplace(record(tier = 1, tierReason = "linkedin", postedAtMs = now - 100L))
        dao.insertOrReplace(record(tier = 3, tierReason = "sms_known", postedAtMs = now - 100L))
        dao.insertOrReplace(record(tier = 2, tierReason = "default", postedAtMs = now - 100L))
        dao.insertOrReplace(record(tier = 1, tierReason = "linkedin", postedAtMs = 50L)) // too old

        val results = dao.getAllSinceWithTiers(sinceMs = now - 1000L, tiers = listOf(1, 3))
        assertEquals(2, results.size)
        assertTrue(results.all { it.tier == 1 || it.tier == 3 })
    }
}
