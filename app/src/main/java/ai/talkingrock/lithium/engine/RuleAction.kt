package ai.talkingrock.lithium.engine

/**
 * The action a [RuleEngine] evaluation produces for an incoming notification.
 *
 * - [SUPPRESS]: cancel the notification immediately. It is recorded in the database but
 *   never shown to the user.
 * - [QUEUE]: cancel the notification and place it in the review queue. The user sees it
 *   during their next Briefing session.
 * - [RESURFACE]: cancel the original notification and immediately post a curated
 *   Lithium-owned copy in the shade, preserving title/text but under Lithium's channel.
 * - [ALLOW]: let the notification pass through unmodified. Default when no rule matches.
 *
 * Old builds reading a [RESURFACE] action string from the database will fall through
 * to [ALLOW] via the existing `else` branch in [RuleEngine.evaluate] — safe degradation.
 */
enum class RuleAction {
    SUPPRESS,
    QUEUE,
    RESURFACE,
    ALLOW
}
