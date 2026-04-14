package ai.talkingrock.lithium.ai

import android.util.Log
import ai.talkingrock.lithium.data.repository.NotificationRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the model's tool-selection output and dispatches to the appropriate [ChatQueryTool].
 *
 * ## Expected model output format (Pass 1)
 *
 * ```
 * TOOL: notificationCount
 * ARGS: {}
 * ```
 *
 * The dispatcher scans the model output line by line for a line starting with `TOOL:`.
 * The next `ARGS:` line (if present) is parsed as JSON. If parsing fails or the line is
 * absent, an empty args map is used (valid for zero-argument tools).
 *
 * ## Error handling
 *
 * - No `TOOL:` line found → [ToolResult.NoToolCall]
 * - Tool name not in registry → [ToolResult.Unknown]
 * - Args JSON missing or malformed → defaults applied (see per-tool defaults)
 *
 * ## Refusal wording
 *
 * When the model emits no parseable tool call, the VM must show the user the
 * canonical refusal phrase (stored as [REFUSAL_TEXT]):
 *
 * > "I'm not sure how to answer that from your notification data. Try asking about
 * > counts, recent notifications, or which apps sent the most."
 */
class ChatToolDispatcher @Inject constructor(
    private val repo: NotificationRepository,
) {

    /**
     * Parses [modelOutput] and executes the identified tool against [repo].
     *
     * @param modelOutput Raw string from [LlamaEngine.generate] (Pass 1).
     * @return [ToolResult] from the executed tool, or [ToolResult.Unknown] /
     *   [ToolResult.NoToolCall] on parse failure.
     */
    suspend fun dispatch(modelOutput: String): ToolResult {
        val lines = modelOutput.lines()

        // Find the TOOL: line
        val toolLineIndex = lines.indexOfFirst { it.trim().startsWith("TOOL:") }
        if (toolLineIndex == -1) {
            Log.d(TAG, "dispatch: no TOOL: line found in model output")
            return ToolResult.NoToolCall
        }

        val toolName = lines[toolLineIndex].trim().removePrefix("TOOL:").trim()

        // Find the next ARGS: line after the TOOL: line (optional)
        val argsLine = lines.drop(toolLineIndex + 1)
            .firstOrNull { it.trim().startsWith("ARGS:") }
        val argsJson = argsLine?.trim()?.removePrefix("ARGS:")?.trim() ?: "{}"
        val args = parseArgs(argsJson)

        Log.d(TAG, "dispatch: tool='$toolName' args=$argsJson")

        val tool = resolveTool(toolName, args) ?: run {
            Log.w(TAG, "dispatch: unknown tool '$toolName'")
            return ToolResult.Unknown
        }

        return tool.execute(repo)
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /** Parses the ARGS JSON string into a [JsonObject], returning null on failure. */
    private fun parseArgs(json: String): JsonObject? {
        return try {
            Json.parseToJsonElement(json).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Maps a [toolName] string (case-insensitive) to a concrete [ChatQueryTool].
     * Returns null if the name does not match any registered tool.
     */
    private fun resolveTool(toolName: String, args: JsonObject?): ChatQueryTool? {
        return when (toolName.lowercase().replace("_", "").replace("-", "")) {
            "notificationcount" -> ChatQueryTool.NotificationCount
            "notificationssince", "recentnotifications" -> {
                val hours = args?.get("hours")?.jsonPrimitive?.intOrNull ?: DEFAULT_HOURS
                ChatQueryTool.NotificationsSince(hours.coerceIn(1, 168))
            }
            "tierbreakdown" -> ChatQueryTool.TierBreakdown
            "topapps", "applist" -> {
                val limit = args?.get("limit")?.jsonPrimitive?.intOrNull ?: DEFAULT_TOP_LIMIT
                ChatQueryTool.TopApps(limit.coerceIn(1, 20))
            }
            "notificationsbyapp", "apphistory" -> {
                val pkg = args?.get("packageName")?.jsonPrimitive?.contentOrNull ?: ""
                ChatQueryTool.NotificationsByApp(pkg)
            }
            "none", "" -> null  // model was asked to call "none" if no tool fits
            else -> null
        }
    }

    companion object {
        private const val TAG = "ChatToolDispatcher"

        /** Default look-back for [ChatQueryTool.NotificationsSince] when not specified. */
        private const val DEFAULT_HOURS = 24

        /** Default limit for [ChatQueryTool.TopApps] when not specified. */
        private const val DEFAULT_TOP_LIMIT = 10

        /**
         * Refusal text shown to the user when the model emits no parseable tool call.
         * Also used by [ChatToolDispatcher] callers (ChatViewModel) when a
         * [ToolResult.Unknown] or [ToolResult.NoToolCall] is received.
         */
        const val REFUSAL_TEXT =
            "I'm not sure how to answer that from your notification data. " +
            "Try asking about counts, recent notifications, or which apps sent the most."

        /** System prompt for Pass 1 (tool selection). Shared constant for prompt construction. */
        const val QA_SYSTEM_PROMPT = """[SYSTEM] You are Lithium, a notification assistant. You answer questions about the user's notification history by calling exactly one tool. Available tools:

TOOL: notificationCount
ARGS: {}
-> Returns the total number of notifications recorded.

TOOL: notificationsSince
ARGS: {"hours": N}
-> Returns notifications from the last N hours. N must be an integer (1-168).

TOOL: tierBreakdown
ARGS: {}
-> Returns a count per tier: 0=invisible, 1=noise, 2=worth seeing, 3=interrupt.

TOOL: topApps
ARGS: {"limit": N}
-> Returns the top N apps by notification count. N must be an integer (1-20).

TOOL: notificationsByApp
ARGS: {"packageName": "com.example"}
-> Returns recent notifications from one specific app.

Rules:
- Respond with exactly one TOOL: line followed by one ARGS: line.
- Do not explain. Do not add any other text before the TOOL: line.
- If no tool fits the question, write: TOOL: none"""
    }
}
