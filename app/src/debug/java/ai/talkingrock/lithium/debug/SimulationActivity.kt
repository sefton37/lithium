package ai.talkingrock.lithium.debug

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.talkingrock.lithium.ai.NotificationClassifier
import ai.talkingrock.lithium.ai.PatternAnalyzer
import ai.talkingrock.lithium.ai.ReportGenerator
import ai.talkingrock.lithium.ai.SuggestionGenerator
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.repository.ReportRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Debug-only activity that runs synthetic data simulations.
 *
 * Launch via ADB:
 *   adb shell am start -n ai.talkingrock.lithium.debug/ai.talkingrock.lithium.debug.SimulationActivity
 *
 * Run specific profile:
 *   adb shell am start -n ai.talkingrock.lithium.debug/ai.talkingrock.lithium.debug.SimulationActivity --ei profile 3
 *
 * profile=0 (default) runs all 9 profiles sequentially.
 * profile=1-9 runs a single profile and leaves data in DB for UI browsing.
 */
@AndroidEntryPoint
class SimulationActivity : ComponentActivity() {

    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var reportDao: ReportDao
    @Inject lateinit var suggestionDao: SuggestionDao
    @Inject lateinit var queueDao: QueueDao
    @Inject lateinit var behaviorProfileDao: AppBehaviorProfileDao
    @Inject lateinit var classifier: NotificationClassifier
    @Inject lateinit var patternAnalyzer: PatternAnalyzer
    @Inject lateinit var reportGenerator: ReportGenerator
    @Inject lateinit var suggestionGenerator: SuggestionGenerator
    @Inject lateinit var reportRepository: ReportRepository

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scrollView = ScrollView(this)
        logView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 13f
            setTextColor(0xFFE0E0E0.toInt())
            setBackgroundColor(0xFF121212.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(logView)
        setContentView(scrollView)

        val profileArg = intent.getIntExtra("profile", 0)

        lifecycleScope.launch {
            val runner = SimulationRunner(
                notificationDao, sessionDao, reportDao, suggestionDao, queueDao,
                behaviorProfileDao,
                classifier, patternAnalyzer, reportGenerator, suggestionGenerator,
                reportRepository
            )

            val profiles = if (profileArg in 1..9) listOf(profileArg) else (1..9).toList()

            appendLog("Lithium Simulation Engine")
            appendLog("Running ${profiles.size} profile(s)...\n")

            for (p in profiles) {
                try {
                    val result = withContext(Dispatchers.IO) { runner.runProfile(p) }
                    appendLog(formatResult(result))
                } catch (e: Exception) {
                    val msg = "PROFILE $p FAILED: ${e.message}\n${e.stackTraceToString()}"
                    Log.e("LithiumSim", msg)
                    appendLog("!!! $msg\n")
                }
            }

            appendLog("\n=== ALL SIMULATIONS COMPLETE ===")
            appendLog("Last profile's data remains in DB for UI inspection.")
        }
    }

    private suspend fun appendLog(text: String) {
        withContext(Dispatchers.Main) {
            logView.append(text + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun formatResult(r: SimulationResult): String = buildString {
        appendLine("╔══════════════════════════════════════════")
        appendLine("║ Profile ${r.profileNumber}: ${r.profileName}")
        appendLine("║ ${r.profileDescription}")
        appendLine("╠══════════════════════════════════════════")
        appendLine("║ Notifications: ${r.totalNotifications}  Sessions: ${r.totalSessions}")
        appendLine("║")
        appendLine("║ CLASSIFICATION:")
        for ((cat, count) in r.classificationBreakdown.entries.sortedByDescending { it.value }) {
            appendLine("║   $cat: $count")
        }
        appendLine("║")
        appendLine("║ REPORT:")
        r.reportText.lines().forEach { appendLine("║   $it") }
        appendLine("║")
        if (r.suggestions.isNotEmpty()) {
            appendLine("║ SUGGESTIONS (${r.suggestions.size}):")
            r.suggestions.forEach { appendLine("║   $it") }
        } else {
            appendLine("║ SUGGESTIONS: None generated")
        }
        appendLine("║")
        appendLine("║ TOP APPS:")
        r.topApps.forEach { appendLine("║   $it") }
        appendLine("╚══════════════════════════════════════════")
        appendLine()
    }
}
