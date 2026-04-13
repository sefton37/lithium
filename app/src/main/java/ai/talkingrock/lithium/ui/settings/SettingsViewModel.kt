package ai.talkingrock.lithium.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import ai.talkingrock.lithium.ai.WorkScheduler
import ai.talkingrock.lithium.ai.scoring.ScoringRefit
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.ImplicitJudgmentDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.ShadeModeSeeder
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.repository.ShadeModeRepository
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val retentionDays: Int = Prefs.DEFAULT_RETENTION_DAYS,
    val notificationCount: Int = 0,
    val diagnosticsEnabled: Boolean = false,
    val isPurging: Boolean = false,
    val purgeComplete: Boolean = false,
    val requireCharging: Boolean = Prefs.DEFAULT_REQUIRE_CHARGING,
    val requireBatteryNotLow: Boolean = Prefs.DEFAULT_REQUIRE_BATTERY_NOT_LOW,
    val requireIdle: Boolean = Prefs.DEFAULT_REQUIRE_IDLE,
    val isRunningAnalysis: Boolean = false,
    /**
     * Whether Shade Mode is enabled. Defaults to true — Shade Mode is ON from install.
     * The ViewModel always initialises from [ShadeModeRepository.isEnabled] on construction,
     * so this default is rarely seen in practice.
     */
    val shadeModeEnabled: Boolean = true,
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
    private val queueDao: QueueDao,
    private val behaviorProfileDao: AppBehaviorProfileDao,
    private val shadeModeRepository: ShadeModeRepository,
    private val shadeModeSeeder: ShadeModeSeeder,
    private val scoringRefit: ScoringRefit,
    private val implicitJudgmentDao: ImplicitJudgmentDao,
) : ViewModel() {

    companion object {
        private const val APP_VERSION = "0.1.0"
        private const val TAG = "SettingsViewModel"
        private const val TAG_DEV_REFIT = "DevRefit"
        private const val TAG_DEV_IMPLICIT = "DevImplicit"
        private const val DEV_IMPLICIT_LIMIT = 20
    }

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            retentionDays = sharedPreferences.getInt(Prefs.PREF_RETENTION_DAYS, Prefs.DEFAULT_RETENTION_DAYS),
            diagnosticsEnabled = sharedPreferences.getBoolean(Prefs.PREF_DIAGNOSTICS, false),
            requireCharging = sharedPreferences.getBoolean(Prefs.PREF_REQUIRE_CHARGING, Prefs.DEFAULT_REQUIRE_CHARGING),
            requireBatteryNotLow = sharedPreferences.getBoolean(Prefs.PREF_REQUIRE_BATTERY_NOT_LOW, Prefs.DEFAULT_REQUIRE_BATTERY_NOT_LOW),
            requireIdle = sharedPreferences.getBoolean(Prefs.PREF_REQUIRE_IDLE, Prefs.DEFAULT_REQUIRE_IDLE),
            shadeModeEnabled = shadeModeRepository.isEnabled.value,
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

        // Track shade mode toggle changes from the repository (e.g., changed by the listener service).
        viewModelScope.launch {
            shadeModeRepository.isEnabled.collect { enabled ->
                _uiState.update { it.copy(shadeModeEnabled = enabled) }
            }
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
        sharedPreferences.edit().putInt(Prefs.PREF_RETENTION_DAYS, days).apply()
        _uiState.update { it.copy(retentionDays = days) }
    }

    /**
     * Toggles Shade Mode and persists it via [ShadeModeRepository].
     *
     * Fix #8: If [enabled] is true but notification access is not currently granted, the
     * repository is NOT written. A snackbar message is emitted instead, guiding the user
     * to grant access first. This prevents the toggle appearing ON while nothing is
     * happening (listener disconnected = no filtering).
     *
     * On the first successful enable, triggers [ShadeModeSeeder.seedIfNeeded] to insert the
     * four default tier-based rules. Subsequent enables are no-ops in the seeder.
     */
    fun setShadeModeEnabled(enabled: Boolean) {
        if (enabled && !listenerState.isConnected.value) {
            // Refuse to enable — emit guidance instead of silently writing a no-op state.
            viewModelScope.launch {
                snackbarMessages.emit(
                    "Grant notification access first (see Permissions below), then enable Shade Mode."
                )
            }
            return
        }
        shadeModeRepository.setEnabled(enabled)
        // _uiState is updated reactively via the isEnabled collector in init.
        if (enabled) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    shadeModeSeeder.seedIfNeeded()
                } catch (e: Exception) {
                    // Log but do not surface to user — the listener service will retry
                    // seedIfNeeded() in onListenerConnected() as a self-heal path.
                    android.util.Log.e(TAG, "seedIfNeeded failed in setShadeModeEnabled", e)
                }
            }
        }
    }

    /** Toggles and persists the diagnostics opt-in preference. */
    fun setDiagnosticsEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(Prefs.PREF_DIAGNOSTICS, enabled).apply()
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
                behaviorProfileDao.deleteAll()
                // Reset data-readiness flag so it re-fires after new data accumulates
                sharedPreferences.edit()
                    .putBoolean(Prefs.DATA_READY_NOTIFIED, false)
                    .apply()
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
    // Worker constraint settings
    // -----------------------------------------------------------------------------------------

    fun setRequireCharging(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(Prefs.PREF_REQUIRE_CHARGING, enabled).apply()
        _uiState.update { it.copy(requireCharging = enabled) }
        rescheduleWorker()
    }

    fun setRequireBatteryNotLow(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(Prefs.PREF_REQUIRE_BATTERY_NOT_LOW, enabled).apply()
        _uiState.update { it.copy(requireBatteryNotLow = enabled) }
        rescheduleWorker()
    }

    fun setRequireIdle(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(Prefs.PREF_REQUIRE_IDLE, enabled).apply()
        _uiState.update { it.copy(requireIdle = enabled) }
        rescheduleWorker()
    }

    /** Immediately runs the AI analysis pipeline (no constraints). */
    fun runAnalysisNow() {
        _uiState.update { it.copy(isRunningAnalysis = true) }
        val workManager = WorkManager.getInstance(context)
        WorkScheduler.runNow(workManager)
        viewModelScope.launch {
            snackbarMessages.emit("Analysis started — check Briefing when complete.")
            // Reset the flag after a brief delay so the button re-enables
            kotlinx.coroutines.delay(3_000)
            _uiState.update { it.copy(isRunningAnalysis = false) }
        }
    }

    private fun rescheduleWorker() {
        val workManager = WorkManager.getInstance(context)
        WorkScheduler.rescheduleWithNewConstraints(workManager, sharedPreferences)
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

    // -----------------------------------------------------------------------------------------
    // Dev / diagnostics actions (debug builds only)
    // -----------------------------------------------------------------------------------------

    /**
     * Triggers a full scoring refit immediately, bypassing the debounce threshold.
     * Logs outcome via [TAG_DEV_REFIT] so it shows up in `adb logcat -s DevRefit`.
     *
     * Because the debounce check inside [ScoringRefit.refit] compares the current
     * training_judgment count against the last-saved count, we temporarily zero out the
     * saved count so the refit always runs when invoked from dev tools.
     */
    fun devRunScoringRefit() {
        viewModelScope.launch {
            Log.i(TAG_DEV_REFIT, "devRunScoringRefit: invoked from dev menu — forcing refit")
            try {
                // Override debounce: zero out the saved count so delta always >= threshold.
                sharedPreferences.edit()
                    .putInt(Prefs.REFIT_LAST_JUDGMENT_COUNT, 0)
                    .apply()
                scoringRefit.refit()
                val implicitCount = try { implicitJudgmentDao.count() } catch (_: Exception) { -1 }
                Log.i(TAG_DEV_REFIT, "devRunScoringRefit: refit completed — implicit count=$implicitCount")
                snackbarMessages.emit("Scoring refit complete — see logcat DevRefit")
            } catch (e: Exception) {
                Log.e(TAG_DEV_REFIT, "devRunScoringRefit: refit failed", e)
                snackbarMessages.emit("Scoring refit failed: ${e.message}")
            }
        }
    }

    /**
     * Loads the [DEV_IMPLICIT_LIMIT] most-recent implicit judgment rows and logs each
     * one via [TAG_DEV_IMPLICIT]. Also logs the total row count as a header.
     *
     * Each log line format:
     * `ts=<ISO> kind=<K> winner=<pkg>/<ch>[rank=<R>] loser=<pkg>/<ch>[rank=<R>] cohort=<N> screen=<B>`
     */
    fun devDumpRecentImplicitJudgments() {
        viewModelScope.launch {
            try {
                val total = implicitJudgmentDao.count()
                val recent = implicitJudgmentDao.getRecent(DEV_IMPLICIT_LIMIT)
                Log.i(TAG_DEV_IMPLICIT, "devDump: total implicit_judgments=$total, showing last ${recent.size}")
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                for (j in recent) {
                    val ts = fmt.format(Date(j.createdAtMs))
                    Log.i(
                        TAG_DEV_IMPLICIT,
                        "ts=$ts kind=${j.kind} " +
                            "winner=${j.winnerPackage}/${j.winnerChannelId}[rank=${j.winnerRank}] " +
                            "loser=${j.loserPackage}/${j.loserChannelId}[rank=${j.loserRank}] " +
                            "cohort=${j.cohortSize} screen=${j.screenWasOn}"
                    )
                }
                snackbarMessages.emit("Dumped ${recent.size} implicit judgments (total=$total) — see logcat DevImplicit")
            } catch (e: Exception) {
                Log.e(TAG_DEV_IMPLICIT, "devDump: failed", e)
                snackbarMessages.emit("Implicit dump failed: ${e.message}")
            }
        }
    }
}
