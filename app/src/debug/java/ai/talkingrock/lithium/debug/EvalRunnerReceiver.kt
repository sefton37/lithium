package ai.talkingrock.lithium.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import ai.talkingrock.lithium.ai.LlamaEngine
import ai.talkingrock.lithium.ai.eval.ModelEvalHarness
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Named

/**
 * Debug-only ADB entry point to run the model eval harness.
 *
 * Invoke:
 *   adb shell am broadcast -a ai.talkingrock.lithium.RUN_EVAL -p ai.talkingrock.lithium.debug
 *
 * On completion, writes a JSON summary to
 *   /sdcard/Download/lithium-eval-<modelFileName>.json
 *
 * Tail `adb logcat -s EvalRunnerReceiver:* ModelEvalHarness:*` to watch progress.
 */
@AndroidEntryPoint
class EvalRunnerReceiver : BroadcastReceiver() {

    @Inject lateinit var harness: ModelEvalHarness
    @Inject lateinit var llamaEngine: LlamaEngine
    @Inject @Named("modelDir") lateinit var modelDir: String

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                if (!llamaEngine.isModelLoaded()) {
                    llamaEngine.loadModel(modelDir, LlamaEngine.GENERATIVE_CONTEXT_SIZE)
                }
                if (!llamaEngine.isModelLoaded()) {
                    Log.e(TAG, "no GGUF model found in $modelDir")
                    return@launch
                }
                Log.i(TAG, "starting eval")
                val start = System.currentTimeMillis()
                val report = harness.run { done, total ->
                    if (done == total || done % 20 == 0) {
                        Log.i(TAG, "progress $done / $total  (${System.currentTimeMillis() - start} ms)")
                    }
                }
                val summary = Summary(
                    modelFileName = report.modelFileName,
                    totalScenarios = report.totalScenarios,
                    totalPhrasings = report.totalPhrasings,
                    scenariosFullyPassed = report.scenariosFullyPassed,
                    overallAccuracy = report.overallAccuracy,
                    fieldAccuracy = report.fieldScores.associate { it.field to it.accuracy },
                    totalDurationMs = report.totalDurationMs,
                    avgPerPhrasingMs = if (report.results.isNotEmpty())
                        report.results.sumOf { it.durationMs } / report.results.size
                    else 0L,
                    failures = report.results.filter { !it.allPassed }.map { r ->
                        Failure(
                            scenarioId = r.scenarioId,
                            tone = r.tone,
                            prompt = r.prompt,
                            fieldsFailed = r.fieldsFailed.toList(),
                            extracted = Extracted(
                                r.extracted.packageName, r.extracted.channelId, r.extracted.category,
                                r.extracted.notFromContact, r.extracted.action,
                            ),
                            expected = Extracted(
                                r.expected.packageName, r.expected.channelId, r.expected.category,
                                r.expected.notFromContact, r.expected.action,
                            ),
                        )
                    },
                )
                val outFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "lithium-eval-${sanitize(report.modelFileName)}.json",
                )
                outFile.writeText(Json { prettyPrint = true }.encodeToString(summary))
                Log.i(TAG, "eval complete → ${outFile.absolutePath}")
                Log.i(TAG, "overall=${"%.1f".format(report.overallAccuracy * 100)}% " +
                    "scenarios=${report.scenariosFullyPassed}/${report.totalScenarios} " +
                    "avg=${summary.avgPerPhrasingMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "eval failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    @Serializable data class Summary(
        val modelFileName: String,
        val totalScenarios: Int,
        val totalPhrasings: Int,
        val scenariosFullyPassed: Int,
        val overallAccuracy: Double,
        val fieldAccuracy: Map<String, Double>,
        val totalDurationMs: Long,
        val avgPerPhrasingMs: Long,
        val failures: List<Failure>,
    )

    @Serializable data class Failure(
        val scenarioId: String,
        val tone: String,
        val prompt: String,
        val fieldsFailed: List<String>,
        val extracted: Extracted,
        val expected: Extracted,
    )

    @Serializable data class Extracted(
        val packageName: String? = null,
        val channelId: String? = null,
        val category: String? = null,
        val notFromContact: Boolean = false,
        val action: String = "suppress",
    )

    companion object { private const val TAG = "EvalRunnerReceiver" }
}
