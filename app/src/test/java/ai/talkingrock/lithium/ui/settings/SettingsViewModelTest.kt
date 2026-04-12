package ai.talkingrock.lithium.ui.settings

import android.content.SharedPreferences
import androidx.work.WorkManager
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.service.ListenerState
import ai.talkingrock.lithium.ui.training.MainDispatcherRule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [SettingsViewModel].
 *
 * Uses Robolectric for Android context and MockK for DAOs.
 * uiState is a plain MutableStateFlow so .value is readable without a subscriber.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var listenerState: ListenerState
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sharedPrefsEditor: SharedPreferences.Editor
    private lateinit var notificationDao: NotificationDao
    private lateinit var sessionDao: SessionDao
    private lateinit var reportDao: ReportDao
    private lateinit var suggestionDao: SuggestionDao
    private lateinit var queueDao: QueueDao
    private lateinit var behaviorProfileDao: AppBehaviorProfileDao
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        listenerState = ListenerState()
        sharedPrefsEditor = mockk(relaxed = true)
        sharedPrefs = mockk()
        notificationDao = mockk()
        sessionDao = mockk()
        reportDao = mockk()
        suggestionDao = mockk()
        queueDao = mockk()
        behaviorProfileDao = mockk()
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager

        every { sharedPrefs.getBoolean(any(), any()) } returns false
        every { sharedPrefs.getInt(any(), any()) } returns Prefs.DEFAULT_RETENTION_DAYS
        every { sharedPrefs.getString(any(), any()) } returns null
        every { sharedPrefs.edit() } returns sharedPrefsEditor
        every { sharedPrefsEditor.putBoolean(any(), any()) } returns sharedPrefsEditor
        every { sharedPrefsEditor.putInt(any(), any()) } returns sharedPrefsEditor
        every { sharedPrefsEditor.apply() } just Runs

        coEvery { notificationDao.count() } returns 0
    }

    private fun makeViewModel(): SettingsViewModel {
        val context = RuntimeEnvironment.getApplication()
        return SettingsViewModel(
            context, listenerState, sharedPrefs,
            notificationDao, sessionDao, reportDao,
            suggestionDao, queueDao, behaviorProfileDao
        )
    }

    // ── Retention days ────────────────────────────────────────────────────────

    @Test
    fun `setRetentionDays updates uiState and persists pref`() = runTest {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.setRetentionDays(30)
        advanceUntilIdle()

        assertEquals(30, vm.uiState.value.retentionDays)
        verify { sharedPrefsEditor.putInt(Prefs.PREF_RETENTION_DAYS, 30) }
        verify { sharedPrefsEditor.apply() }
    }

    @Test
    fun `initial retentionDays reflects pref value`() = runTest {
        every { sharedPrefs.getInt(Prefs.PREF_RETENTION_DAYS, any()) } returns 60

        val vm = makeViewModel()
        advanceUntilIdle()

        assertEquals(60, vm.uiState.value.retentionDays)
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    @Test
    fun `setDiagnosticsEnabled true updates uiState and persists pref`() = runTest {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.setDiagnosticsEnabled(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.diagnosticsEnabled)
        verify { sharedPrefsEditor.putBoolean(Prefs.PREF_DIAGNOSTICS, true) }
        verify { sharedPrefsEditor.apply() }
    }

    @Test
    fun `setDiagnosticsEnabled false updates uiState`() = runTest {
        every { sharedPrefs.getBoolean(Prefs.PREF_DIAGNOSTICS, false) } returns true
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.setDiagnosticsEnabled(false)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.diagnosticsEnabled)
    }

    // ── Purge ─────────────────────────────────────────────────────────────────

    @Test
    fun `purgeAllData calls deleteAll on all DAOs`() = runTest {
        coEvery { notificationDao.deleteAll() } just Runs
        coEvery { sessionDao.deleteAll() } just Runs
        coEvery { reportDao.deleteAll() } just Runs
        coEvery { suggestionDao.deleteAll() } just Runs
        coEvery { queueDao.deleteAll() } just Runs
        coEvery { behaviorProfileDao.deleteAll() } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.purgeAllData()
        advanceUntilIdle()

        coVerify { notificationDao.deleteAll() }
        coVerify { sessionDao.deleteAll() }
        coVerify { reportDao.deleteAll() }
        coVerify { suggestionDao.deleteAll() }
        coVerify { queueDao.deleteAll() }
        coVerify { behaviorProfileDao.deleteAll() }
    }

    @Test
    fun `purgeAllData sets purgeComplete=true and notificationCount=0`() = runTest {
        coEvery { notificationDao.deleteAll() } just Runs
        coEvery { sessionDao.deleteAll() } just Runs
        coEvery { reportDao.deleteAll() } just Runs
        coEvery { suggestionDao.deleteAll() } just Runs
        coEvery { queueDao.deleteAll() } just Runs
        coEvery { behaviorProfileDao.deleteAll() } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.purgeAllData()
        advanceUntilIdle()

        assertTrue("purgeComplete should be true after purge", vm.uiState.value.purgeComplete)
        assertEquals(0, vm.uiState.value.notificationCount)
    }

    @Test
    fun `acknowledgeSnackbar clears purgeComplete`() = runTest {
        coEvery { notificationDao.deleteAll() } just Runs
        coEvery { sessionDao.deleteAll() } just Runs
        coEvery { reportDao.deleteAll() } just Runs
        coEvery { suggestionDao.deleteAll() } just Runs
        coEvery { queueDao.deleteAll() } just Runs
        coEvery { behaviorProfileDao.deleteAll() } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.purgeAllData()
        advanceUntilIdle()
        assertTrue("purgeComplete should be true after purge", vm.uiState.value.purgeComplete)

        vm.acknowledgeSnackbar()
        advanceUntilIdle()
        assertFalse("purgeComplete should be false after acknowledgeSnackbar", vm.uiState.value.purgeComplete)
    }

    // ── Worker constraints ────────────────────────────────────────────────────

    @Test
    fun `setRequireCharging updates uiState and persists pref`() = runTest {
        val vm = makeViewModel()
        advanceUntilIdle()

        // Note: rescheduleWorker() calls WorkManager.getInstance — no mock needed in Robolectric
        vm.uiState.test {
            awaitItem() // consume initial

            vm.setRequireCharging(true)
            advanceUntilIdle()

            val updated = awaitItem()
            assertTrue("requireCharging should be true", updated.requireCharging)
            verify { sharedPrefsEditor.putBoolean(Prefs.PREF_REQUIRE_CHARGING, true) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `notificationAccessGranted true when ListenerState is connected`() = runTest {
        listenerState.onConnected()

        val vm = makeViewModel()
        advanceUntilIdle()

        assertTrue("notificationAccessGranted should be true when listener connected",
            vm.uiState.value.notificationAccessGranted)
    }

    @Test
    fun `snackbarMessages emits after purge`() = runTest {
        coEvery { notificationDao.deleteAll() } just Runs
        coEvery { sessionDao.deleteAll() } just Runs
        coEvery { reportDao.deleteAll() } just Runs
        coEvery { suggestionDao.deleteAll() } just Runs
        coEvery { queueDao.deleteAll() } just Runs
        coEvery { behaviorProfileDao.deleteAll() } just Runs

        val vm = makeViewModel()
        advanceUntilIdle()

        vm.snackbarMessages.test {
            vm.purgeAllData()
            advanceUntilIdle()

            val msg = awaitItem()
            assertEquals("All data purged.", msg)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
