package ai.talkingrock.lithium.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed representation of a rule's matching condition.
 *
 * Stored as JSON in [Rule.conditionJson]. Serialization/deserialization happens at the
 * repository layer — the DAO layer stores and retrieves the raw String.
 *
 * The sealed class hierarchy maps to the @SerialName discriminator field in the JSON, so
 * the same JSON format works across versions as long as the SerialName values are stable.
 */
@Serializable
sealed class RuleCondition {

    /** Match all notifications from a specific package. */
    @Serializable
    @SerialName("package_match")
    data class PackageMatch(val packageName: String) : RuleCondition()

    /** Match notifications from a specific notification channel, optionally scoped to a package. */
    @Serializable
    @SerialName("channel_match")
    data class ChannelMatch(
        val packageName: String?,
        val channelId: String
    ) : RuleCondition()

    /** Match notifications whose category field matches exactly. */
    @Serializable
    @SerialName("category_match")
    data class CategoryMatch(val category: String) : RuleCondition()

    /** Match notifications where the sender is NOT in the user's contacts. */
    @Serializable
    @SerialName("not_from_contact")
    data object NotFromContact : RuleCondition()

    /** Match when ALL child conditions match (logical AND). */
    @Serializable
    @SerialName("composite_and")
    data class CompositeAnd(val conditions: List<RuleCondition>) : RuleCondition()

    /**
     * Match notifications whose [ai.talkingrock.lithium.data.model.NotificationRecord.tier]
     * equals [tier]. Used by the default tier-based seed rules inserted by
     * [ai.talkingrock.lithium.data.db.ShadeModeSeeder].
     *
     * Serialised as `{"type":"tier_match","tier":N}`.
     */
    @Serializable
    @SerialName("tier_match")
    data class TierMatch(val tier: Int) : RuleCondition()
}
