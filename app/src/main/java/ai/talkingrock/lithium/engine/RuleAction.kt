package ai.talkingrock.lithium.engine

/**
 * The action a [RuleEngine] evaluation produces for an incoming notification.
 *
 * - [SUPPRESS]: cancel the notification immediately. It is recorded in the database but
 *   never shown to the user.
 * - [QUEUE]: cancel the notification and place it in the review queue. The user sees it
 *   during their next Briefing session.
 * - [ALLOW]: let the notification pass through unmodified. Default when no rule matches.
 */
enum class RuleAction {
    SUPPRESS,
    QUEUE,
    ALLOW
}
