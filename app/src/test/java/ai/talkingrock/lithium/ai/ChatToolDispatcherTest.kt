package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ChatToolDispatcher].
 *
 * DOD-11: [unknownToolCallReturnsRefusal] — unknown tool name → [ToolResult.Unknown].
 * DOD-24: [noToolCallProducesRefusalNotHallucination] — no TOOL: line → [ToolResult.NoToolCall].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatToolDispatcherTest {

    private lateinit var repo: NotificationRepository
    private lateinit var dispatcher: ChatToolDispatcher

    @Before fun setUp() {
        repo = mockk()
        dispatcher = ChatToolDispatcher(repo)
    }

    // ---------------------------------------------------------------------------
    // DOD-11: Unknown tool name → ToolResult.Unknown
    // ---------------------------------------------------------------------------

    /**
     * DOD-11: When the model emits a TOOL: line with an unrecognised name,
     * dispatch() must return [ToolResult.Unknown] — not a fabricated answer.
     */
    @Test fun unknownToolCallReturnsRefusal() = runTest {
        val modelOutput = """
            TOOL: completelyMadeUpTool
            ARGS: {}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(
            "Expected ToolResult.Unknown for unrecognised tool name",
            result is ToolResult.Unknown
        )
    }

    @Test fun toolNameNoneReturnsUnknown() = runTest {
        val modelOutput = """
            TOOL: none
            ARGS: {}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        // "none" is what the model writes when no tool fits — maps to null in resolveTool → Unknown
        assertTrue(result is ToolResult.Unknown)
    }

    // ---------------------------------------------------------------------------
    // DOD-24: No TOOL: line → ToolResult.NoToolCall (refusal, not hallucination)
    // ---------------------------------------------------------------------------

    /**
     * DOD-24: When model output contains no parseable TOOL: line, dispatch() must
     * return [ToolResult.NoToolCall] — the caller must show the refusal text, not
     * forward the raw model output as an answer.
     */
    @Test fun noToolCallProducesRefusalNotHallucination() = runTest {
        val modelOutput = "I'm not sure what you mean. Tell me more about your question."

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(
            "Expected ToolResult.NoToolCall when model emits no TOOL: line",
            result is ToolResult.NoToolCall
        )
        // Also confirm: REFUSAL_TEXT is a non-empty string constant (what the VM shows to the user)
        assertTrue(ChatToolDispatcher.REFUSAL_TEXT.isNotBlank())
        assertFalse(
            "REFUSAL_TEXT must not contain a number that could be mistaken for a count",
            ChatToolDispatcher.REFUSAL_TEXT.any { it.isDigit() }
        )
    }

    @Test fun emptyModelOutputProducesNoToolCall() = runTest {
        val result = dispatcher.dispatch("")

        assertTrue(result is ToolResult.NoToolCall)
    }

    // ---------------------------------------------------------------------------
    // Happy-path dispatch for known tools
    // ---------------------------------------------------------------------------

    @Test fun dispatchesNotificationCount() = runTest {
        coEvery { repo.getCount() } returns 42

        val modelOutput = """
            TOOL: notificationCount
            ARGS: {}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.Count)
        assertEquals(42, (result as ToolResult.Count).value)
    }

    @Test fun dispatchesNotificationsSinceWithArgs() = runTest {
        coEvery { repo.getAllSince(any()) } returns emptyList()

        val modelOutput = """
            TOOL: notificationsSince
            ARGS: {"hours": 48}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.NotificationList)
    }

    @Test fun dispatchesTierBreakdown() = runTest {
        coEvery { repo.getTierBreakdown() } returns listOf(TierCount(2, 100))

        val modelOutput = """
            TOOL: tierBreakdown
            ARGS: {}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.TierSummary)
    }

    @Test fun dispatchesTopApps() = runTest {
        coEvery { repo.getTopAppsByCount(any()) } returns emptyList()

        val modelOutput = """
            TOOL: topApps
            ARGS: {"limit": 5}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.AppCountList)
    }

    @Test fun dispatchesNotificationsByApp() = runTest {
        coEvery { repo.getByPackageSuspend("com.slack", any()) } returns listOf(
            NotificationRecord(id = 1L, packageName = "com.slack")
        )

        val modelOutput = """
            TOOL: notificationsByApp
            ARGS: {"packageName": "com.slack"}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.NotificationList)
        assertEquals(1, (result as ToolResult.NotificationList).rows.size)
    }

    @Test fun missingArgsLineUsesDefaults() = runTest {
        // notificationsSince with no ARGS: line → defaults to 24 hours
        coEvery { repo.getAllSince(any()) } returns emptyList()

        val modelOutput = "TOOL: notificationsSince"

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.NotificationList)
    }

    @Test fun toolNameIsCaseInsensitive() = runTest {
        coEvery { repo.getCount() } returns 3

        val modelOutput = """
            TOOL: NotificationCount
            ARGS: {}
        """.trimIndent()

        val result = dispatcher.dispatch(modelOutput)

        assertTrue(result is ToolResult.Count)
    }
}
