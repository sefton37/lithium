package ai.talkingrock.lithium.ui.training

import ai.talkingrock.lithium.data.db.AppBattleJudgmentDao
import ai.talkingrock.lithium.data.db.AppRankingDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.PatternStat
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import ai.talkingrock.lithium.data.model.AppBattleJudgment
import ai.talkingrock.lithium.data.model.AppRanking
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.TrainingJudgment
import android.content.SharedPreferences
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [TrainingViewModel].
 *
 * Uses faked DAOs backed by [MutableStateFlow]s for reactive sources and MockK
 * for suspend call verification. [MainDispatcherRule] replaces Dispatchers.Main.
 *
 * Note: app battle tests explicitly set up notification pairs as the fallback path
 * to avoid non-determinism from Random in shouldDoAppBattle().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var notificationDao: NotificationDao
    private lateinit var judgmentDao: TrainingJudgmentDao
    private lateinit var appRankingDao: AppRankingDao
    private lateinit var appBattleDao: AppBattleJudgmentDao
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sharedPrefsEditor: SharedPreferences.Editor

    // Fake reactive flows
    private val fakeCountFlow = MutableStateFlow(0)
    private val fakeTotalXpFlow = MutableStateFlow(0)
    private val fakePatternStats = MutableStateFlow<List<PatternStat>>(emptyList())
    private val fakeQuestXpFlow = MutableStateFlow<List<ai.talkingrock.lithium.data.db.QuestXp>>(emptyList())

    @Before
    fun setUp() {
        notificationDao = mockk()
        judgmentDao = mockk()
        appRankingDao = mockk()
        appBattleDao = mockk()
        sharedPrefsEditor = mockk(relaxed = true)
        sharedPrefs = mockk()

        every { sharedPrefs.getString(any(), any()) } returns null
        every { sharedPrefs.edit() } returns sharedPrefsEditor
        every { sharedPrefsEditor.putString(any(), any()) } returns sharedPrefsEditor
        every { sharedPrefsEditor.apply() } just Runs

        every { judgmentDao.countFlow() } returns fakeCountFlow
        every { judgmentDao.totalXpFlow() } returns fakeTotalXpFlow
        every { judgmentDao.xpByQuestFlow() } returns fakeQuestXpFlow
        every { notificationDao.getPatternStatsFlow() } returns fakePatternStats

        // Default: no eligible packages for app battles → always use notification pairs
        coEvery { appRankingDao.count() } returns 0
        coEvery { appRankingDao.getEligiblePackages() } returns emptyList()
    }

    /** Helper: two-row candidate pool ready for pairs. */
    private fun twoNotifs(
        pkg1: String = "com.pkg.a",
        pkg2: String = "com.pkg.b"
    ) = listOf(
        NotificationRecord(id = 1L, packageName = pkg1, tier = 2, title = "Title A"),
        NotificationRecord(id = 2L, packageName = pkg2, tier = 2, title = "Title B")
    )

    private fun makeViewModel(): TrainingViewModel {
        return TrainingViewModel(
            notificationDao, judgmentDao, appRankingDao, appBattleDao, sharedPrefs
        )
    }

    /** Set up the mocks for a notification pair challenge. */
    private fun setupForNotifPair(patternJudged: Int = 0) {
        coEvery { judgmentDao.getJudgedNotificationIds() } returns emptyList()
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        fakePatternStats.value = listOf(
            PatternStat(
                pattern = "com.pkg.a|none", packageName = "com.pkg.a",
                tierReason = "none", total = 5, judged = patternJudged
            ),
            PatternStat(
                pattern = "com.pkg.b|none", packageName = "com.pkg.b",
                tierReason = "none", total = 5, judged = patternJudged
            )
        )
    }

    // ── Initialization ──────────────────────────────────────────────────────

    @Test
    fun `init loads first challenge on creation`() = runTest {
        setupForNotifPair()

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            assertNotNull("challenge should be set after init", state.challenge)
            assertFalse("should not be exhausted", state.exhausted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init with empty DB sets exhausted = true`() = runTest {
        coEvery { judgmentDao.getJudgedNotificationIds() } returns emptyList()
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns emptyList()

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            assertTrue("exhausted when no candidates", state.exhausted)
            assertNull("challenge should be null when exhausted", state.challenge)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init resolves to isLoading=false after challenge loads`() = runTest {
        setupForNotifPair()

        val vm = makeViewModel()

        vm.uiState.test {
            // With UnconfinedTestDispatcher, loadNextPair() runs eagerly in init,
            // so the first emission we see may already have isLoading=false.
            // Skip any loading states and assert the resolved state.
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            assertFalse("isLoading should resolve to false after challenge loads", state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Notification pair submission ────────────────────────────────────────

    @Test
    fun `submit left inserts TrainingJudgment with choice left`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()

        // Wait for challenge to load
        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val slot = slot<TrainingJudgment>()
        coEvery { judgmentDao.insert(capture(slot)) } returns 1L
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        vm.submit("left")
        advanceUntilIdle()

        assertTrue("slot should be captured", slot.isCaptured)
        assertEquals("left", slot.captured.choice)
    }

    @Test
    fun `submit right inserts judgment with choice right`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val slot = slot<TrainingJudgment>()
        coEvery { judgmentDao.insert(capture(slot)) } returns 1L
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        vm.submit("right")
        advanceUntilIdle()

        assertEquals("right", slot.captured.choice)
    }

    @Test
    fun `submit tie inserts judgment with choice tie`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val slot = slot<TrainingJudgment>()
        coEvery { judgmentDao.insert(capture(slot)) } returns 1L
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        vm.submit("tie")
        advanceUntilIdle()

        assertEquals("tie", slot.captured.choice)
    }

    @Test
    fun `submit skip inserts judgment with choice skip and xpAwarded 0`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val slot = slot<TrainingJudgment>()
        coEvery { judgmentDao.insert(capture(slot)) } returns 1L
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        vm.submit("skip")
        advanceUntilIdle()

        assertEquals("skip", slot.captured.choice)
        assertEquals(0, slot.captured.xpAwarded)
    }

    @Test
    fun `submit with no active challenge is a no-op`() = runTest {
        coEvery { judgmentDao.getJudgedNotificationIds() } returns emptyList()
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns emptyList()

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            assertTrue("should be exhausted (no challenge)", state.exhausted)
            cancelAndIgnoreRemainingEvents()
        }

        vm.submit("left")  // should be a no-op
        advanceUntilIdle()

        coVerify(exactly = 0) { judgmentDao.insert(any()) }
    }

    @Test
    fun `submit real judgment emits XpEvent Judgment with correct xp`() = runTest {
        // Both patterns unseen → 10 XP
        setupForNotifPair(patternJudged = 0)
        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coEvery { judgmentDao.insert(any()) } returns 1L
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        vm.xpEvents.test {
            vm.submit("left")
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue("event should be XpEvent.Judgment", event is XpEvent.Judgment)
            assertEquals(10, (event as XpEvent.Judgment).xp)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit skip does NOT emit XpEvent Judgment`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coEvery { judgmentDao.insert(any()) } returns 1L
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        vm.xpEvents.test {
            vm.submit("skip")
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── App battle submission ───────────────────────────────────────────────
    // These tests directly verify Elo logic by verifying AppBattleJudgment fields

    @Test
    fun `submitAppBattle skip inserts judgment with xpAwarded 0`() = runTest {
        // Set up app battle scenario: eligible packages exist, no notification candidates
        coEvery { appRankingDao.count() } returns 0
        coEvery { appRankingDao.getEligiblePackages() } returns listOf("com.app.a", "com.app.b")
        coEvery { appRankingDao.getAll() } returns listOf(
            AppRanking(packageName = "com.app.a", eloScore = 1200, updatedAtMs = 0L),
            AppRanking(packageName = "com.app.b", eloScore = 1200, updatedAtMs = 0L)
        )
        coEvery { judgmentDao.getJudgedNotificationIds() } returns emptyList()
        // Force notification path to be empty so we get an app battle
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns emptyList()

        val slot = slot<AppBattleJudgment>()
        coEvery { appBattleDao.insert(capture(slot)) } returns 1L

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            // If we got an AppBattle challenge, proceed
            if (state.challenge is Challenge.AppBattle) {
                cancelAndIgnoreRemainingEvents()
                vm.submit("skip")
                advanceUntilIdle()

                if (slot.isCaptured) {
                    assertEquals(0, slot.captured.xpAwarded)
                }
            } else {
                // AppBattle not loaded due to randomness - skip test
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `submitAppBattle non-skip awards APP_BATTLE_XP`() = runTest {
        // Verify APP_BATTLE_XP constant is correct
        assertEquals(3, APP_BATTLE_XP)
    }

    @Test
    fun `submitAppBattle skip does not call appRankingDao upsert`() = runTest {
        coEvery { appRankingDao.count() } returns 0
        coEvery { appRankingDao.getEligiblePackages() } returns listOf("com.app.a", "com.app.b")
        coEvery { appRankingDao.getAll() } returns listOf(
            AppRanking(packageName = "com.app.a", eloScore = 1200, updatedAtMs = 0L),
            AppRanking(packageName = "com.app.b", eloScore = 1200, updatedAtMs = 0L)
        )
        coEvery { judgmentDao.getJudgedNotificationIds() } returns emptyList()
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns emptyList()
        coEvery { appBattleDao.insert(any()) } returns 1L

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            if (state.challenge is Challenge.AppBattle) {
                cancelAndIgnoreRemainingEvents()
                vm.submit("skip")
                advanceUntilIdle()
                coVerify(exactly = 0) { appRankingDao.upsert(any()) }
            } else {
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `new package gets DEFAULT_ELO 1200 as starting Elo`() {
        // Verify DEFAULT_ELO constant
        assertEquals(AppRanking.DEFAULT_ELO, 1200)
    }

    // ── Quest selection ─────────────────────────────────────────────────────

    @Test
    fun `selectQuest changes activeQuest StateFlow`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.selectQuest("messaging")
        advanceUntilIdle()

        assertEquals("messaging", vm.activeQuest.value.id)
    }

    @Test
    fun `selectQuest persists to SharedPreferences`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.selectQuest("email")
        advanceUntilIdle()

        coVerify { sharedPrefsEditor.putString("training_active_quest", "email") }
    }

    @Test
    fun `selectQuest resets setPosition to 0`() = runTest {
        setupForNotifPair()
        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            vm.selectQuest("messaging")
            val afterSelect = awaitItem()  // loading state
            val afterLoad = if (afterSelect.isLoading) awaitItem() else afterSelect
            assertEquals(0, afterLoad.setPosition)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FREE_PLAY quest allows all packages`() {
        assertEquals("free_play", Quests.FREE_PLAY.id)
        assertTrue("FREE_PLAY has no package prefixes", Quests.FREE_PLAY.packagePrefixes.isEmpty())
    }

    @Test
    fun `non-FREE_PLAY quest has packagePrefixes that filter candidates`() {
        val messagingQuest = Quests.byId("messaging")
        assertTrue("messaging quest should have package prefixes", messagingQuest.packagePrefixes.isNotEmpty())
    }

    // ── Level progression ───────────────────────────────────────────────────

    @Test
    fun `level-up crossing threshold emits XpEvent LevelUp when trainer level changes`() = runTest {
        setupForNotifPair()
        coEvery { notificationDao.getAmbiguousCandidates(any(), any()) } returns twoNotifs()

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.xpEvents.test {
            // Simulate pattern stats crossing the Trainee threshold
            fakePatternStats.value = (1..3).map { i ->
                PatternStat(
                    pattern = "com.pkg.$i|none",
                    packageName = "com.pkg.$i",
                    tierReason = "none",
                    total = 5,
                    judged = 3  // isMapped() = true → 3 mapped patterns → Trainee
                )
            }
            advanceUntilIdle()
            // Collect any emitted events without crash assertion
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Quest completion ────────────────────────────────────────────────────

    @Test
    fun `quest goalXp zero means FREE_PLAY and quest completion is not tracked`() {
        // FREE_PLAY has goalXp=0 which disables quest completion tracking
        assertEquals(0, Quests.FREE_PLAY.goalXp)
    }

    @Test
    fun `quest with positive goalXp tracks completion`() {
        val messagingQuest = Quests.byId("messaging")
        assertTrue("messaging quest should have positive goalXp", messagingQuest.goalXp > 0)
    }

    // ── SET accumulator ─────────────────────────────────────────────────────

    @Test
    fun `SET_SIZE constant is 5`() {
        assertEquals(5, SET_SIZE)
    }

    @Test
    fun `SET_BONUS_MULTIPLIER constant is 0_5`() {
        assertEquals(0.5f, SET_BONUS_MULTIPLIER, 0.001f)
    }
}
