package ai.talkingrock.lithium.engine

import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.model.RuleCondition
import ai.talkingrock.lithium.data.repository.RuleRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates incoming notifications against approved rules.
 *
 * This is the hot path — called synchronously on the main thread of the service process
 * for every notification received. It must complete in under 1ms for 50 rules.
 *
 * Performance strategy:
 * - Reads the approved-rules list from [RuleRepository.approvedRules].value — a StateFlow,
 *   no database query.
 * - Caches deserialized [RuleCondition] objects keyed by rule ID. The cache is rebuilt
 *   whenever the rules list changes, so deserialization never happens on the notification
 *   callback thread.
 *
 * First-match-wins: rules are evaluated in [Rule.createdAtMs] order (oldest first, as
 * persisted by the DAO). The first rule whose condition matches determines the action.
 * If no rule matches, [RuleAction.ALLOW] is returned.
 */
@Singleton
class RuleEngine @Inject constructor(
    private val ruleRepository: RuleRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Cached parsed rules — rebuilt lazily when the approved list changes.
    // Volatile so the main-thread read always sees the latest write.
    @Volatile
    private var cachedRuleList: List<Rule> = emptyList()

    @Volatile
    private var parsedConditions: Map<Long, RuleCondition> = emptyMap()

    /**
     * Evaluates [record] against all approved rules.
     *
     * This method is synchronous and must not suspend. It is called directly from
     * [LithiumNotificationListener.onNotificationPosted] on the main thread.
     */
    fun evaluate(record: NotificationRecord): RuleAction {
        val rules = ruleRepository.approvedRules.value

        // Rebuild condition cache if the rules list has changed.
        if (rules !== cachedRuleList) {
            rebuildCache(rules)
        }

        for (rule in cachedRuleList) {
            val condition = parsedConditions[rule.id] ?: continue
            if (matches(condition, record)) {
                return when (rule.action.lowercase()) {
                    "suppress" -> RuleAction.SUPPRESS
                    "queue" -> RuleAction.QUEUE
                    else -> RuleAction.ALLOW
                }
            }
        }

        return RuleAction.ALLOW
    }

    private fun rebuildCache(rules: List<Rule>) {
        val parsed = mutableMapOf<Long, RuleCondition>()
        for (rule in rules) {
            try {
                parsed[rule.id] = json.decodeFromString(RuleCondition.serializer(), rule.conditionJson)
            } catch (e: Exception) {
                // Malformed condition JSON — skip this rule rather than crashing the hot path.
                // The rule will be silently bypassed until the JSON is corrected.
            }
        }
        cachedRuleList = rules
        parsedConditions = parsed
    }

    private fun matches(condition: RuleCondition, record: NotificationRecord): Boolean {
        return when (condition) {
            is RuleCondition.PackageMatch ->
                record.packageName == condition.packageName

            is RuleCondition.ChannelMatch ->
                (condition.packageName == null || record.packageName == condition.packageName) &&
                        record.channelId == condition.channelId

            is RuleCondition.CategoryMatch ->
                record.category == condition.category

            is RuleCondition.NotFromContact ->
                // isFromContact is populated by ContactsResolver (M2). In M1 this field
                // does not exist on NotificationRecord — always returns false so the rule
                // never fires until M2 populates the field.
                false

            is RuleCondition.CompositeAnd ->
                condition.conditions.all { matches(it, record) }
        }
    }
}
