package ai.talkingrock.lithium.ui.eval

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.Environment
import ai.talkingrock.lithium.ai.LlamaEngine
import ai.talkingrock.lithium.ai.eval.ModelEvalHarness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class EvalUiState(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val report: ModelEvalHarness.EvalReport? = null,
    val error: String? = null,
    val modelFileName: String = "",
)

@HiltViewModel
class EvalViewModel @Inject constructor(
    private val harness: ModelEvalHarness,
    private val llamaEngine: LlamaEngine,
    @Named("modelDir") private val modelDir: String,
) : ViewModel() {

    private val _state = MutableStateFlow(EvalUiState(modelFileName = resolveModelName()))
    val state: StateFlow<EvalUiState> = _state.asStateFlow()

    fun refreshModelName() {
        _state.update { it.copy(modelFileName = resolveModelName()) }
    }

    fun run() {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(
                isRunning = true, done = 0, total = 0, report = null, error = null,
                modelFileName = resolveModelName(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!llamaEngine.isModelLoaded()) {
                    llamaEngine.loadModel(modelDir, LlamaEngine.GENERATIVE_CONTEXT_SIZE)
                }
                if (!llamaEngine.isModelLoaded()) {
                    _state.update {
                        it.copy(
                            isRunning = false,
                            error = "No GGUF model loaded. Sideload a .gguf into $modelDir and retry.",
                        )
                    }
                    return@launch
                }
                val report = harness.run { done, total ->
                    _state.update { it.copy(done = done, total = total) }
                }
                _state.update { it.copy(isRunning = false, report = report) }
                runCatching { writeSummary(report) }
                    .onFailure { Log.w(TAG, "writeSummary failed", it) }
            } catch (e: Exception) {
                Log.e(TAG, "eval run failed", e)
                _state.update { it.copy(isRunning = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    private fun resolveModelName(): String =
        java.io.File(modelDir).listFiles()?.firstOrNull { it.extension == "gguf" }?.name ?: "(none)"

    private fun writeSummary(report: ModelEvalHarness.EvalReport) {
        val summary = SummaryDto(
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
                FailureDto(
                    scenarioId = r.scenarioId, tone = r.tone, prompt = r.prompt,
                    fieldsFailed = r.fieldsFailed.toList(),
                    extracted = ExtractedDto(r.extracted.packageName, r.extracted.channelId,
                        r.extracted.category, r.extracted.notFromContact, r.extracted.action),
                    expected = ExtractedDto(r.expected.packageName, r.expected.channelId,
                        r.expected.category, r.expected.notFromContact, r.expected.action),
                )
            },
        )
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val safeName = report.modelFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "lithium-eval-$safeName.json")
        file.writeText(Json { prettyPrint = true }.encodeToString(summary))
        Log.i(TAG, "wrote summary to ${file.absolutePath}")
    }

    @Serializable data class SummaryDto(
        val modelFileName: String,
        val totalScenarios: Int,
        val totalPhrasings: Int,
        val scenariosFullyPassed: Int,
        val overallAccuracy: Double,
        val fieldAccuracy: Map<String, Double>,
        val totalDurationMs: Long,
        val avgPerPhrasingMs: Long,
        val failures: List<FailureDto>,
    )
    @Serializable data class FailureDto(
        val scenarioId: String, val tone: String, val prompt: String,
        val fieldsFailed: List<String>, val extracted: ExtractedDto, val expected: ExtractedDto,
    )
    @Serializable data class ExtractedDto(
        val packageName: String? = null, val channelId: String? = null,
        val category: String? = null, val notFromContact: Boolean = false,
        val action: String = "suppress",
    )

    companion object { private const val TAG = "EvalViewModel" }
}
