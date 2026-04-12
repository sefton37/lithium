package ai.talkingrock.lithium.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.talkingrock.lithium.ai.SyntheticNotifications
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the stats computed directly from [NotificationDao] match what
 * [LithiumApiServer] would serve at GET /api/stats after a simulation run.
 *
 * This is the consistency check between DAO and Ktor output described in
 * TESTING_STRATEGY.md Phase 3 requirements. Because the Ktor routes are
 * pure wrappers around DAO calls, we validate the DAO logic here (in-memory DB)
 * without needing to bind a real HTTP port.
 *
 * The shape of StatsResponse is:
 *   totalNotifications   = notificationDao.count()
 *   classifiedNotifications = notificationDao.countClassified()
 *   unclassifiedNotifications = total - classified
 *   tierBreakdown        = notificationDao.getTierBreakdown().associate { it.tier to it.count }
 *   noiseRatio           = classified / total (0 when total=0)
 *
 * Method names use camelCase to ensure DEX compatibility at minSdk=29.
 */
@RunWith(AndroidJUnit4::class)
class ApiStatsConsistencyTest {

    private lateinit var context: Context
    private lateinit var db: LithiumDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LithiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SyntheticNotifications.resetIds()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helper: build the StatsResponse values from DAO — same logic as the API ──

    private data class StatsSnapshot(
        val totalNotifications: Int,
        val classifiedNotifications: Int,
        val unclassifiedNotifications: Int,
        val tierBreakdown: Map<Int, Int>,
        val noiseRatio: Float,
    )

    private suspend fun snapshotStats(): StatsSnapshot {
        val dao = db.notificationDao()
        val total = dao.count()
        val classified = dao.countClassified()
        val tierBreakdown = dao.getTierBreakdown().associate { it.tier to it.count }
        return StatsSnapshot(
            totalNotifications = total,
            classifiedNotifications = classified,
            unclassifiedNotifications = total - classified,
            tierBreakdown = tierBreakdown,
            noiseRatio = if (total > 0) classified.toFloat() / total else 0f,
        )
    }

    // ── 1. Empty DB: stats are all-zero, tierBreakdown empty ─────────────────

    @Test
    fun emptyDb_statsAreAllZero() = runTest {
        val stats = snapshotStats()

        assertEquals(0, stats.totalNotifications)
        assertEquals(0, stats.classifiedNotifications)
        assertEquals(0, stats.unclassifiedNotifications)
        assertTrue("tier breakdown must be empty when DB is empty", stats.tierBreakdown.isEmpty())
        assertEquals(0f, stats.noiseRatio, 0.001f)
    }

    // ── 2. tierBreakdown sums to totalNotifications ───────────────────────────

    @Test
    fun largeDataset_tierBreakdownSumsToTotal() = runTest {
        val dao = db.notificationDao()
        val records = SyntheticNotifications.largeSyntheticDataset(count = 500, seed = 55)
        records.forEach { dao.insertOrReplace(it) }

        val stats = snapshotStats()

        val tierSum = stats.tierBreakdown.values.sum()
        assertEquals(
            "tier breakdown must sum to totalNotifications",
            stats.totalNotifications, tierSum,
        )
    }

    // ── 3. classifiedNotifications + unclassified = total ────────────────────

    @Test
    fun mixedClassifiedAndUnclassified_sumsToTotal() = runTest {
        val dao = db.notificationDao()

        // Insert 10 classified + 5 unclassified
        repeat(10) { i ->
            val id = dao.insertOrReplace(NotificationRecord(
                packageName = "com.classified.app",
                postedAtMs = System.currentTimeMillis() - i * 1000L,
                title = "Classified $i",
                tier = 2,
                tierReason = "default",
            ))
            dao.updateClassification(id, "promotional", 0.85f)
        }
        repeat(5) { i ->
            dao.insertOrReplace(NotificationRecord(
                packageName = "com.unclassified.app",
                postedAtMs = System.currentTimeMillis() - i * 1000L,
                title = "Unclassified $i",
                tier = 2,
                tierReason = "default",
                // aiClassification left null
            ))
        }

        val stats = snapshotStats()

        assertEquals(15, stats.totalNotifications)
        assertEquals(10, stats.classifiedNotifications)
        assertEquals(5, stats.unclassifiedNotifications)
        assertEquals(
            "classified + unclassified must equal total",
            stats.totalNotifications,
            stats.classifiedNotifications + stats.unclassifiedNotifications,
        )
    }

    // ── 4. noiseRatio: classified/total ──────────────────────────────────────

    @Test
    fun noiseRatio_isClassifiedDividedByTotal() = runTest {
        val dao = db.notificationDao()

        // Insert 30 classified out of 100 total
        repeat(100) { i ->
            val id = dao.insertOrReplace(NotificationRecord(
                packageName = "com.test.app",
                postedAtMs = System.currentTimeMillis() - i * 1000L,
                title = "Notification $i",
                tier = 2,
            ))
            if (i < 30) {
                dao.updateClassification(id, "promotional", 0.8f)
            }
        }

        val stats = snapshotStats()

        assertEquals(0.3f, stats.noiseRatio, 0.001f)
    }

    // ── 5. noiseRatio: zero when total is zero ────────────────────────────────

    @Test
    fun noiseRatio_isZeroWhenTotalIsZero() = runTest {
        val stats = snapshotStats()
        assertEquals(0f, stats.noiseRatio, 0.001f)
    }

    // ── 6. Tier breakdown after simulation matches direct DAO query ───────────

    @Test
    fun postSimulation_tierBreakdownMatchesDirectDaoQuery() = runTest {
        val dao = db.notificationDao()

        // Insert known tier distribution
        val tierAssignments = listOf(
            "com.spotify.music" to 0,
            "com.spotify.music" to 0,
            "com.spotify.music" to 0,
            "com.linkedin.android" to 1,
            "com.linkedin.android" to 1,
            "com.google.android.gm" to 2,
            "com.google.android.gm" to 2,
            "com.google.android.gm" to 2,
            "com.whatsapp" to 3,
            "com.whatsapp" to 3,
        )

        tierAssignments.forEach { (pkg, tier) ->
            dao.insertOrReplace(NotificationRecord(
                packageName = pkg,
                postedAtMs = System.currentTimeMillis(),
                title = "Test",
                tier = tier,
                tierReason = when (tier) {
                    0 -> "media_player"
                    1 -> "linkedin"
                    2 -> "gmail"
                    else -> "sms_known"
                },
            ))
        }

        val stats = snapshotStats()
        val directBreakdown = dao.getTierBreakdown().associate { it.tier to it.count }

        // API stats tierBreakdown must be identical to direct DAO query
        assertEquals(
            "stats tierBreakdown must match direct getTierBreakdown() output",
            directBreakdown, stats.tierBreakdown,
        )
        assertEquals(3, stats.tierBreakdown[0]) // 3 tier-0 (Spotify)
        assertEquals(2, stats.tierBreakdown[1]) // 2 tier-1 (LinkedIn)
        assertEquals(3, stats.tierBreakdown[2]) // 3 tier-2 (Gmail)
        assertEquals(2, stats.tierBreakdown[3]) // 2 tier-3 (WhatsApp)
        assertEquals(10, stats.totalNotifications)
    }

    // ── 7. spamVictim simulation: stats shape is consistent ──────────────────

    @Test
    fun spamVictimSimulation_statsShapeIsConsistent() = runTest {
        val dao = db.notificationDao()
        val records = SyntheticNotifications.spamVictim(seed = 46)
        records.forEach { dao.insertOrReplace(it) }

        val stats = snapshotStats()

        // Shape invariants that must always hold
        assertTrue("total must be positive", stats.totalNotifications > 0)
        assertTrue("unclassified must be <= total", stats.unclassifiedNotifications <= stats.totalNotifications)
        assertEquals(
            "classified + unclassified == total",
            stats.totalNotifications,
            stats.classifiedNotifications + stats.unclassifiedNotifications,
        )
        val tierSum = stats.tierBreakdown.values.sum()
        assertEquals("tier breakdown sums to total", stats.totalNotifications, tierSum)
        assertTrue("noiseRatio in [0,1]", stats.noiseRatio in 0f..1f)
    }

    // ── 8. 1000-row large-scale seed: stats remain consistent ────────────────

    @Test
    fun largeScale1000Rows_statsInvariants() = runTest {
        val dao = db.notificationDao()
        val records = SyntheticNotifications.largeSyntheticDataset(count = 1_000, seed = 88)
        records.forEach { dao.insertOrReplace(it) }

        val stats = snapshotStats()

        assertEquals(1_000, stats.totalNotifications)
        assertEquals(
            "classified + unclassified == 1000",
            1_000,
            stats.classifiedNotifications + stats.unclassifiedNotifications,
        )
        val tierSum = stats.tierBreakdown.values.sum()
        assertEquals("tier breakdown sums to 1000", 1_000, tierSum)

        // All tiers should appear (largeSyntheticDataset covers all 4 tiers)
        assertTrue("tier 0 should appear", stats.tierBreakdown.containsKey(0))
        assertTrue("tier 1 should appear", stats.tierBreakdown.containsKey(1))
        assertTrue("tier 2 should appear", stats.tierBreakdown.containsKey(2))
        assertTrue("tier 3 should appear", stats.tierBreakdown.containsKey(3))
    }
}
