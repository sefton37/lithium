package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.db.AppCount
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.repository.NotificationRepository

/**
 * Sealed class hierarchy for the Q&A tool surface.
 *
 * Each concrete tool has a [execute] function that queries the Room database via
 * [NotificationRepository] and returns a typed [ToolResult]. The tool layer is
 * the single source of truth for Q&A answers — the LLM is never allowed to
 * fabricate notification counts or lists.
 *
 * Tool surface (v1):
 * - [NotificationCount]   — total notification count
 * - [NotificationsSince]  — notifications received in the last N hours
 * - [TierBreakdown]       — count per tier (0=invisible … 3=interrupt)
 * - [TopApps]             — top N apps by notification count
 * - [NotificationsByApp]  — recent notifications from one specific package
 */
sealed class ChatQueryTool {

    /** Returns the total number of notifications recorded. */
    object NotificationCount : ChatQueryTool() {
        override suspend fun execute(repo: NotificationRepository): ToolResult {
            val n = repo.getCount()
            return ToolResult.Count(n)
        }
    }

    /**
     * Returns notifications from the last [hours] hours.
     * @param hours Look-back window (1–168). Defaults to 24 if not supplied.
     */
    data class NotificationsSince(val hours: Int = 24) : ChatQueryTool() {
        override suspend fun execute(repo: NotificationRepository): ToolResult {
            val sinceMs = System.currentTimeMillis() - hours.toLong() * 60 * 60 * 1000
            val rows = repo.getAllSince(sinceMs)
            val truncated = rows.size > MAX_RESULT_ROWS
            return ToolResult.NotificationList(
                rows = if (truncated) rows.take(MAX_RESULT_ROWS) else rows,
                truncated = truncated,
            )
        }
    }

    /** Returns a count per tier (0=invisible, 1=noise, 2=worth seeing, 3=interrupt). */
    object TierBreakdown : ChatQueryTool() {
        override suspend fun execute(repo: NotificationRepository): ToolResult {
            val tiers = repo.getTierBreakdown()
            return ToolResult.TierSummary(tiers)
        }
    }

    /**
     * Returns the top [limit] apps by total notification count.
     * @param limit Maximum number of apps to return (1–20). Defaults to 10.
     */
    data class TopApps(val limit: Int = 10) : ChatQueryTool() {
        override suspend fun execute(repo: NotificationRepository): ToolResult {
            val apps = repo.getTopAppsByCount(limit.coerceIn(1, MAX_RESULT_ROWS))
            val truncated = apps.size >= limit
            return ToolResult.AppCountList(apps = apps, truncated = truncated)
        }
    }

    /**
     * Returns recent notifications from [packageName].
     * @param packageName Android package identifier (e.g. "com.slack").
     */
    data class NotificationsByApp(val packageName: String) : ChatQueryTool() {
        override suspend fun execute(repo: NotificationRepository): ToolResult {
            val rows = repo.getByPackageSuspend(packageName, MAX_RESULT_ROWS)
            return ToolResult.NotificationList(rows = rows, truncated = false)
        }
    }

    /** Executes this tool against [repo] and returns a [ToolResult]. */
    abstract suspend fun execute(repo: NotificationRepository): ToolResult

    companion object {
        /** Maximum rows returned in list results before truncation. */
        const val MAX_RESULT_ROWS = 20
    }
}

// ---------------------------------------------------------------------------
// Tool result types
// ---------------------------------------------------------------------------

/**
 * Discriminated union of all possible tool outcomes.
 *
 * Each variant implements [toPromptString] to produce the text injected into
 * the Pass-2 LLM prompt. Formatting lives here so [ChatToolDispatcher] stays
 * clean of presentation concerns.
 */
sealed class ToolResult {

    /** Serialises this result for injection into the Pass-2 LLM prompt. */
    abstract fun toPromptString(): String

    /** Plain count result from [ChatQueryTool.NotificationCount]. */
    data class Count(val value: Int) : ToolResult() {
        override fun toPromptString() = "Total notifications recorded: $value"
    }

    /**
     * A list of notification records (possibly truncated).
     * Used by [ChatQueryTool.NotificationsSince] and [ChatQueryTool.NotificationsByApp].
     */
    data class NotificationList(
        val rows: List<NotificationRecord>,
        val truncated: Boolean,
    ) : ToolResult() {
        override fun toPromptString(): String = buildString {
            appendLine("Notifications (${rows.size}${if (truncated) "+" else ""}):")
            rows.forEach { n ->
                append("  - ${n.packageName}")
                n.title?.let { append(" | $it") }
                n.text?.let { t -> append(": ${t.take(80)}") }
                appendLine()
            }
            if (truncated) appendLine("(list truncated to $${rows.size} entries)")
        }
    }

    /**
     * Tier breakdown summary.
     * Used by [ChatQueryTool.TierBreakdown].
     */
    data class TierSummary(val tiers: List<TierCount>) : ToolResult() {
        override fun toPromptString(): String = buildString {
            val labels = mapOf(0 to "Invisible", 1 to "Noise", 2 to "Worth seeing", 3 to "Interrupt")
            appendLine("Tier breakdown:")
            tiers.forEach { tc ->
                appendLine("  Tier ${tc.tier} (${labels[tc.tier] ?: "?"}): ${tc.count}")
            }
        }
    }

    /**
     * Top-apps result.
     * Used by [ChatQueryTool.TopApps].
     */
    data class AppCountList(
        val apps: List<AppCount>,
        val truncated: Boolean,
    ) : ToolResult() {
        override fun toPromptString(): String = buildString {
            appendLine("Top apps by notification count:")
            apps.forEachIndexed { idx, ac ->
                appendLine("  ${idx + 1}. ${ac.packageName}: ${ac.count}")
            }
            if (truncated) appendLine("(list truncated)")
        }
    }

    /**
     * The model named a tool that does not exist in the tool registry.
     * [ChatToolDispatcher] returns this when the parsed tool name is unrecognised.
     */
    object Unknown : ToolResult() {
        override fun toPromptString() = "unknown tool"
    }

    /**
     * The model output contained no parseable TOOL: line.
     * [ChatToolDispatcher] returns this when no TOOL: prefix is found.
     */
    object NoToolCall : ToolResult() {
        override fun toPromptString() = "no tool call"
    }
}
