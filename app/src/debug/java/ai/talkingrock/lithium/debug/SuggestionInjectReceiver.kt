package ai.talkingrock.lithium.debug

import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only BroadcastReceiver that injects a Report + pending Suggestion so
 * Maestro flow 18 can deterministically exercise the "Yes, try it" approval
 * path without waiting for real LLM analysis.
 *
 * Trigger:
 *   adb shell am broadcast \
 *       -a ai.talkingrock.lithium.debug.INJECT_SUGGESTION \
 *       -p ai.talkingrock.lithium.debug \
 *       --es rationale "maestro-suggestion-approve" \
 *       --es action "suppress" \
 *       --es conditionJson '{"type":"package_match","packageName":"com.android.shell"}'
 *
 * All extras are optional; defaults produce a Suppress rule for
 * com.android.shell named "maestro-suggestion-approve" — deterministic across
 * runs so Maestro selectors on the created rule are stable.
 *
 * Report-before-Suggestion ordering (critical — see spec #40 §4):
 *   BriefingViewModel subscribes to reportDao.getLatestUnreviewed() and only
 *   queries suggestions tied to that report's id. Injecting a Suggestion
 *   without a matching unreviewed Report produces a UI that never renders
 *   "Yes, try it". The receiver therefore clears stale unreviewed reports
 *   (matching ReportRepository.insertReport semantics) and inserts a fresh
 *   Report first, capturing its generated id for the Suggestion row.
 *
 * Injects DAOs directly (not through ReportRepository / SuggestionRepository)
 * so the receiver lives entirely in the debug source set — zero changes to
 * app/src/main/. Same philosophy as DbExportReceiver (spec #32).
 */
@AndroidEntryPoint
class SuggestionInjectReceiver : BroadcastReceiver() {

    @Inject lateinit var reportDao: ReportDao
    @Inject lateinit var suggestionDao: SuggestionDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INJECT_SUGGESTION) return
        val rationale = intent.getStringExtra("rationale") ?: DEFAULT_RATIONALE
        val action = intent.getStringExtra("action") ?: DEFAULT_ACTION
        val conditionJson = intent.getStringExtra("conditionJson") ?: DEFAULT_CONDITION

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reportDao.markAllReviewed()
                val reportId = reportDao.insertReport(
                    Report(
                        generatedAtMs = System.currentTimeMillis(),
                        summaryJson = """{"source":"SuggestionInjectReceiver"}""",
                        reviewed = false,
                    )
                )
                suggestionDao.insertSuggestions(
                    listOf(
                        Suggestion(
                            reportId = reportId,
                            conditionJson = conditionJson,
                            action = action,
                            rationale = rationale,
                            status = "pending",
                        )
                    )
                )
                Log.i(
                    TAG,
                    "injected report=$reportId suggestion rationale=\"$rationale\" action=$action"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "injection failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SuggestionInjectReceiver"
        const val ACTION_INJECT_SUGGESTION =
            "ai.talkingrock.lithium.debug.INJECT_SUGGESTION"
        private const val DEFAULT_RATIONALE = "maestro-suggestion-approve"
        private const val DEFAULT_ACTION = "suppress"
        private const val DEFAULT_CONDITION =
            """{"type":"package_match","packageName":"com.android.shell"}"""
    }
}
