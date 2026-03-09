package ai.talkingrock.lithium.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.ai.AiAnalysisWorker.Companion.DEFAULT_RETENTION_DAYS
import ai.talkingrock.lithium.ai.AiAnalysisWorker.Companion.PREF_RETENTION_DAYS
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.service.ListenerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Valid retention period options (in days) presented in the Settings screen.
 */
val RETENTION_OPTIONS = listOf(7, 14, 30, 60, 90)

/**
 * UI state for [SettingsScreen].
 *
 * @param notificationAccessGranted Whether BIND_NOTIFICATION_LISTENER_SERVICE is active.
 * @param usageAccessGranted Whether PACKAGE_USAGE_STATS is granted.
 * @param contactsGranted Whether READ_CONTACTS is granted.
 * @param retentionDays Current data-retention period in days.
 * @param notificationCount Approximate count of rows in the notifications table.
 * @param diagnosticsEnabled Whether anonymous diagnostics opt-in is enabled.
 * @param isPurging True while a purge operation is in progress.
 * @param purgeComplete True after a purge finishes (cleared after the snackbar is shown).
 */
data class SettingsUiState(
    val notificationAccessGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    val notificationCount: Int = 0,
    val diagnosticsEnabled: Boolean = false,
    val isPurging: Boolean = false,
    val purgeComplete: Boolean = false
)

/**
 * ViewModel for [SettingsScreen].
 *
 * Manages:
 * - Live permission status (reused from SetupViewModel logic)
 * - Data retention period stored in EncryptedSharedPreferences
 * - DB stats (notification row count)
 * - Purge-all-data operation across all mutable tables (not rules)
 * - Diagnostics opt-in toggle (preference only — no wiring yet in M6)
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listenerState: ListenerState,
    private val sharedPreferences: SharedPreferences,
    private val notificationDao: NotificationDao,
    private val sessionDao: SessionDao,
    private val reportDao: ReportDao,
    private val suggestionDao: SuggestionDao,
    private val queueDao: QueueDao
) : ViewModel() {

    companion object {
        private const val PREF_DIAGNOSTICS = "diagnostics_enabled"
        private const val APP_VERSION = "0.1.0"
    }

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            retentionDays = sharedPreferences.getInt(PREF_RETENTION_DAYS, DEFAULT_RETENTION_DAYS),
            diagnosticsEnabled = sharedPreferences.getBoolean(PREF_DIAGNOSTICS, false)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** One-shot events for snackbar messages. */
    val snackbarMessages = MutableSharedFlow<String>()

    val appVersion: String = APP_VERSION

    init {
        // Wire notification access (live) and permission checks (one-shot on init)
        viewModelScope.launch {
            combine(
                listenerState.isConnected,
                flow { emit(checkUsageAccess()) },
                flow { emit(checkContactsPermission()) },
                flow { emit(loadNotificationCount()) }
            ) { notificationGranted, usageGranted, contactsGranted, count ->
                _uiState.update { current ->
                    current.copy(
                        notificationAccessGranted = notificationGranted,
                        usageAccessGranted = usageGranted,
                        contactsGranted = contactsGranted,
                        notificationCount = count
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = Unit
            ).collect { /* collection drives updates via update{} above */ }
        }
    }

    /** Refreshes permission status and DB stats — call when returning from system Settings. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    notificationAccessGranted = listenerState.isConnected.value,
                    usageAccessGranted = checkUsageAccess(),
                    contactsGranted = checkContactsPermission(),
                    notificationCount = loadNotificationCount()
                )
            }
        }
    }

    /** Updates and persists the data-retention period. */
    fun setRetentionDays(days: Int) {
        sharedPreferences.edit().putInt(PREF_RETENTION_DAYS, days).apply()
        _uiState.update { it.copy(retentionDays = days) }
    }

    /** Toggles and persists the diagnostics opt-in preference. */
    fun setDiagnosticsEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(PREF_DIAGNOSTICS, enabled).apply()
        _uiState.update { it.copy(diagnosticsEnabled = enabled) }
    }

    /**
     * Purges all notification records, sessions, reports, suggestions, and queued items.
     * Rules are NOT deleted — those are user-configured and must be explicitly managed.
     */
    fun purgeAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPurging = true, purgeComplete = false) }
            try {
                notificationDao.deleteAll()
                sessionDao.deleteAll()
                reportDao.deleteAll()
                suggestionDao.deleteAll()
                queueDao.deleteAll()
                _uiState.update { it.copy(
                    isPurging = false,
                    purgeComplete = true,
                    notificationCount = 0
                ) }
                snackbarMessages.emit("All data purged.")
            } catch (e: Exception) {
                _uiState.update { it.copy(isPurging = false) }
                snackbarMessages.emit("Purge failed: ${e.message}")
            }
        }
    }

    /** Called after the snackbar has shown, so purgeComplete doesn't re-trigger on recompose. */
    fun acknowledgeSnackbar() {
        _uiState.update { it.copy(purgeComplete = false) }
    }

    // -----------------------------------------------------------------------------------------
    // Permission checks (duplicated from SetupViewModel — intentionally; Settings is standalone)
    // -----------------------------------------------------------------------------------------

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

    private suspend fun loadNotificationCount(): Int {
        return try {
            notificationDao.count()
        } catch (e: Exception) {
            0
        }
    }
}
