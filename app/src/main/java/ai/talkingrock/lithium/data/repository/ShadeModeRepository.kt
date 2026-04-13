package ai.talkingrock.lithium.data.repository

import android.content.SharedPreferences
import ai.talkingrock.lithium.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the Shade Mode enabled flag.
 *
 * Wraps the EncryptedSharedPreferences boolean as a [StateFlow] backed by a
 * [MutableStateFlow] and a SharedPreferences.OnSharedPreferenceChangeListener.
 * The listener service may read [isEnabled] synchronously before any UI collector exists.
 *
 * Shade Mode defaults to ON from install. The persistent notification in the shade
 * (NOTIF_ID_PERSISTENT) acts as the user-visible indicator that Lithium is active,
 * satisfying the "user always knows" requirement. The user can turn Shade Mode off
 * at any time in Settings.
 */
@Singleton
class ShadeModeRepository @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    private val _isEnabled = MutableStateFlow(
        sharedPreferences.getBoolean(Prefs.SHADE_MODE_ENABLED, true)
    )

    // Listener kept alive as a field so it is not garbage-collected.
    @Suppress("ObjectLiteralToLambda")
    private val prefListener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            if (key == Prefs.SHADE_MODE_ENABLED) {
                _isEnabled.value = prefs.getBoolean(Prefs.SHADE_MODE_ENABLED, true)
            }
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    /**
     * Live state of the Shade Mode toggle. Read by LithiumNotificationListener
     * synchronously on the notification callback thread via [StateFlow.value].
     */
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /** Persist and broadcast the new shade-mode state. */
    fun setEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Prefs.SHADE_MODE_ENABLED, enabled)
            .apply()
        // prefListener picks up the change and updates _isEnabled
    }
}
