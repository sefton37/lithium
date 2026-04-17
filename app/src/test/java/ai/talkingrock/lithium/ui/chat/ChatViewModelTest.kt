package ai.talkingrock.lithium.ui.chat

import ai.talkingrock.lithium.ai.BriefingService
import ai.talkingrock.lithium.ai.ChatToolDispatcher
import ai.talkingrock.lithium.ai.LlamaEngine
import ai.talkingrock.lithium.ai.RuleExtractor
import ai.talkingrock.lithium.ai.ToolResult
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.repository.ReportRepository
import ai.talkingrock.lithium.data.repository.RuleRepository
import ai.talkingrock.lithium.ui.training.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:org.junit.Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var briefingService: BriefingService
    private lateinit var extractor: RuleExtractor
    private lateinit var ruleRepo: RuleRepository
    private lateinit var reportRepo: ReportRepository
    private lateinit var llama: LlamaEngine
    private lateinit var dispatcher: ChatToolDispatcher
    private lateinit var vm: ChatViewModel

    @Before fun setUp() {
        briefingService = mockk()
        extractor = mockk()
        ruleRepo = mockk()
        reportRepo = mockk()
        // Default: no unreviewed reports → reactive SuggestionPrompt collector sees
        // an empty stream, so every test starts with zero suggestion prompts unless
        // it overrides this stub.
        every { reportRepo.getLatestUnreviewed() } returns flowOf(null)
        llama = mockk(relaxed = true)
        dispatcher = mockk()
        every { llama.isModelLoaded() } returns true
        vm = ChatViewModel(
            briefingService,
            extractor,
            ruleRepo,
            reportRepo,
            llama,
            dispatcher,
            modelDir = "/tmp/models",
        )
    }

    @Test fun `invokeBriefing appends BriefingResult`() = runTest {
        val report = Report(
            id = 1,
            generatedAtMs = 1L,
            summaryJson = "{\"text\":\"Hello world\"}",
            reviewed = false,
        )
        coEvery { briefingService.generateReport(any(), any()) } returns BriefingService.Result(
            report = report,
            reportId = 1,
            suggestionCount = 0,
            allNotifications = emptyList(),
            sinceMs = 0L,
        )
        vm.invokeBriefing()
        advanceUntilIdle()
        val last = vm.state.value.messages.last()
        assertTrue(last is ChatMessage.BriefingResult)
        assertEquals("Hello world", (last as ChatMessage.BriefingResult).reportText)
    }

    @Test fun `submitInput starts rule creation when no draft exists`() = runTest {
        coEvery { extractor.extract(any(), any()) } returns RuleDraftState(originalInput = "mute slack")
        every { extractor.totalFields } returns 5
        vm.updateInputDraft("mute slack")
        vm.submitInput()
        advanceUntilIdle()
        val msgs = vm.state.value.messages
        assertTrue(msgs.any { it is ChatMessage.UserText })
        assertTrue(msgs.any { it is ChatMessage.RuleDraft })
    }

    @Test fun `approveRule inserts Rule with ai source and pending_review`() = runTest {
        val ruleSlot = slot<Rule>()
        coEvery { ruleRepo.insertRule(capture(ruleSlot)) } returns 42L
        val draft = RuleDraftState(
            originalInput = "mute slack",
            packageName = "com.slack",
            action = "suppress",
        )
        vm.approveRule(draft)
        advanceUntilIdle()
        coVerify { ruleRepo.insertRule(any()) }
        assertEquals("ai", ruleSlot.captured.source)
        assertEquals("pending_review", ruleSlot.captured.status)
        assertEquals("suppress", ruleSlot.captured.action)
    }

    @Test fun `approveRule with no filters posts system error and does not insert`() = runTest {
        val draft = RuleDraftState(originalInput = "do nothing")
        vm.approveRule(draft)
        advanceUntilIdle()
        coVerify(exactly = 0) { ruleRepo.insertRule(any()) }
        val last = vm.state.value.messages.last()
        assertTrue(last is ChatMessage.SystemMessage)
    }

    @Test fun `submitInput refines when a draft already exists`() = runTest {
        coEvery { extractor.extract(any(), any()) } returns RuleDraftState(
            originalInput = "mute slack",
            packageName = "com.slack",
        )
        coEvery { extractor.refine(any(), any(), any()) } returns RuleDraftState(
            originalInput = "mute slack. only work hours",
            packageName = "com.slack",
        )
        every { extractor.totalFields } returns 5

        vm.updateInputDraft("mute slack")
        vm.submitInput()
        advanceUntilIdle()
        vm.updateInputDraft("only work hours")
        vm.submitInput()
        advanceUntilIdle()

        coVerify { extractor.refine(any(), eq("only work hours"), any()) }
    }

    /** DOD-25: full VM round-trip with mocked dispatcher; state.messages contains AssistantAnswer. */
    @Test fun `qaAnswerAppendedAfterToolDispatch`() = runTest {
        coEvery { dispatcher.dispatch(any()) } returns ToolResult.Count(42)
        coEvery { llama.generate(any(), maxTokens = 32) } returns "TOOL: notificationCount\nARGS: {}"
        coEvery { llama.generate(any(), maxTokens = 256) } returns "You have 42 notifications."

        vm.submitQaInput("How many notifications do I have?")
        advanceUntilIdle()

        val msgs = vm.state.value.messages
        assertTrue("Expected AssistantAnswer in messages", msgs.any { it is ChatMessage.AssistantAnswer })
    }

    @Test fun `qaThinkingStateClearedAfterAnswer`() = runTest {
        coEvery { dispatcher.dispatch(any()) } returns ToolResult.Count(7)
        coEvery { llama.generate(any(), maxTokens = 32) } returns "TOOL: notificationCount\nARGS: {}"
        coEvery { llama.generate(any(), maxTokens = 256) } returns "You have 7 notifications."

        vm.submitQaInput("How many?")
        advanceUntilIdle()

        assertEquals(false, vm.state.value.isQaThinking)
    }

    @Test fun `noToolCallProducesRefusalMessage`() = runTest {
        coEvery { dispatcher.dispatch(any()) } returns ToolResult.NoToolCall
        coEvery { llama.generate(any(), maxTokens = 32) } returns "I am not sure."

        vm.submitQaInput("What is the meaning of life?")
        advanceUntilIdle()

        val msgs = vm.state.value.messages
        val answer = msgs.filterIsInstance<ChatMessage.AssistantAnswer>().last()
        assertTrue(
            "Refusal text expected",
            answer.text.contains("not sure", ignoreCase = true)
        )
    }
}
