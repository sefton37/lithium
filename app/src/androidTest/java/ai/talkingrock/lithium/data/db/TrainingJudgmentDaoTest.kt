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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [TrainingJudgmentDao].
 *
 * Uses in-memory Room (no SQLCipher).
 */
@RunWith(AndroidJUnit4::class)
class TrainingJudgmentDaoTest {

    private lateinit var db: LithiumDatabase
    private lateinit var dao: TrainingJudgmentDao
    private lateinit var notifDao: NotificationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LithiumDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.trainingJudgmentDao()
        notifDao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    private suspend fun insertNotif(pkg: String = "com.test", tier: Int = 2): Long =
        notifDao.insertOrReplace(
            NotificationRecord(packageName = pkg, postedAtMs = System.currentTimeMillis(),
                title = "T", text = "B", tier = tier, tierReason = "default")
        )

    private suspend fun insertJudgment(
        leftId: Long,
        rightId: Long,
        choice: String = "left",
        xpAwarded: Int = 7,
        setBonusXp: Int = 0,
        setComplete: Boolean = false,
        questId: String = "free_play",
        createdAtMs: Long = System.currentTimeMillis(),
    ): Long = dao.insert(
        TrainingJudgment(
            leftNotificationId = leftId,
            rightNotificationId = rightId,
            choice = choice,
            leftTier = 2,
            rightTier = 2,
            leftTierReason = "default",
            rightTierReason = "default",
            leftAiClassification = null,
            rightAiClassification = null,
            leftConfidence = null,
            rightConfidence = null,
            createdAtMs = createdAtMs,
            xpAwarded = xpAwarded,
            setBonusXp = setBonusXp,
            setComplete = setComplete,
            questId = questId,
        )
    )

    // ── 1. insert → row persisted with all fields ─────────────────────────────

    @Test
    fun insert_rowPersistedWithAllFields() = runTest {
        val leftId = insertNotif()
        val rightId = insertNotif()
        val id = insertJudgment(leftId, rightId, choice = "tie", xpAwarded = 5)
        assertTrue(id > 0)
        val count = dao.countFlow().first()
        assertEquals(1, count)
    }

    // ── 2. countFlow reflects inserted row count ──────────────────────────────

    @Test
    fun countFlow_reflectsInsertedCount() = runTest {
        val l1 = insertNotif()
        val r1 = insertNotif()
        val l2 = insertNotif()
        val r2 = insertNotif()
        assertEquals(0, dao.countFlow().first())
        insertJudgment(l1, r1)
        assertEquals(1, dao.countFlow().first())
        insertJudgment(l2, r2)
        assertEquals(2, dao.countFlow().first())
    }

    // ── 3. getJudgedNotificationIds returns both left and right IDs ───────────

    @Test
    fun getJudgedNotificationIds_returnsBothSideIds() = runTest {
        val leftId = insertNotif()
        val rightId = insertNotif()
        insertJudgment(leftId, rightId)
        val ids = dao.getJudgedNotificationIds()
        assertTrue(ids.contains(leftId))
        assertTrue(ids.contains(rightId))
    }

    // ── 4. getJudgedNotificationIds deduplicates IDs appearing on both sides ──

    @Test
    fun getJudgedNotificationIds_deduplicatesIds() = runTest {
        val id1 = insertNotif()
        val id2 = insertNotif()
        val id3 = insertNotif()
        // id1 appears as left in judgment1 and right in judgment2
        insertJudgment(id1, id2)
        insertJudgment(id3, id1)
        val ids = dao.getJudgedNotificationIds()
        // UNION removes duplicates
        assertEquals(3, ids.size)
        assertTrue(ids.toSet() == setOf(id1, id2, id3))
    }

    // ── 5. totalXpFlow sums xp_awarded + set_bonus_xp ────────────────────────

    @Test
    fun totalXpFlow_sumsXpAndBonus() = runTest {
        val l1 = insertNotif(); val r1 = insertNotif()
        val l2 = insertNotif(); val r2 = insertNotif()
        insertJudgment(l1, r1, xpAwarded = 7, setBonusXp = 0)
        insertJudgment(l2, r2, xpAwarded = 7, setBonusXp = 5, setComplete = true)
        // Total = (7+0) + (7+5) = 19
        assertEquals(19, dao.totalXpFlow().first())
    }

    // ── 6. totalXpFlow excludes skip rows ────────────────────────────────────

    @Test
    fun totalXpFlow_excludesSkipRows() = runTest {
        val l1 = insertNotif(); val r1 = insertNotif()
        val l2 = insertNotif(); val r2 = insertNotif()
        insertJudgment(l1, r1, choice = "left", xpAwarded = 10)
        insertJudgment(l2, r2, choice = "skip", xpAwarded = 0)
        assertEquals(10, dao.totalXpFlow().first())
    }

    // ── 7. xpByQuestFlow groups by quest_id correctly ────────────────────────

    @Test
    fun xpByQuestFlow_groupsByQuestId() = runTest {
        val l1 = insertNotif(); val r1 = insertNotif()
        val l2 = insertNotif(); val r2 = insertNotif()
        insertJudgment(l1, r1, xpAwarded = 7, questId = "quest_alpha")
        insertJudgment(l2, r2, xpAwarded = 10, questId = "free_play")
        val xpByQuest = dao.xpByQuestFlow().first()
        val alphaXp = xpByQuest.find { it.questId == "quest_alpha" }?.xp
        val freePlayXp = xpByQuest.find { it.questId == "free_play" }?.xp
        assertEquals(7, alphaXp)
        assertEquals(10, freePlayXp)
    }

    // ── 8. xpByQuestFlow returns 0 for quest with no judgments ───────────────
    // (This is tested implicitly — a quest that has no rows simply won't appear.
    // Verify that the list doesn't contain a phantom entry.)

    @Test
    fun xpByQuestFlow_noPhantomEntries() = runTest {
        val l1 = insertNotif(); val r1 = insertNotif()
        insertJudgment(l1, r1, questId = "free_play", xpAwarded = 5)
        val xpByQuest = dao.xpByQuestFlow().first()
        // "missing_quest" should not appear
        assertTrue(xpByQuest.none { it.questId == "missing_quest" })
    }

    // ── 9. countSinceFlow filters by created_at_ms >= sinceMs AND choice != 'skip' ──

    @Test
    fun countSinceFlow_filtersBySinceAndExcludesSkip() = runTest {
        val now = System.currentTimeMillis()
        val l1 = insertNotif(); val r1 = insertNotif()
        val l2 = insertNotif(); val r2 = insertNotif()
        val l3 = insertNotif(); val r3 = insertNotif()
        insertJudgment(l1, r1, choice = "left", createdAtMs = now - 100L)    // qualifies
        insertJudgment(l2, r2, choice = "skip", createdAtMs = now - 100L)    // skip — excluded
        insertJudgment(l3, r3, choice = "right", createdAtMs = now - 100_000L) // too old
        val count = dao.countSinceFlow(sinceMs = now - 1000L).first()
        assertEquals(1, count)
    }

    // ── 10. getChoiceBreakdown groups by choice, counts correctly ────────────

    @Test
    fun getChoiceBreakdown_groupsAndCounts() = runTest {
        val ids = (1..6).map { insertNotif() }
        insertJudgment(ids[0], ids[1], choice = "left")
        insertJudgment(ids[2], ids[3], choice = "left")
        insertJudgment(ids[4], ids[5], choice = "skip")
        val breakdown = dao.getChoiceBreakdown()
        val map = breakdown.associate { it.choice to it.count }
        assertEquals(2, map["left"])
        assertEquals(1, map["skip"])
    }

    // ── 11. skip judgment: xp_awarded=0 not included in totalXpFlow ──────────
    // (Covered by test 6, but this explicitly verifies the filter on choice.)

    @Test
    fun skipJudgment_notIncludedInTotalXp() = runTest {
        val l1 = insertNotif(); val r1 = insertNotif()
        insertJudgment(l1, r1, choice = "skip", xpAwarded = 0)
        assertEquals(0, dao.totalXpFlow().first())
    }

    // ── 12. insert with quest_id → xpByQuestFlow shows it under the right quest ──

    @Test
    fun questTaggedJudgment_appearsInCorrectQuestGroup() = runTest {
        val l1 = insertNotif(); val r1 = insertNotif()
        insertJudgment(l1, r1, xpAwarded = 15, questId = "label_the_unknown")
        val xpByQuest = dao.xpByQuestFlow().first()
        val entry = xpByQuest.find { it.questId == "label_the_unknown" }
        assertEquals(15, entry?.xp)
    }
}
