package ai.talkingrock.lithium.ui.setup

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.service.ListenerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val contactsGranted: Boolean = false,
)

/**
 * ViewModel for [SetupScreen].
 *
 * Derives permission state from [ListenerState] (notification access) and
 * [AppOpsManager] (usage access). Contact permission is checked via
 * [PackageManager.checkPermission].
 *
 * [usageAccessGranted] and [contactsGranted] are polled as one-shot flows —
 * they update when the ViewModel is created. The user is expected to return
 * from Settings via back navigation which triggers recomposition.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listenerState: ListenerState
) : ViewModel() {

    val uiState: StateFlow<SetupUiState> = combine(
        listenerState.isConnected,
        flow { emit(checkUsageAccess()) },
        flow { emit(checkContactsPermission()) }
    ) { notificationGranted, usageGranted, contactsGranted ->
        SetupUiState(
            notificationAccessGranted = notificationGranted,
            usageAccessGranted = usageGranted,
            contactsGranted = contactsGranted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SetupUiState()
    )

    /** Refreshes usage access and contacts state (call when returning from Settings). */
    fun refresh(): SetupUiState = SetupUiState(
        notificationAccessGranted = listenerState.isConnected.value,
        usageAccessGranted = checkUsageAccess(),
        contactsGranted = checkContactsPermission(),
    )

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
