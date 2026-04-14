package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.db.AppCount
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ChatQueryTool] execute() functions.
 *
 * Each test seeds a mock [NotificationRepository] with controlled data and asserts
 * that the tool result contains exactly what the repository returned — proving that
 * the tool layer does not fabricate or transform data.
 *
 * DOD-23: [notificationCountMatchesSeedData] — seed 7 notifications, assert answer contains "7".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatQueryToolsTest {

    private lateinit var repo: NotificationRepository

    @Before fun setUp() {
        repo = mockk()
    }

    // ---------------------------------------------------------------------------
    // NotificationCount
    // ---------------------------------------------------------------------------

    /**
     * DOD-23: Seeded repo returns 7; ToolResult.Count must carry value 7 and
     * toPromptString() must contain the string "7".
     */
    @Test fun notificationCountMatchesSeedData() = runTest {
        coEvery { repo.getCount() } returns 7

        val result = ChatQueryTool.NotificationCount.execute(repo)

        assertTrue("result must be Count", result is ToolResult.Count)
        val count = result as ToolResult.Count
        assertEquals(7, count.value)
        assertTrue(
            "toPromptString must contain '7'",
            count.toPromptString().contains("7")
        )
    }

    @Test fun notificationCountZeroIsValid() = runTest {
        coEvery { repo.getCount() } returns 0

        val result = ChatQueryTool.NotificationCount.execute(repo) as ToolResult.Count
        assertEquals(0, result.value)
        assertTrue(result.toPromptString().contains("0"))
    }

    // ---------------------------------------------------------------------------
    // NotificationsSince
    // ---------------------------------------------------------------------------

    @Test fun notificationsSinceReturnsListResult() = runTest {
        val fakeRows = (1..5).map { i ->
            NotificationRecord(id = i.toLong(), packageName = "com.app$i")
        }
        coEvery { repo.getAllSince(any()) } returns fakeRows

        val result = ChatQueryTool.NotificationsSince(hours = 24).execute(repo)

        assertTrue(result is ToolResult.NotificationList)
        val list = result as ToolResult.NotificationList
        assertEquals(5, list.rows.size)
        assertTrue(list.toPromptString().contains("com.app1"))
    }

    @Test fun notificationsSinceTruncatesAtMaxRows() = runTest {
        val tooMany = (1..25).map { i ->
            NotificationRecord(id = i.toLong(), packageName = "com.app$i")
        }
        coEvery { repo.getAllSince(any()) } returns tooMany

        val result = ChatQueryTool.NotificationsSince(hours = 1).execute(repo)
            as ToolResult.NotificationList

        assertEquals(ChatQueryTool.MAX_RESULT_ROWS, result.rows.size)
        assertTrue(result.truncated)
    }

    // ---------------------------------------------------------------------------
    // TierBreakdown
    // ---------------------------------------------------------------------------

    @Test fun tierBreakdownReturnsTierSummary() = runTest {
        val tiers = listOf(TierCount(0, 10), TierCount(1, 20), TierCount(2, 30), TierCount(3, 5))
        coEvery { repo.getTierBreakdown() } returns tiers

        val result = ChatQueryTool.TierBreakdown.execute(repo)

        assertTrue(result is ToolResult.TierSummary)
        val summary = result as ToolResult.TierSummary
        assertEquals(4, summary.tiers.size)
        val prompt = summary.toPromptString()
        assertTrue(prompt.contains("10"))
        assertTrue(prompt.contains("30"))
    }

    // ---------------------------------------------------------------------------
    // TopApps
    // ---------------------------------------------------------------------------

    @Test fun topAppsReturnsAppCountList() = runTest {
        val apps = listOf(
            AppCount("com.slack", 50),
            AppCount("com.whatsapp", 30),
        )
        coEvery { repo.getTopAppsByCount(any()) } returns apps

        val result = ChatQueryTool.TopApps(limit = 5).execute(repo)

        assertTrue(result is ToolResult.AppCountList)
        val list = result as ToolResult.AppCountList
        assertEquals(2, list.apps.size)
        assertTrue(list.toPromptString().contains("com.slack"))
        assertTrue(list.toPromptString().contains("50"))
    }

    // ---------------------------------------------------------------------------
    // NotificationsByApp
    // ---------------------------------------------------------------------------

    @Test fun notificationsByAppFiltersToPackage() = runTest {
        val rows = listOf(
            NotificationRecord(id = 1L, packageName = "com.slack", title = "Alice"),
            NotificationRecord(id = 2L, packageName = "com.slack", title = "Bob"),
        )
        coEvery { repo.getByPackageSuspend("com.slack", any()) } returns rows

        val result = ChatQueryTool.NotificationsByApp("com.slack").execute(repo)

        assertTrue(result is ToolResult.NotificationList)
        val list = result as ToolResult.NotificationList
        assertEquals(2, list.rows.size)
        assertTrue(list.toPromptString().contains("com.slack"))
    }
}
