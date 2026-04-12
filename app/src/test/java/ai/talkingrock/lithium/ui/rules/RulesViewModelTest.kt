package ai.talkingrock.lithium.ui.rules

import ai.talkingrock.lithium.data.model.Rule as RuleModel
import ai.talkingrock.lithium.data.repository.RuleRepository
import ai.talkingrock.lithium.ui.training.MainDispatcherRule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for [RulesViewModel].
 *
 * Uses Turbine to subscribe to uiState so WhileSubscribed activates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var ruleRepository: RuleRepository
    private val fakeRules = MutableStateFlow<List<RuleModel>>(emptyList())

    @Before
    fun setUp() {
        ruleRepository = mockk()
        every { ruleRepository.getAll() } returns fakeRules
    }

    private fun makeViewModel() = RulesViewModel(ruleRepository)

    private fun rule(
        id: Long,
        status: String = "approved",
        conditionJson: String = """{"type":"package_match","packageName":"com.test"}"""
    ) = RuleModel(
        id = id,
        name = "Rule $id",
        conditionJson = conditionJson,
        action = "suppress",
        status = status,
        createdAtMs = id * 1000L
    )

    // ── State ────────────────────────────────────────────────────────────────

    @Test
    fun `rules list populated from DB`() = runTest {
        fakeRules.value = listOf(rule(1L), rule(2L))

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            assertEquals(2, state.rules.size)
            assertFalse("should not be loading", state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Test
    fun `deleteRule calls ruleRepository deleteRule with correct id`() = runTest {
        fakeRules.value = listOf(rule(3L))
        coEvery { ruleRepository.deleteRule(any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.deleteRule(rule(3L))
        advanceUntilIdle()

        coVerify { ruleRepository.deleteRule(3L) }
    }

    // ── Toggle ───────────────────────────────────────────────────────────────

    @Test
    fun `toggleRule approved to disabled calls toggleStatus with current status`() = runTest {
        val approvedRule = rule(1L, status = "approved")
        fakeRules.value = listOf(approvedRule)
        coEvery { ruleRepository.toggleStatus(any(), any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.toggleRule(approvedRule)
        advanceUntilIdle()

        coVerify { ruleRepository.toggleStatus(1L, "approved") }
    }

    @Test
    fun `toggleRule disabled to approved calls toggleStatus with disabled`() = runTest {
        val disabledRule = rule(2L, status = "disabled")
        fakeRules.value = listOf(disabledRule)
        coEvery { ruleRepository.toggleStatus(any(), any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.toggleRule(disabledRule)
        advanceUntilIdle()

        coVerify { ruleRepository.toggleStatus(2L, "disabled") }
    }

    @Test
    fun `toggleRule rejected rule is a no-op`() = runTest {
        val rejectedRule = rule(4L, status = "rejected")
        fakeRules.value = listOf(rejectedRule)

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.toggleRule(rejectedRule)
        advanceUntilIdle()

        coVerify(exactly = 0) { ruleRepository.toggleStatus(any(), any()) }
    }

    @Test
    fun `approvedRules StateFlow updates after toggle — fakeRules change propagates`() = runTest {
        fakeRules.value = listOf(rule(1L, status = "approved"))

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()
            assertEquals("approved", state.rules[0].status)

            // Simulate DB updating after toggle
            fakeRules.value = listOf(rule(1L, status = "disabled"))
            val updated = awaitItem()
            assertEquals("disabled", updated.rules[0].status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Expand toggle ─────────────────────────────────────────────────────────

    @Test
    fun `toggleExpanded sets expandedRuleId then clears on second toggle`() = runTest {
        fakeRules.value = listOf(rule(1L))

        val vm = makeViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            vm.toggleExpanded(1L)
            val afterFirst = awaitItem()
            assertEquals(1L, afterFirst.expandedRuleId)

            vm.toggleExpanded(1L)
            val afterSecond = awaitItem()
            assertNull("expandedRuleId should be null after second toggle", afterSecond.expandedRuleId)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
