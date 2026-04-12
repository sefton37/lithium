package ai.talkingrock.lithium.ui.setup

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.service.ListenerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val contactsGranted: Boolean = false,
)

/**
 * ViewModel for [SetupScreen].
 *
 * Derives permission state from [ListenerState] (notification access, live via StateFlow)
 * and one-shot checks for battery optimization, usage access, and contacts.
 *
 * [refresh] must be called when the user returns from a system settings dialog
 * (battery exemption, usage access, contacts) so the UI reflects the updated state.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listenerState: ListenerState,
    private val sharedPreferences: SharedPreferences
) : ViewModel() {

    /** True if the user has previously completed the onboarding flow. */
    val onboardingComplete: Boolean
        get() = sharedPreferences.getBoolean(Prefs.ONBOARDING_COMPLETE, false)

    /** Mark onboarding as complete. Called when the user taps "Get Started" on the last page. */
    fun markOnboardingComplete() {
        sharedPreferences.edit()
            .putBoolean(Prefs.ONBOARDING_COMPLETE, true)
            .apply()
    }

    /** Holds the latest polled permission state for non-reactive checks. */
    private val _polledState = MutableStateFlow(pollPermissions())

    val uiState: StateFlow<SetupUiState> = combine(
        listenerState.isConnected,
        _polledState
    ) { liveConnected, polled ->
        // ListenerState.isConnected is false at cold start until the service reconnects,
        // so we OR in the synchronous system-level check (the authoritative source).
        // This mirrors MainActivity's routing logic and prevents the "needs notification
        // access" tile from flashing red when access is actually granted.
        val granted = liveConnected || polled.notificationAccessGranted
        polled.copy(notificationAccessGranted = granted)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SetupUiState()
    )

    /** Re-checks all non-reactive permissions. Call when returning from system dialogs. */
    fun refresh() {
        _polledState.update { pollPermissions() }
    }

    private fun pollPermissions(): SetupUiState = SetupUiState(
        notificationAccessGranted = checkNotificationAccess(),
        batteryOptimizationExempt = checkBatteryOptimization(),
        usageAccessGranted = checkUsageAccess(),
        contactsGranted = checkContactsPermission(),
    )

    private fun checkNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    private fun checkBatteryOptimization(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun checkUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
