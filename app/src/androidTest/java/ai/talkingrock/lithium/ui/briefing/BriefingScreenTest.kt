package ai.talkingrock.lithium.ui.briefing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [BriefingScreen].
 *
 * Uses MockK to create a fake [BriefingViewModel] with controlled state, avoiding
 * the full Hilt + Room + WorkManager dependency graph in instrumented tests.
 *
 * Each test sets up [BriefingUiState] directly and verifies the Compose tree renders
 * the correct elements. ViewModel action verification uses MockK's [verify] blocks.
 *
 * Phase 4 — TESTING_STRATEGY.md §2.5.
 */
@RunWith(AndroidJUnit4::class)
class BriefingScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // -----------------------------------------------------------------------------------------
    // Fake ViewModel state
    // -----------------------------------------------------------------------------------------

    private val uiStateFlow = MutableStateFlow(BriefingUiState(isLoading = false))

    private fun buildFakeViewModel(): BriefingViewModel {
        val vm = mockk<BriefingViewModel>(relaxed = true)
        every { vm.uiState } returns uiStateFlow
        return vm
    }

    // -----------------------------------------------------------------------------------------
    // 1. No report → "Lithium is learning" when dataReady=false
    // -----------------------------------------------------------------------------------------

    @Test
    fun noReport_dataReadyFalse_showsLithiumIsLearning() {
        uiStateFlow.value = BriefingUiState(isLoading = false, report = null, dataReady = false)
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Lithium is learning").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 2. No report + dataReady=true → "No new report."
    // -----------------------------------------------------------------------------------------

    @Test
    fun noReport_dataReadyTrue_showsNoNewReport() {
        uiStateFlow.value = BriefingUiState(isLoading = false, report = null, dataReady = true)
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No new report.").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 3. Report present → "Your Briefing" header
    // -----------------------------------------------------------------------------------------

    @Test
    fun reportPresent_showsYourBriefingHeader() {
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = fakeReport(),
            suggestions = emptyList()
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your Briefing").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 4. Report with text renders the report body
    // -----------------------------------------------------------------------------------------

    @Test
    fun reportPresent_rendersReportText() {
        val reportText = "Lithium has analyzed your notifications."
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = fakeReport(summaryText = reportText),
            suggestions = emptyList()
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(reportText, useUnmergedTree = true).assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 5. Suggestion card shows Approve and Reject buttons
    // -----------------------------------------------------------------------------------------

    @Test
    fun suggestionCard_showsApproveAndRejectButtons() {
        val suggestion = fakeSuggestion(id = 1L, rationale = "Block LinkedIn notifications")
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = fakeReport(),
            suggestions = listOf(suggestion)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Yes, try it").assertIsDisplayed()
        composeRule.onNodeWithText("No thanks").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 6. Suggestion rationale appears in the card
    // -----------------------------------------------------------------------------------------

    @Test
    fun suggestionCard_rendersRationale() {
        val suggestion = fakeSuggestion(id = 1L, rationale = "Suppress LinkedIn marketing")
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = fakeReport(),
            suggestions = listOf(suggestion)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Suppress LinkedIn marketing", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 7. Approving a suggestion calls approveSuggestion on the ViewModel
    // -----------------------------------------------------------------------------------------

    @Test
    fun approveSuggestion_callsViewModelApprove() {
        val report = fakeReport(id = 10L)
        val suggestion = fakeSuggestion(id = 1L, reportId = 10L, rationale = "Suppress LinkedIn")
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = report,
            suggestions = listOf(suggestion)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Yes, try it").performClick()
        composeRule.waitForIdle()
        verify { vm.approveSuggestion(suggestion, report.id) }
    }

    // -----------------------------------------------------------------------------------------
    // 8. Rejecting a suggestion calls rejectSuggestion on the ViewModel
    // -----------------------------------------------------------------------------------------

    @Test
    fun rejectSuggestion_callsViewModelReject() {
        val report = fakeReport(id = 10L)
        val suggestion = fakeSuggestion(id = 1L, reportId = 10L, rationale = "Suppress Amazon")
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = report,
            suggestions = listOf(suggestion)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No thanks").performClick()
        composeRule.waitForIdle()
        verify { vm.rejectSuggestion(suggestion, report.id) }
    }

    // -----------------------------------------------------------------------------------------
    // 9. Analysis running banner is shown when analysisRunning=true
    // -----------------------------------------------------------------------------------------

    @Test
    fun analysisRunningBanner_visibleWhenRunning() {
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = null,
            analysisRunning = true
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // The banner shows "Analyzing your notifications…" when analysisRunning=true
        composeRule.onNodeWithText(
            "Analyzing your notifications…",
            useUnmergedTree = true
        ).assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 10. Tier breakdown card shows when tierBreakdown24h is non-empty
    // -----------------------------------------------------------------------------------------

    @Test
    fun tierBreakdown_visibleWhenDataPresent() {
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = fakeReport(),
            suggestions = emptyList(),
            tierBreakdown24h = mapOf(0 to 5, 1 to 3, 2 to 10, 3 to 2)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Last 24 hours").assertIsDisplayed()
        composeRule.onNodeWithText("Interrupt").assertIsDisplayed()
        composeRule.onNodeWithText("Worth").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 11. Empty tier breakdown: card not shown when map is empty
    // -----------------------------------------------------------------------------------------

    @Test
    fun tierBreakdown_notShownWhenEmpty() {
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = fakeReport(),
            suggestions = emptyList(),
            tierBreakdown24h = emptyMap()
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // "Last 24 hours" header only appears inside TierBreakdownCard
        composeRule.onNodeWithText("Last 24 hours").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------
    // 12. Comment draft toggle → calls toggleCommentExpanded on ViewModel
    // -----------------------------------------------------------------------------------------

    @Test
    fun addComment_toggleCallsViewModelToggle() {
        val report = fakeReport(id = 10L)
        val suggestion = fakeSuggestion(id = 1L, reportId = 10L, rationale = "Suppress noise")
        uiStateFlow.value = BriefingUiState(
            isLoading = false,
            report = report,
            suggestions = listOf(suggestion)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { BriefingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add comment").performClick()
        composeRule.waitForIdle()
        verify { vm.toggleCommentExpanded(suggestion.id) }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun fakeReport(
        id: Long = 1L,
        summaryText: String = "Test analysis complete."
    ) = Report(
        id = id,
        generatedAtMs = System.currentTimeMillis(),
        summaryJson = """{"text":"$summaryText"}""",
        reviewed = false
    )

    private fun fakeSuggestion(
        id: Long = 1L,
        reportId: Long = 1L,
        rationale: String = "Test suggestion",
        action: String = "suppress"
    ) = Suggestion(
        id = id,
        reportId = reportId,
        conditionJson = """{"type":"package_match","packageName":"com.test.app"}""",
        action = action,
        rationale = rationale,
        status = "pending"
    )
}
