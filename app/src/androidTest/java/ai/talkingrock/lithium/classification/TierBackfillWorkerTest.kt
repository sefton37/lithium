package ai.talkingrock.lithium.classification

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [TierBackfillWorker].
 *
 * Uses in-memory Room + [TestListenableWorkerBuilder] with direct construction
 * (bypasses Hilt since TierBackfillWorker only depends on NotificationDao).
 */
@RunWith(AndroidJUnit4::class)
class TierBackfillWorkerTest {

    private lateinit var db: LithiumDatabase
    private lateinit var dao: NotificationDao
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LithiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    private suspend fun insertRecord(
        pkg: String = "com.test.app",
        isOngoing: Boolean = false,
        text: String? = null,
        category: String? = null,
        isFromContact: Boolean = false,
        tierReason: String? = null,  // null = needs backfill
        tier: Int = 2,
    ): Long = dao.insertOrReplace(
        NotificationRecord(
            packageName = pkg,
            postedAtMs = System.currentTimeMillis(),
            title = "Title",
            text = text,
            isOngoing = isOngoing,
            category = category,
            isFromContact = isFromContact,
            tier = tier,
            tierReason = tierReason,
        )
    )

    /**
     * Build and run TierBackfillWorker directly (no Hilt — constructor injection).
     */
    private suspend fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<TierBackfillWorker>(context)
            .setWorkerFactory(TierBackfillWorkerFactory(dao))
            .build()
        return worker.doWork()
    }

    // ── 1. empty DB → worker returns SUCCESS immediately ─────────────────────

    @Test
    fun emptyDb_workerReturnsSuccess() = runTest {
        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ── 2. all rows already have tier_reason → nothing processed, SUCCESS ─────

    @Test
    fun allRowsHaveTierReason_workerSucceedsWithoutProcessing() = runTest {
        insertRecord(tierReason = "default")
        insertRecord(tierReason = "gmail")
        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        // Verify backfill count is still 0 after run
        assertEquals(0, dao.countTierBackfillRemaining())
    }

    // ── 3. rows with tier_reason=NULL → all get correct tier and reason ───────

    @Test
    fun rowsWithNullTierReason_getBackfilled() = runTest {
        insertRecord(pkg = "com.spotify.music", tierReason = null, isOngoing = false)
        insertRecord(pkg = "com.random.app", tierReason = null, isOngoing = false)
        assertEquals(2, dao.countTierBackfillRemaining())

        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, dao.countTierBackfillRemaining())
    }

    // ── 4. batch size boundary: > 500 rows → worker processes all ────────────

    @Test
    fun manyNullRows_workerProcessesAll() = runTest {
        repeat(501) { insertRecord(tierReason = null) }
        assertEquals(501, dao.countTierBackfillRemaining())

        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, dao.countTierBackfillRemaining())
    }

    // ── 5. idempotent: rows with tier_reason already set are not re-processed ──

    @Test
    fun idempotent_alreadyClassifiedRowsNotReprocessed() = runTest {
        val idWithReason = insertRecord(tierReason = "gmail", tier = 2)
        val idNull = insertRecord(tierReason = null, pkg = "com.spotify.music", isOngoing = false)

        runWorker()

        // Row that already had tier_reason should not be changed
        val unchanged = dao.getById(idWithReason)
        assertEquals("gmail", unchanged!!.tierReason)

        // Row with null should now have a reason
        val backfilled = dao.getById(idNull)
        assertTrue(backfilled!!.tierReason != null)
    }

    // ── 6. row with isOngoing=true gets tier=0 "ongoing_persistent" ──────────

    @Test
    fun ongoingRow_getsTierZeroOngoingReason() = runTest {
        val id = insertRecord(pkg = "com.random.app", isOngoing = true, tierReason = null)
        runWorker()
        val record = dao.getById(id)!!
        assertEquals(0, record.tier)
        assertEquals("ongoing_persistent", record.tierReason)
    }

    // ── 7. OTP text row gets tier=3 "security_2fa" ───────────────────────────

    @Test
    fun otpText_getsTierThreeSecurityReason() = runTest {
        val id = insertRecord(
            pkg = "com.google.android.apps.messaging",
            text = "Your verification code is 123456",
            tierReason = null,
            isOngoing = false,
        )
        runWorker()
        val record = dao.getById(id)!!
        assertEquals(3, record.tier)
        assertEquals("security_2fa", record.tierReason)
    }

    // ── 8. row with isFromContact=true and messaging package gets "sms_known" ──

    @Test
    fun messageFromContact_getsSmsKnownReason() = runTest {
        val id = insertRecord(
            pkg = "com.google.android.apps.messaging",
            isFromContact = true,
            tierReason = null,
            isOngoing = false,
        )
        runWorker()
        val record = dao.getById(id)!!
        assertEquals(3, record.tier)
        assertEquals("sms_known", record.tierReason)
    }

    // ── 9. resumable: second run completes remaining rows ─────────────────────
    // Simulates a partial first run by manually setting some rows' tier_reason.

    @Test
    fun resumable_secondRunCompletesRemaining() = runTest {
        // Insert 3 rows needing backfill
        val id1 = insertRecord(tierReason = null, pkg = "com.app1")
        val id2 = insertRecord(tierReason = null, pkg = "com.app2")
        insertRecord(tierReason = null, pkg = "com.app3")

        // Simulate partial run: manually backfill 2 of them
        dao.updateTier(id1, 2, "default")
        dao.updateTier(id2, 2, "default")
        assertEquals(1, dao.countTierBackfillRemaining())

        // Run worker — should complete the remaining 1
        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, dao.countTierBackfillRemaining())
    }

    // ── 10. WORK_NAME matches WorkScheduler.scheduleTierBackfill ─────────────

    @Test
    fun workName_matchesExpectedConstant() {
        // Regression guard: TierBackfillWorker.WORK_NAME is used by WorkScheduler
        // to prevent duplicate enqueue. If it changes, scheduling breaks.
        assertEquals("lithium_tier_backfill", TierBackfillWorker.WORK_NAME)
    }
}

/**
 * Custom [androidx.work.WorkerFactory] for [TierBackfillWorker] that bypasses Hilt.
 *
 * TierBackfillWorker has exactly one non-standard dependency: NotificationDao.
 * Injecting it directly avoids setting up a full Hilt component in tests.
 */
class TierBackfillWorkerFactory(
    private val dao: NotificationDao,
) : androidx.work.WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: androidx.work.WorkerParameters,
    ): ListenableWorker? {
        return if (workerClassName == TierBackfillWorker::class.java.name) {
            TierBackfillWorker(appContext, workerParameters, dao)
        } else null
    }
}
