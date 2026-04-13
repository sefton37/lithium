package ai.talkingrock.lithium.ui.eval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.ai.eval.ModelEvalHarness

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalScreen(
    onBack: () -> Unit,
    autoRun: Boolean = false,
    viewModel: EvalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(autoRun) { if (autoRun) viewModel.run() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model evaluation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Current model", fontWeight = FontWeight.SemiBold)
                        Text(state.modelFileName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Sideload a different .gguf and re-run to compare.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::run,
                        enabled = !state.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isRunning) "Running…" else "Run evaluation")
                    }
                }
            }

            if (state.isRunning && state.total > 0) {
                item {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${state.done} / ${state.total} phrasings", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            state.error?.let { err ->
                item {
                    Card { Text(err, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                }
            }

            state.report?.let { report ->
                item { SummaryCard(report) }
                item { FieldTable(report.fieldScores) }
                item { HorizontalDivider() }
                item { Text("Per-phrasing results", fontWeight = FontWeight.SemiBold) }
                items(report.results) { r -> PhrasingRow(r) }
            }
        }
    }
}

@Composable
private fun SummaryCard(report: ModelEvalHarness.EvalReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Summary", fontWeight = FontWeight.SemiBold)
            Text("Model: ${report.modelFileName}")
            Text("Overall field accuracy: ${"%.1f".format(report.overallAccuracy * 100)}%")
            Text("Scenarios fully passed: ${report.scenariosFullyPassed} / ${report.totalScenarios}")
            Text("Phrasings tested: ${report.totalPhrasings}")
            Text("Total time: ${"%.1f".format(report.totalDurationMs / 1000.0)}s")
            val avgMs = if (report.results.isNotEmpty())
                report.results.sumOf { it.durationMs } / report.results.size else 0L
            Text("Avg / phrasing: ${avgMs}ms")
        }
    }
}

@Composable
private fun FieldTable(scores: List<ModelEvalHarness.FieldScore>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Per-field accuracy", fontWeight = FontWeight.SemiBold)
            scores.forEach { s ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.field)
                    Text("${s.correct}/${s.total}  (${"%.1f".format(s.accuracy * 100)}%)")
                }
            }
        }
    }
}

@Composable
private fun PhrasingRow(r: ModelEvalHarness.PhrasingResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${r.scenarioId} · ${r.tone}", fontWeight = FontWeight.Medium)
                Text(
                    if (r.allPassed) "✓ pass" else "✗ ${r.fieldsFailed.size} fail",
                    color = if (r.allPassed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(r.prompt, style = MaterialTheme.typography.bodySmall)
            if (!r.allPassed) {
                Text(
                    "Failed: ${r.fieldsFailed.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Got: pkg=${r.extracted.packageName} ch=${r.extracted.channelId} cat=${r.extracted.category} " +
                        "nfc=${r.extracted.notFromContact} act=${r.extracted.action}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Expected: pkg=${r.expected.packageName} ch=${r.expected.channelId} cat=${r.expected.category} " +
                        "nfc=${r.expected.notFromContact} act=${r.expected.action}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text("${r.durationMs}ms", style = MaterialTheme.typography.labelSmall)
        }
    }
}
