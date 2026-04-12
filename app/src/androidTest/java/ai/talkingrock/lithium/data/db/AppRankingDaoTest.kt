package ai.talkingrock.lithium.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.talkingrock.lithium.data.model.AppRanking
import ai.talkingrock.lithium.data.model.NotificationRecord
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
 * Instrumented DAO tests for [AppRankingDao].
 *
 * Uses in-memory Room (no SQLCipher).
 */
@RunWith(AndroidJUnit4::class)
class AppRankingDaoTest {

    private lateinit var db: LithiumDatabase
    private lateinit var dao: AppRankingDao
    private lateinit var notifDao: NotificationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LithiumDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.appRankingDao()
        notifDao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    private fun ranking(pkg: String, elo: Int = AppRanking.DEFAULT_ELO) = AppRanking(
        packageName = pkg,
        eloScore = elo,
        wins = 0,
        losses = 0,
        ties = 0,
        judgments = 0,
        updatedAtMs = System.currentTimeMillis()
    )

    private suspend fun insertNotif(
        pkg: String,
        tier: Int = 2,
        isOngoing: Boolean = false,
        title: String? = "Title",
        text: String? = "Body",
    ): Long = notifDao.insertOrReplace(
        NotificationRecord(
            packageName = pkg,
            postedAtMs = System.currentTimeMillis(),
            title = title,
            text = text,
            isOngoing = isOngoing,
            tier = tier,
            tierReason = "default",
        )
    )

    // ── 1. upsert creates row if not exists ───────────────────────────────────

    @Test
    fun upsert_createsRowIfNotExists() = runTest {
        dao.upsert(ranking("com.new.app"))
        assertNotNull(dao.get("com.new.app"))
    }

    // ── 2. upsert replaces existing row ──────────────────────────────────────

    @Test
    fun upsert_replacesExistingRow() = runTest {
        dao.upsert(ranking("com.app", elo = 1200))
        dao.upsert(ranking("com.app", elo = 1250).copy(wins = 1, judgments = 1))
        val row = dao.get("com.app")
        assertEquals(1250, row!!.eloScore)
        assertEquals(1, row.wins)
    }

    // ── 3. get returns row by package_name ────────────────────────────────────

    @Test
    fun get_returnsRowByPackageName() = runTest {
        dao.upsert(ranking("com.target"))
        val row = dao.get("com.target")
        assertNotNull(row)
        assertEquals("com.target", row!!.packageName)
    }

    // ── 4. get returns null for unknown package ───────────────────────────────

    @Test
    fun get_returnsNullForUnknownPackage() = runTest {
        assertNull(dao.get("com.unknown.package"))
    }

    // ── 5. getAll returns all rows ordered by elo_score DESC ─────────────────

    @Test
    fun getAll_orderedByEloDesc() = runTest {
        dao.upsert(ranking("com.low", elo = 1100))
        dao.upsert(ranking("com.high", elo = 1400))
        dao.upsert(ranking("com.mid", elo = 1250))
        val all = dao.getAll()
        assertEquals("com.high", all[0].packageName)
        assertEquals("com.mid", all[1].packageName)
        assertEquals("com.low", all[2].packageName)
    }

    // ── 6. getEligiblePackages: only packages with tier > 0, is_ongoing=0, non-null title/text ──

    @Test
    fun getEligiblePackages_onlyEligibleRows() = runTest {
        insertNotif("com.eligible", tier = 2, isOngoing = false, title = "Hello")
        insertNotif("com.tier_zero", tier = 0, isOngoing = false, title = "Hello")
        insertNotif("com.ongoing", tier = 2, isOngoing = true, title = "Hello")
        insertNotif("com.no_title_text", tier = 2, isOngoing = false, title = null, text = null)

        val eligible = dao.getEligiblePackages()
        assertTrue(eligible.contains("com.eligible"))
        assertTrue(!eligible.contains("com.tier_zero"))
        assertTrue(!eligible.contains("com.ongoing"))
        assertTrue(!eligible.contains("com.no_title_text"))
    }

    // ── 7. getEligiblePackages: excludes packages with only tier=0 notifications ──

    @Test
    fun getEligiblePackages_excludesTierZeroOnlyPackages() = runTest {
        insertNotif("com.all_tier0_pkg", tier = 0, title = "T")
        insertNotif("com.all_tier0_pkg", tier = 0, title = "T")
        val eligible = dao.getEligiblePackages()
        assertTrue(!eligible.contains("com.all_tier0_pkg"))
    }

    // ── 8. getEligiblePackages: excludes packages with only ongoing notifications ──

    @Test
    fun getEligiblePackages_excludesOnlyOngoingPackages() = runTest {
        insertNotif("com.always_ongoing", tier = 2, isOngoing = true, title = "T")
        val eligible = dao.getEligiblePackages()
        assertTrue(!eligible.contains("com.always_ongoing"))
    }

    // ── 9. count returns correct row count ────────────────────────────────────

    @Test
    fun count_returnsCorrectRowCount() = runTest {
        assertEquals(0, dao.count())
        dao.upsert(ranking("com.a"))
        dao.upsert(ranking("com.b"))
        assertEquals(2, dao.count())
    }

    // ── 10. default elo_score is 1200 ─────────────────────────────────────────

    @Test
    fun defaultEloScore_is1200() = runTest {
        dao.upsert(ranking("com.default.elo"))
        val row = dao.get("com.default.elo")
        assertEquals(AppRanking.DEFAULT_ELO, row!!.eloScore)
        assertEquals(1200, row.eloScore)
    }
}
