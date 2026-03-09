package ai.talkingrock.lithium.data.repository

import ai.talkingrock.lithium.data.db.RuleDao
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.Suggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [Rule].
 *
 * Maintains an in-memory [StateFlow] of approved rules. The [RuleEngine] reads
 * [approvedRules].value synchronously on every notification — no database query on the
 * hot path. The StateFlow is kept fresh by collecting [RuleDao.getApprovedRules].
 *
 * The repository-level scope uses [SupervisorJob] so a failure in one coroutine does not
 * cancel the shared scope.
 */
@Singleton
class RuleRepository @Inject constructor(
    private val dao: RuleDao
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * In-memory cache of approved rules, updated automatically whenever the database changes.
     * Read by [ai.talkingrock.lithium.engine.RuleEngine] synchronously via [StateFlow.value].
     *
     * [SharingStarted.Eagerly] ensures the cache is populated as soon as the repository is
     * created (i.e., at app start), not lazily when the first collector arrives. The service
     * may evaluate rules before any UI collector exists.
     */
    val approvedRules: StateFlow<List<Rule>> = dao.getApprovedRules()
        .stateIn(
            scope = repositoryScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** Insert a new rule. Returns the generated row ID. */
    suspend fun insertRule(rule: Rule): Long = withContext(Dispatchers.IO) {
        dao.insertRule(rule)
    }

    /** Update the status of a rule (e.g., "approved", "rejected"). */
    suspend fun updateStatus(id: Long, status: String) = withContext(Dispatchers.IO) {
        dao.updateStatus(id, status)
    }

    /** Reactive stream of all rules, newest first. Used by the Rules management screen. */
    fun getAll(): Flow<List<Rule>> = dao.getAll()

    suspend fun getById(id: Long): Rule? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    /**
     * Creates an approved [Rule] from an accepted [Suggestion].
     * The suggestion's rationale becomes the rule name; the condition_json is copied directly.
     * Returns the generated row ID of the new rule.
     */
    suspend fun createFromSuggestion(suggestion: Suggestion): Long = withContext(Dispatchers.IO) {
        val rule = Rule(
            name = suggestion.rationale,
            conditionJson = suggestion.conditionJson,
            action = suggestion.action,
            status = "approved",
            createdAtMs = System.currentTimeMillis(),
            source = "ai"
        )
        dao.insertRule(rule)
    }

    /** Hard-deletes a rule by ID. */
    suspend fun deleteRule(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    /**
     * Toggles a rule's status between [activeStatus] and [disabledStatus].
     * If the rule is currently [activeStatus], it becomes [disabledStatus], and vice versa.
     */
    suspend fun toggleStatus(id: Long, currentStatus: String) = withContext(Dispatchers.IO) {
        val newStatus = if (currentStatus == "approved") "disabled" else "approved"
        dao.updateStatus(id, newStatus)
    }
}
