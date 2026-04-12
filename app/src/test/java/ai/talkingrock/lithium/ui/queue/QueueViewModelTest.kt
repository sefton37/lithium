package ai.talkingrock.lithium.ui.queue

import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.QueuedItem
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [QueueViewModel].
 *
 * Uses Turbine to subscribe to the StateFlow so WhileSubscribed activates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var queueDao: QueueDao
    private val fakePendingItems = MutableStateFlow<List<QueuedItem>>(emptyList())

    @Before
    fun setUp() {
        queueDao = mockk()
        every { queueDao.getPendingQueueItems() } returns fakePendingItems
    }

    private fun makeViewModel() = QueueViewModel(queueDao)

    private fun item(
        id: Long,
        pkg: String = "com.test.app"
    ) = QueuedItem(id = id, queuedAtMs = 1000L, status = "pending", packageName = pkg, title = null, text = null)

    // ── State ────────────────────────────────────────────────────────────────

    @Test
    fun `empty queue — uiState has empty list and isLoading false`() = runTest {
        fakePendingItems.value = emptyList()

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitItem()
            // First emission from the flow (not the loading initial value)
            // Skip initial loading state
            val finalState = if (state.isLoading) awaitItem() else state
            assertTrue("items should be empty", finalState.items.isEmpty())
            assertEquals(false, finalState.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `QueuedItem in DB — appears in UI state`() = runTest {
        fakePendingItems.value = listOf(item(1L, "com.test.app"))

        val vm = makeViewModel()

        vm.uiState.test {
            // Consume initial loading state if present
            var state = awaitItem()
            if (state.isLoading) state = awaitItem()
            assertEquals(1, state.items.size)
            assertEquals("com.test.app", state.items[0].packageName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismiss action calls markReviewed with dismissed action`() = runTest {
        fakePendingItems.value = listOf(item(5L))
        coEvery { queueDao.markReviewed(any(), any(), any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.dismiss(5L)
        advanceUntilIdle()

        coVerify { queueDao.markReviewed(5L, "dismissed", any()) }
    }

    @Test
    fun `clearReviewed calls queueDao clearReviewed`() = runTest {
        fakePendingItems.value = emptyList()
        coEvery { queueDao.clearReviewed() } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.clearReviewed()
        advanceUntilIdle()

        coVerify { queueDao.clearReviewed() }
    }

    @Test
    fun `queue flow is reactive — new row added to DB appears without explicit refresh`() = runTest {
        fakePendingItems.value = emptyList()

        val vm = makeViewModel()

        vm.uiState.test {
            // Drain initial states
            var state = awaitItem()
            while (state.isLoading) state = awaitItem()

            assertTrue("should be empty initially", state.items.isEmpty())

            // Simulate DB change
            fakePendingItems.value = listOf(item(2L, "com.new.app"))

            val updated = awaitItem()
            assertEquals(1, updated.items.size)
            assertEquals("com.new.app", updated.items[0].packageName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `release action calls markReviewed with actioned status`() = runTest {
        fakePendingItems.value = listOf(item(9L))
        coEvery { queueDao.markReviewed(any(), any(), any()) } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.release(9L)
        advanceUntilIdle()

        coVerify { queueDao.markReviewed(9L, "actioned", any()) }
    }
}
