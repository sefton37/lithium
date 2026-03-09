package ai.talkingrock.lithium.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.RuleCondition
import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Actions available when selecting a rule action.
 */
enum class RuleActionOption(val value: String, val label: String) {
    SUPPRESS("suppress", "Suppress"),
    QUEUE("queue", "Queue for later")
}

/**
 * UI state for [AddRuleScreen].
 *
 * @param availablePackages Distinct package names observed so far, for the app selector.
 * @param selectedPackage Currently selected package, or null if none selected.
 * @param selectedAction Currently selected action (suppress / queue).
 * @param channelFilter Optional channel ID filter text.
 * @param description Rule description — auto-generated but editable.
 * @param isLoadingPackages True while fetching distinct package names.
 * @param isSaving True while the insert coroutine is running.
 * @param saveComplete True after a successful save — triggers navigation back.
 * @param error Non-null if validation failed; contains an error message.
 */
data class AddRuleUiState(
    val availablePackages: List<String> = emptyList(),
    val selectedPackage: String? = null,
    val selectedAction: RuleActionOption = RuleActionOption.SUPPRESS,
    val channelFilter: String = "",
    val description: String = "",
    val isLoadingPackages: Boolean = true,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for [AddRuleScreen].
 *
 * Loads available package names from the notification history, allows the user to fill in
 * rule fields, auto-generates a description from selections, and creates a user-sourced Rule.
 */
@HiltViewModel
class AddRuleViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val ruleRepository: RuleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddRuleUiState())
    val uiState: StateFlow<AddRuleUiState> = _uiState.asStateFlow()

    private val json = Json { encodeDefaults = true }

    init {
        loadPackages()
    }

    private fun loadPackages() {
        viewModelScope.launch {
            val packages = notificationRepository.getDistinctPackageNames()
            _uiState.update { it.copy(availablePackages = packages, isLoadingPackages = false) }
        }
    }

    fun selectPackage(packageName: String) {
        _uiState.update { state ->
            state.copy(
                selectedPackage = packageName,
                description = buildDescription(packageName, state.selectedAction, state.channelFilter)
            )
        }
    }

    fun selectAction(action: RuleActionOption) {
        _uiState.update { state ->
            state.copy(
                selectedAction = action,
                description = buildDescription(state.selectedPackage, action, state.channelFilter)
            )
        }
    }

    fun updateChannelFilter(channel: String) {
        _uiState.update { state ->
            state.copy(
                channelFilter = channel,
                description = buildDescription(state.selectedPackage, state.selectedAction, channel)
            )
        }
    }

    fun updateDescription(text: String) {
        _uiState.update { it.copy(description = text) }
    }

    /** Validates inputs and saves the rule. Sets [AddRuleUiState.saveComplete] on success. */
    fun saveRule() {
        val state = _uiState.value
        if (state.selectedPackage == null) {
            _uiState.update { it.copy(error = "Please select an app.") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            val condition: RuleCondition = if (state.channelFilter.isNotBlank()) {
                RuleCondition.ChannelMatch(
                    packageName = state.selectedPackage,
                    channelId = state.channelFilter.trim()
                )
            } else {
                RuleCondition.PackageMatch(packageName = state.selectedPackage)
            }

            val conditionJson = json.encodeToString(RuleCondition.serializer(), condition)
            val rule = Rule(
                name = state.description.ifBlank {
                    buildDescription(state.selectedPackage, state.selectedAction, state.channelFilter)
                },
                conditionJson = conditionJson,
                action = state.selectedAction.value,
                status = "approved",
                createdAtMs = System.currentTimeMillis(),
                source = "user"
            )

            ruleRepository.insertRule(rule)
            _uiState.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }

    private fun buildDescription(
        packageName: String?,
        action: RuleActionOption,
        channelFilter: String
    ): String {
        val pkg = packageName ?: return ""
        val channelPart = if (channelFilter.isNotBlank()) " (channel: ${channelFilter.trim()})" else ""
        return "${action.label} notifications from $pkg$channelPart"
    }
}
