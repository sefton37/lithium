package ai.talkingrock.lithium.ui.briefing

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.data.repository.ReportRepository
import ai.talkingrock.lithium.data.repository.RuleRepository
import ai.talkingrock.lithium.ui.training.MainDispatcherRule
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [BriefingViewModel].
 *
 * Uses Turbine for StateFlow subscription and MockK for repositories.
 * mockkStatic intercepts WorkManager.getInstance().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var reportRepository: ReportRepository
    private lateinit var ruleRepository: RuleRepository
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    // Fake flows
    private val fakeUnreviewed = MutableStateFlow<Report?>(null)
    private val fakeTierBreakdown = MutableStateFlow<List<TierCount>>(emptyList())
    private val fakePeriodicWork = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val fakeManualWork = MutableStateFlow<List<WorkInfo>>(emptyList())

    @Before
    fun setUp() {
        reportRepository = mockk()
        ruleRepository = mockk()
        notificationRepository = mockk()
        sharedPrefs = mockk()
        context = mockk()
        workManager = mockk()

        every { sharedPrefs.getBoolean(Prefs.DATA_READY_NOTIFIED, false) } returns false
        every { reportRepository.getLatestUnreviewed() } returns fakeUnreviewed
        every { notificationRepository.getTierBreakdownSince(any()) } returns fakeTierBreakdown

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow("lithium_ai_analysis") } returns fakePeriodicWork
        every { workManager.getWorkInfosForUniqueWorkFlow("lithium_ai_analysis_manual") } returns fakeManualWork
    }

    private fun makeViewModel() = BriefingViewModel(
        reportRepository, ruleRepository, notificationRepository, sharedPrefs, context
    )

    /** Skip initial loading emissions and return first non-loading state. */
    private suspend fun app.cash.turbine.ReceiveTurbine<BriefingUiState>.awaitNonLoading(): BriefingUiState {
        var state = awaitItem()
        while (state.isLoading) state = awaitItem()
        return state
    }

    // ── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `no report in DB — uiState report is null and suggestions is empty`() = runTest {
        fakeUnreviewed.value = null

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitNonLoading()
            assertNull("report should be null when DB has no unreviewed report", state.report)
            assertTrue("suggestions should be empty", state.suggestions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `report exists but no suggestions — uiState report populated suggestions empty`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(emptyList())

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitNonLoading()
            assertEquals(report, state.report)
            assertTrue("suggestions should be empty", state.suggestions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `report with 2 pending suggestions — suggestions list has 2 entries`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestions = listOf(
            Suggestion(id = 1L, reportId = 1L, status = "pending"),
            Suggestion(id = 2L, reportId = 1L, status = "pending")
        )
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(suggestions)

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitNonLoading()
            assertEquals(2, state.suggestions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Suggestion actions ──────────────────────────────────────────────────

    @Test
    fun `approveSuggestion creates rule via RuleRepository createFromSuggestion`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 1L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { ruleRepository.createFromSuggestion(any()) } returns 10L
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(any()) } returns 0
        coEvery { reportRepository.markReviewed(any()) } returns Unit

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.approveSuggestion(suggestion, 1L)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { ruleRepository.createFromSuggestion(suggestion) }
    }

    @Test
    fun `approveSuggestion updates suggestion status to approved`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 5L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { ruleRepository.createFromSuggestion(any()) } returns 10L
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(any()) } returns 0
        coEvery { reportRepository.markReviewed(any()) } returns Unit

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.approveSuggestion(suggestion, 1L)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { reportRepository.updateSuggestionStatus(5L, "approved", null) }
    }

    @Test
    fun `approveSuggestion last pending marks report reviewed`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 1L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { ruleRepository.createFromSuggestion(any()) } returns 10L
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(1L) } returns 0
        coEvery { reportRepository.markReviewed(any()) } returns Unit

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.approveSuggestion(suggestion, 1L)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { reportRepository.markReviewed(1L) }
    }

    @Test
    fun `rejectSuggestion updates status to rejected without creating rule`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 7L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(any()) } returns 1

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.rejectSuggestion(suggestion, 1L)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { reportRepository.updateSuggestionStatus(7L, "rejected", null) }
        coVerify(exactly = 0) { ruleRepository.createFromSuggestion(any()) }
    }

    @Test
    fun `rejectSuggestion last pending marks report reviewed`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 1L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(1L) } returns 0
        coEvery { reportRepository.markReviewed(any()) } returns Unit

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.rejectSuggestion(suggestion, 1L)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { reportRepository.markReviewed(1L) }
    }

    // ── Comment drafts ──────────────────────────────────────────────────────

    @Test
    fun `updateCommentDraft updates commentDrafts map`() = runTest {
        fakeUnreviewed.value = null

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()

            vm.updateCommentDraft(suggestionId = 3L, text = "my comment")

            val updated = awaitItem()
            assertEquals("my comment", updated.commentDrafts[3L])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `approveSuggestion with comment draft passes comment to updateSuggestionStatus`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 3L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { ruleRepository.createFromSuggestion(any()) } returns 1L
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(any()) } returns 0
        coEvery { reportRepository.markReviewed(any()) } returns Unit

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.updateCommentDraft(3L, "great idea")
            awaitItem() // drain the commentDraft update
            vm.approveSuggestion(suggestion, 1L)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { reportRepository.updateSuggestionStatus(3L, "approved", "great idea") }
    }

    @Test
    fun `approving a suggestion clears its comment draft`() = runTest {
        val report = Report(id = 1L, generatedAtMs = 1000L)
        val suggestion = Suggestion(id = 3L, reportId = 1L, status = "pending")
        fakeUnreviewed.value = report
        every { reportRepository.getPendingForReport(1L) } returns flowOf(listOf(suggestion))
        coEvery { ruleRepository.createFromSuggestion(any()) } returns 1L
        coEvery { reportRepository.updateSuggestionStatus(any(), any(), any()) } returns Unit
        coEvery { reportRepository.countPendingSuggestions(any()) } returns 0
        coEvery { reportRepository.markReviewed(any()) } returns Unit

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.updateCommentDraft(3L, "some text")
            awaitItem()  // drain draft update
            vm.approveSuggestion(suggestion, 1L)
            advanceUntilIdle()

            // Find the state where draft was cleared
            val finalState = expectMostRecentItem()
            assertFalse("comment draft should be cleared after approval", finalState.commentDrafts.containsKey(3L))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Comment expansion toggle ────────────────────────────────────────────

    @Test
    fun `toggleCommentExpanded first toggle sets expandedCommentId`() = runTest {
        fakeUnreviewed.value = null

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.toggleCommentExpanded(5L)
            val updated = awaitItem()
            assertEquals(5L, updated.expandedCommentId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleCommentExpanded second toggle on same id clears expandedCommentId`() = runTest {
        fakeUnreviewed.value = null

        val vm = makeViewModel()

        vm.uiState.test {
            awaitNonLoading()
            vm.toggleCommentExpanded(5L)
            awaitItem()  // first toggle
            vm.toggleCommentExpanded(5L)
            val afterSecond = awaitItem()
            assertNull("expandedCommentId should be null after second toggle", afterSecond.expandedCommentId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Analysis running state ──────────────────────────────────────────────

    @Test
    fun `analysisRunning is false when periodic worker is ENQUEUED`() = runTest {
        fakeUnreviewed.value = null
        val enqueuedInfo = mockk<WorkInfo> {
            every { state } returns WorkInfo.State.ENQUEUED
        }
        fakePeriodicWork.value = listOf(enqueuedInfo)
        every { workManager.getWorkInfosForUniqueWorkFlow("lithium_ai_analysis") } returns fakePeriodicWork
        every { workManager.getWorkInfosForUniqueWorkFlow("lithium_ai_analysis_manual") } returns fakeManualWork

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitNonLoading()
            assertFalse("periodic ENQUEUED should not mean running", state.analysisRunning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analysisRunning is true when periodic worker is RUNNING`() = runTest {
        fakeUnreviewed.value = null
        val runningInfo = mockk<WorkInfo> {
            every { state } returns WorkInfo.State.RUNNING
        }
        fakePeriodicWork.value = listOf(runningInfo)
        every { workManager.getWorkInfosForUniqueWorkFlow("lithium_ai_analysis") } returns fakePeriodicWork
        every { workManager.getWorkInfosForUniqueWorkFlow("lithium_ai_analysis_manual") } returns fakeManualWork

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitNonLoading()
            assertTrue("periodic RUNNING should set analysisRunning to true", state.analysisRunning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dataReady reflects DATA_READY_NOTIFIED pref`() = runTest {
        every { sharedPrefs.getBoolean(Prefs.DATA_READY_NOTIFIED, false) } returns true
        fakeUnreviewed.value = null

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitNonLoading()
            assertTrue("dataReady should reflect pref value", state.dataReady)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
