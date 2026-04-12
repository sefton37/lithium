package ai.talkingrock.lithium.ui.setup

import android.content.SharedPreferences
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.service.ListenerState
import ai.talkingrock.lithium.ui.training.MainDispatcherRule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
 * Unit tests for [SetupViewModel].
 *
 * Uses Robolectric for Android context (NotificationManagerCompat, PowerManager).
 * Uses Turbine to subscribe to uiState so WhileSubscribed activates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var listenerState: ListenerState
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sharedPrefsEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        listenerState = ListenerState()  // real instance — it's a simple StateFlow wrapper
        sharedPrefsEditor = mockk(relaxed = true)
        sharedPrefs = mockk()

        every { sharedPrefs.getBoolean(any(), any()) } returns false
        every { sharedPrefs.getString(any(), any()) } returns null
        every { sharedPrefs.edit() } returns sharedPrefsEditor
        every { sharedPrefsEditor.putBoolean(any(), any()) } returns sharedPrefsEditor
        every { sharedPrefsEditor.apply() } just Runs
    }

    private fun makeViewModel(): SetupViewModel {
        val context = RuntimeEnvironment.getApplication()
        return SetupViewModel(context, listenerState, sharedPrefs)
    }

    // ── Onboarding ───────────────────────────────────────────────────────────

    @Test
    fun `onboardingComplete returns false when pref is false`() = runTest {
        every { sharedPrefs.getBoolean(Prefs.ONBOARDING_COMPLETE, false) } returns false

        val vm = makeViewModel()

        assertFalse("onboardingComplete should be false when pref is false", vm.onboardingComplete)
    }

    @Test
    fun `onboardingComplete returns true when pref is true`() = runTest {
        every { sharedPrefs.getBoolean(Prefs.ONBOARDING_COMPLETE, false) } returns true

        val vm = makeViewModel()

        assertTrue("onboardingComplete should be true when pref is true", vm.onboardingComplete)
    }

    @Test
    fun `markOnboardingComplete sets pref to true`() = runTest {
        val vm = makeViewModel()

        vm.markOnboardingComplete()

        verify { sharedPrefsEditor.putBoolean(Prefs.ONBOARDING_COMPLETE, true) }
        verify { sharedPrefsEditor.apply() }
    }

    // ── Permission state ─────────────────────────────────────────────────────

    @Test
    fun `uiState combines ListenerState isConnected with polled state`() = runTest {
        listenerState.onDisconnected()

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitItem()
            // ListenerState is not connected and Robolectric has no notification access
            assertFalse("notificationAccessGranted should be false when both sources are false",
                state.notificationAccessGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ListenerState connected means notificationAccessGranted is true`() = runTest {
        listenerState.onConnected()

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue("notificationAccessGranted should be true when listener is connected",
                state.notificationAccessGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contactsGranted false when permission not granted in Robolectric`() = runTest {
        // Robolectric does not grant permissions by default
        listenerState.onDisconnected()

        val vm = makeViewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertFalse("contactsGranted should be false by default in Robolectric",
                state.contactsGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh does not crash and permission state remains false`() = runTest {
        listenerState.onDisconnected()

        val vm = makeViewModel()

        // Subscribe to activate WhileSubscribed, verify initial state, then call refresh
        vm.uiState.test {
            val initial = awaitItem()
            assertFalse("initial state should not have notification access", initial.notificationAccessGranted)

            // refresh() re-polls permissions — should not throw or crash
            vm.refresh()
            advanceUntilIdle()

            // No new emission expected since permissions didn't change in Robolectric
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits new values when listener connects`() = runTest {
        listenerState.onDisconnected()

        val vm = makeViewModel()

        vm.uiState.test {
            val initial = awaitItem()
            assertFalse("should start as not accessible", initial.notificationAccessGranted)

            listenerState.onConnected()
            advanceUntilIdle()

            val updated = awaitItem()
            assertTrue("should be accessible after listener connects",
                updated.notificationAccessGranted)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
