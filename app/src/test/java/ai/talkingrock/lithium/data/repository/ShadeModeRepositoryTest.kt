package ai.talkingrock.lithium.data.repository

import android.content.SharedPreferences
import ai.talkingrock.lithium.data.Prefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ShadeModeRepository].
 *
 * Verifies the ON-by-default behaviour (fallback = true) and that toggling
 * the preference propagates correctly through the StateFlow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShadeModeRepositoryTest {

    private fun makeRepo(storedValue: Boolean? = null): Pair<ShadeModeRepository, SharedPreferences> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)

        // Simulate presence or absence of the stored value.
        // getBoolean(key, defaultValue): if storedValue is null the key is absent — Android
        // returns the defaultValue passed by the caller. We mirror this by returning the
        // default argument directly (secondArg() = the fallback).
        every { prefs.getBoolean(Prefs.SHADE_MODE_ENABLED, any()) } answers {
            storedValue ?: secondArg()
        }
        every { prefs.edit() } returns editor

        val repo = ShadeModeRepository(prefs)
        return Pair(repo, prefs)
    }

    // ── Default-on behaviour ──────────────────────────────────────────────────

    @Test
    fun `isEnabled defaults to true when key is absent`() {
        // Key is absent — getBoolean must return the fallback passed by the caller (true).
        // This confirms the ON-by-default policy: the repository calls
        // getBoolean(SHADE_MODE_ENABLED, true) and when the pref is absent, true is returned.
        val (repo, _) = makeRepo(storedValue = null)
        assertTrue(
            "Shade Mode must default to ON when the key has never been written",
            repo.isEnabled.value
        )
    }

    @Test
    fun `isEnabled is true when stored value is true`() {
        val (repo, _) = makeRepo(storedValue = true)
        assertTrue(repo.isEnabled.value)
    }

    @Test
    fun `isEnabled is false when stored value is false`() {
        // User explicitly turned off Shade Mode — must be respected.
        val (repo, _) = makeRepo(storedValue = false)
        assertFalse(repo.isEnabled.value)
    }

    // ── setEnabled ────────────────────────────────────────────────────────────

    @Test
    fun `setEnabled writes to SharedPreferences`() {
        val (repo, prefs) = makeRepo(storedValue = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        // putBoolean must return the same editor so the fluent chain .putBoolean().apply()
        // calls apply() on the same mock object that we can verify against.
        every { editor.putBoolean(any(), any()) } returns editor
        every { prefs.edit() } returns editor

        repo.setEnabled(false)

        verify { editor.putBoolean(Prefs.SHADE_MODE_ENABLED, false) }
        verify { editor.apply() }
    }
}
