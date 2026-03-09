package ai.talkingrock.lithium.ui.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.data.model.Suggestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Briefing screen — the home screen of Lithium.
 *
 * Shows the latest unreviewed AI-generated report and any pending suggestion cards.
 * Suggestion approval is wired in M5; for now the cards are display-only.
 *
 * Design: Material 3, dark theme, high contrast, minimum 48dp tap targets.
 */
@Composable
fun BriefingScreen(
    viewModel: BriefingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.isLoading -> LoadingState()
        uiState.report == null -> EmptyState()
        else -> ReportContent(uiState = uiState)
    }
}

// -----------------------------------------------------------------------------------------
// Sub-composables
// -----------------------------------------------------------------------------------------

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No new report.",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Your next briefing will appear after the nightly analysis runs — usually when your phone is charging overnight.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReportContent(uiState: BriefingUiState) {
    val report = uiState.report ?: return

    // Extract the human-readable text from the JSON summary.
    val reportText = extractReportText(report.summaryJson)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Your Briefing",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Report text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Text(
                text = reportText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
        }

        // Suggestions section — only shown when suggestions exist
        if (uiState.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Suggestions",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            uiState.suggestions.forEach { suggestion ->
                SuggestionCard(suggestion = suggestion)
            }
        }

        // Bottom padding to clear the navigation bar
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SuggestionCard(suggestion: Suggestion) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Suggestion: ${suggestion.rationale}" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Action badge (suppress / queue)
            val actionLabel = suggestion.action.replaceFirstChar { it.uppercaseChar() }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Rationale
            Text(
                text = suggestion.rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            // Yes / No buttons — wired in M5; currently present for layout fidelity.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { /* M5: accept suggestion */ },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Yes, try it")
                }
                OutlinedButton(
                    onClick = { /* M5: reject suggestion */ },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("No thanks")
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------------------

/**
 * Extracts the `text` field from [summaryJson].
 * Falls back to the raw JSON string if parsing fails or the field is missing.
 */
private fun extractReportText(summaryJson: String): String {
    return try {
        val obj = Json.parseToJsonElement(summaryJson) as? JsonObject
        obj?.get("text")?.jsonPrimitive?.content ?: summaryJson
    } catch (_: Exception) {
        summaryJson
    }
}
