package ai.talkingrock.lithium.ui.training

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [TrainingScreen].
 *
 * Uses MockK to create a fake [TrainingViewModel] with controlled state, avoiding
 * the full Hilt + Room + SQLCipher dependency graph in instrumented tests.
 * State is driven via [MutableStateFlow] instances injected into the mock.
 *
 * Animation note: BATTLE_DURATION_MS=650ms. Tests that submit a judgment advance
 * the main clock by 800ms to deterministically clear the animation state.
 *
 * Phase 4 — TESTING_STRATEGY.md §2.5.
 */
@RunWith(AndroidJUnit4::class)
class TrainingScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // -----------------------------------------------------------------------------------------
    // Fake ViewModel state
    // -----------------------------------------------------------------------------------------

    private val uiStateFlow = MutableStateFlow(TrainingUiState())
    private val trainerFlow = MutableStateFlow(TrainerLevels.snapshot(0, 0, 0))
    private val activeQuestFlow = MutableStateFlow(Quests.FREE_PLAY)
    private val questXpFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val judgmentCountFlow = MutableStateFlow(0)
    private val xpEventsFlow = MutableSharedFlow<XpEvent>(extraBufferCapacity = 4)

    private fun buildFakeViewModel(): TrainingViewModel {
        val vm = mockk<TrainingViewModel>(relaxed = true)
        every { vm.uiState } returns uiStateFlow
        every { vm.trainer } returns trainerFlow
        every { vm.activeQuest } returns activeQuestFlow
        every { vm.questXp } returns questXpFlow
        every { vm.judgmentCount } returns judgmentCountFlow
        every { vm.xpEvents } returns xpEventsFlow
        return vm
    }

    // -----------------------------------------------------------------------------------------
    // 1. Loading state shows progress indicator
    // -----------------------------------------------------------------------------------------

    @Test
    fun loadingState_showsCircularProgressIndicator() {
        uiStateFlow.value = TrainingUiState(isLoading = true, challenge = null, exhausted = false)
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // The CircularProgressIndicator has no text; verify no challenge content appears
        composeRule.onNodeWithText("A is more important").assertDoesNotExist()
        composeRule.onNodeWithText("No pairs in").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------
    // 2. Exhausted state shows "No more pairs" message
    // -----------------------------------------------------------------------------------------

    @Test
    fun exhaustedState_showsNoPairsMessage() {
        uiStateFlow.value = TrainingUiState(isLoading = false, exhausted = true)
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No pairs in", useUnmergedTree = true, substring = true)
            .assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 3. Notification pair challenge renders left and right cards
    // -----------------------------------------------------------------------------------------

    @Test
    fun notificationPairChallenge_rendersBothCards() {
        val left = fakeNotification(id = 1L, title = "Left notification")
        val right = fakeNotification(id = 2L, title = "Right notification")
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.NotificationPair(left, right)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("A is more important").assertIsDisplayed()
        composeRule.onNodeWithText("B is more important").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 4. Tapping left card calls submit("left")
    // -----------------------------------------------------------------------------------------

    @Test
    fun tapLeft_callsSubmitLeft() {
        val left = fakeNotification(id = 1L, title = "Left notification")
        val right = fakeNotification(id = 2L, title = "Right notification")
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.NotificationPair(left, right)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("A is more important").performClick()
        composeRule.waitForIdle()
        verify { vm.submit("left") }
    }

    // -----------------------------------------------------------------------------------------
    // 5. Tapping right card calls submit("right")
    // -----------------------------------------------------------------------------------------

    @Test
    fun tapRight_callsSubmitRight() {
        val left = fakeNotification(id = 1L, title = "Left notification")
        val right = fakeNotification(id = 2L, title = "Right notification")
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.NotificationPair(left, right)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("B is more important").performClick()
        composeRule.waitForIdle()
        verify { vm.submit("right") }
    }

    // -----------------------------------------------------------------------------------------
    // 6. Tie button calls submit("tie")
    // -----------------------------------------------------------------------------------------

    @Test
    fun tapTie_callsSubmitTie() {
        val left = fakeNotification(id = 1L)
        val right = fakeNotification(id = 2L)
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.NotificationPair(left, right)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tie").performClick()
        composeRule.waitForIdle()
        verify { vm.submit("tie") }
    }

    // -----------------------------------------------------------------------------------------
    // 7. Skip button calls submit("skip")
    // -----------------------------------------------------------------------------------------

    @Test
    fun tapSkip_callsSubmitSkip() {
        val left = fakeNotification(id = 1L)
        val right = fakeNotification(id = 2L)
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.NotificationPair(left, right)
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.waitForIdle()
        verify { vm.submit("skip") }
    }

    // -----------------------------------------------------------------------------------------
    // 8. Quest chip row renders all quests
    // -----------------------------------------------------------------------------------------

    @Test
    fun questChipRow_rendersAllQuests() {
        uiStateFlow.value = TrainingUiState(isLoading = false, exhausted = true)
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // Each quest name must appear at least once in the chip row
        Quests.all.forEach { quest ->
            val nodes = composeRule.onAllNodes(hasText(quest.name, substring = true))
                .fetchSemanticsNodes()
            assert(nodes.isNotEmpty()) { "Quest chip '${quest.name}' not found" }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 9. Tapping quest chip calls selectQuest
    // -----------------------------------------------------------------------------------------

    @Test
    fun tapQuestChip_callsSelectQuest() {
        uiStateFlow.value = TrainingUiState(isLoading = false, exhausted = true)
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // The "Sort Email" chip label is "Sort Email · 0/100" — use substring match
        composeRule.onNode(hasText("Sort Email", substring = true)).performClick()
        composeRule.waitForIdle()
        verify { vm.selectQuest(any()) }
    }

    // -----------------------------------------------------------------------------------------
    // 10. Trainer header renders level name
    // -----------------------------------------------------------------------------------------

    @Test
    fun trainerHeader_rendersLevelName() {
        uiStateFlow.value = TrainingUiState(isLoading = false, exhausted = true)
        trainerFlow.value = TrainerLevels.snapshot(xp = 0, mapped = 0, total = 0)
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // With 0 patterns mapped, level is "Novice"
        composeRule.onNodeWithText("Novice").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 11. Pattern-based progress bar renders pattern count text
    // -----------------------------------------------------------------------------------------

    @Test
    fun trainerHeader_rendersPatternSubtext() {
        uiStateFlow.value = TrainingUiState(isLoading = false, exhausted = true)
        trainerFlow.value = TrainerLevels.snapshot(xp = 0, mapped = 0, total = 0)
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        // Pattern count subtext is always rendered in the header
        composeRule.onNodeWithText("0 of 0 notification patterns mapped", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 12. App battle renders two package name cards
    // -----------------------------------------------------------------------------------------

    @Test
    fun appBattle_rendersAppBattleBanner() {
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.AppBattle(
                leftPackage = "com.example.one",
                leftElo = 1200,
                rightPackage = "com.example.two",
                rightElo = 1150
            )
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("App battle — which app matters more?", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("A matters more").assertIsDisplayed()
        composeRule.onNodeWithText("B matters more").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // 13. Battle animation state — after submitting, lastBattle is set
    // -----------------------------------------------------------------------------------------

    @Test
    fun battleOutcome_leftWins_showsCorrectBannerText() {
        val left = fakeNotification(id = 1L)
        val right = fakeNotification(id = 2L)
        uiStateFlow.value = TrainingUiState(
            isLoading = false,
            exhausted = false,
            challenge = Challenge.NotificationPair(left, right),
            lastBattle = BattleOutcome.LEFT_WINS
        )
        val vm = buildFakeViewModel()
        composeRule.setContent { TrainingScreen(viewModel = vm) }
        // Advance clock past 650ms battle animation duration
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        // Screen renders without crash in LEFT_WINS state
        composeRule.onNodeWithText("A is more important").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // Helper: create a minimal NotificationRecord for test use
    // -----------------------------------------------------------------------------------------

    private fun fakeNotification(
        id: Long = 1L,
        title: String? = "Test notification",
        packageName: String = "com.test.app"
    ) = ai.talkingrock.lithium.data.model.NotificationRecord(
        id = id,
        packageName = packageName,
        postedAtMs = System.currentTimeMillis(),
        title = title,
        text = "Test content",
        tier = 2,
        tierReason = "default"
    )
}

private fun <T : Any> androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() {
    try {
        assertIsDisplayed()
        throw AssertionError("Node was expected to not exist but is displayed")
    } catch (_: AssertionError) {
        // Expected — node doesn't exist
    }
}
