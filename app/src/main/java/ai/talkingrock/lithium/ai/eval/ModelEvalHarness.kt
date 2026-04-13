package ai.talkingrock.lithium.ai.eval

import android.content.Context
import android.util.Log
import ai.talkingrock.lithium.ai.LlamaEngine
import ai.talkingrock.lithium.ai.RuleExtractor
import ai.talkingrock.lithium.ui.chat.RuleDraftState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Model-quality evaluator.
 *
 * Runs every phrasing in every scenario through [RuleExtractor] (with synthetic
 * app + contact context) and compares the extracted [RuleDraftState] against the
 * labelled expectation. Produces a [EvalReport] that can be rendered in a debug
 * screen for side-by-side model comparison.
 *
 * The harness intentionally drives [RuleExtractor] via the same public API the
 * chat tab uses, so eval numbers reflect real app behaviour including the
 * package-name grounding step.
 */
@Singleton
class ModelEvalHarness @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaEngine: LlamaEngine,
    private val ruleExtractor: RuleExtractor,
    @Named("modelDir") private val modelDir: String,
) {

    data class FieldScore(
        val field: String,
        val correct: Int,
        val total: Int,
    ) {
        val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    }

    data class PhrasingResult(
        val scenarioId: String,
        val tone: String,
        val prompt: String,
        val extracted: RuleDraftState,
        val expected: Expected,
        val fieldsPassed: Set<String>,
        val fieldsFailed: Set<String>,
        val durationMs: Long,
    ) {
        val allPassed: Boolean get() = fieldsFailed.isEmpty()
    }

    data class EvalReport(
        val modelFileName: String,
        val totalPhrasings: Int,
        val totalScenarios: Int,
        val scenariosFullyPassed: Int,
        val fieldScores: List<FieldScore>,
        val results: List<PhrasingResult>,
        val totalDurationMs: Long,
    ) {
        val overallAccuracy: Double
            get() = fieldScores.sumOf { it.correct }.toDouble() /
                fieldScores.sumOf { it.total }.coerceAtLeast(1)
    }

    /**
     * Runs the full eval. [onProgress] is invoked after each phrasing so the UI can
     * update a progress bar. Expects [LlamaEngine] to already have a model loaded
     * with a generative-sized context.
     */
    suspend fun run(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): EvalReport {
        val apps = EvalDataset.loadApps(context).apps
        val contacts = EvalDataset.loadContacts(context).contacts
        val scenarios = EvalDataset.loadScenarios(context).scenarios
        val ctx = RuleExtractor.Context(
            apps = apps.map { ai.talkingrock.lithium.ai.KnownApp(it.packageName, it.label) },
            contacts = contacts,
        )

        if (!llamaEngine.isModelLoaded()) {
            llamaEngine.loadModel(modelDir, LlamaEngine.GENERATIVE_CONTEXT_SIZE)
        }
        val modelName = resolveModelName()

        val total = scenarios.sumOf { it.phrasings.size }
        val results = mutableListOf<PhrasingResult>()
        var done = 0
        val runStart = System.currentTimeMillis()

        for (scenario in scenarios) {
            for (phrasing in scenario.phrasings) {
                val t0 = System.currentTimeMillis()
                val extracted = try {
                    ruleExtractor.extract(phrasing.text, context = ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "extract failed for ${scenario.id}/${phrasing.tone}", e)
                    RuleDraftState(originalInput = phrasing.text)
                }
                val elapsed = System.currentTimeMillis() - t0
                val (passed, failed) = scoreAgainst(extracted, scenario.expected, scenario.acceptable)
                results += PhrasingResult(
                    scenarioId = scenario.id,
                    tone = phrasing.tone,
                    prompt = phrasing.text,
                    extracted = extracted,
                    expected = scenario.expected,
                    fieldsPassed = passed,
                    fieldsFailed = failed,
                    durationMs = elapsed,
                )
                done += 1
                onProgress(done, total)
            }
        }

        val fieldScores = FIELDS.map { field ->
            val (correct, all) = results.fold(0 to 0) { (c, t), r ->
                if (field in r.fieldsPassed || field in r.fieldsFailed) {
                    val nextCorrect = c + if (field in r.fieldsPassed) 1 else 0
                    nextCorrect to (t + 1)
                } else c to t
            }
            FieldScore(field, correct, all)
        }
        val scenariosPassed = scenarios.count { sc ->
            results.filter { it.scenarioId == sc.id }.all { it.allPassed }
        }
        return EvalReport(
            modelFileName = modelName,
            totalPhrasings = total,
            totalScenarios = scenarios.size,
            scenariosFullyPassed = scenariosPassed,
            fieldScores = fieldScores,
            results = results,
            totalDurationMs = System.currentTimeMillis() - runStart,
        )
    }

    private fun scoreAgainst(
        extracted: RuleDraftState,
        expected: Expected,
        acceptable: Acceptable?,
    ): Pair<Set<String>, Set<String>> {
        val passed = mutableSetOf<String>()
        val failed = mutableSetOf<String>()

        fun checkNullable(field: String, actual: String?, exp: String?, acc: List<String?>?) {
            val ok = actual == exp || (acc != null && actual in acc)
            if (ok) passed += field else failed += field
        }

        checkNullable("packageName", extracted.packageName, expected.packageName, acceptable?.packageName)
        checkNullable("channelId", extracted.channelId, expected.channelId, acceptable?.channelId)
        checkNullable("category", extracted.category, expected.category, acceptable?.category)

        if (extracted.notFromContact == expected.notFromContact) passed += "notFromContact"
        else failed += "notFromContact"

        if (extracted.action == expected.action) passed += "action"
        else failed += "action"

        return passed to failed
    }

    private fun resolveModelName(): String =
        java.io.File(modelDir).listFiles()?.firstOrNull { it.extension == "gguf" }?.name ?: "unknown"

    companion object {
        private const val TAG = "ModelEvalHarness"
        val FIELDS = listOf("packageName", "channelId", "category", "notFromContact", "action")
    }
}
