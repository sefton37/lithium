package ai.talkingrock.lithium.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for [RulesScreen].
 *
 * @param rules All rules, newest first (approved, disabled, rejected).
 * @param isLoading True on first emission before the DB has responded.
 * @param expandedRuleId The rule whose detail card is currently expanded, or null.
 */
data class RulesUiState(
    val rules: List<Rule> = emptyList(),
    val isLoading: Boolean = true,
    val expandedRuleId: Long? = null
)

/**
 * ViewModel for [RulesScreen].
 *
 * Exposes all rules as a reactive [StateFlow]. Provides actions for toggling a rule's
 * enabled/disabled status and deleting rules.
 */
@HiltViewModel
class RulesViewModel @Inject constructor(
    private val ruleRepository: RuleRepository
) : ViewModel() {

    // MutableStateFlow so that expand/collapse triggers recomposition.
    private val _expandedRuleId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<RulesUiState> = combine(
        ruleRepository.getAll(),
        _expandedRuleId
    ) { rules, expandedId ->
        RulesUiState(
            rules = rules,
            isLoading = false,
            expandedRuleId = expandedId
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RulesUiState(isLoading = true)
        )

    /**
     * Toggles a rule between enabled (approved) and disabled.
     * If the rule is currently approved, it becomes disabled, and vice versa.
     * Rejected rules are not togglable — they remain rejected.
     */
    fun toggleRule(rule: Rule) {
        if (rule.status == "rejected") return
        viewModelScope.launch {
            ruleRepository.toggleStatus(rule.id, rule.status)
        }
    }

    /** Hard-deletes a rule. The RuleEngine cache will update automatically via the StateFlow. */
    fun deleteRule(rule: Rule) {
        viewModelScope.launch {
            ruleRepository.deleteRule(rule.id)
        }
    }

    /** Expands or collapses the detail view for a rule card. */
    fun toggleExpanded(ruleId: Long) {
        _expandedRuleId.value = if (_expandedRuleId.value == ruleId) null else ruleId
    }
}
