package ai.talkingrock.lithium.ui.briefing

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Yes/No buttons are wired to [BriefingViewModel.approveSuggestion] and
 * [BriefingViewModel.rejectSuggestion]. An optional comment field appears on tap.
 *
 * Design: Material 3, dark theme, high contrast, minimum 48dp tap targets.
 */
@Composable
fun BriefingScreen(
    viewModel: BriefingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = uiState.analysisRunning) {
            AnalysisRunningBanner()
        }
        when {
            uiState.isLoading -> LoadingState()
            uiState.report == null -> EmptyState(dataReady = uiState.dataReady)
            else -> ReportContent(uiState = uiState, viewModel = viewModel)
        }
    }
}

@Composable
private fun AnalysisRunningBanner() {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = "Analyzing your notifications…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
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
private fun EmptyState(dataReady: Boolean) {
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
            if (dataReady) {
                // Data threshold met — just no new report this cycle
                Text(
                    text = "No new report.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Your next briefing will appear after the nightly analysis " +
                            "runs — usually when your phone is charging overnight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Still collecting data — learning in progress
                Text(
                    text = "Lithium is learning",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Lithium is quietly watching your notifications and learning " +
                            "your patterns. This usually takes a few days.\n\n" +
                            "All your notifications will arrive normally during this time " +
                            "— nothing is being filtered yet.\n\n" +
                            "You'll get a notification when Lithium has collected enough " +
                            "data to produce your first briefing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReportContent(
    uiState: BriefingUiState,
    viewModel: BriefingViewModel
) {
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

        // 24-hour tier breakdown
        if (uiState.tierBreakdown24h.isNotEmpty()) {
            TierBreakdownCard(counts = uiState.tierBreakdown24h)
        }

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
                SuggestionCard(
                    suggestion = suggestion,
                    commentDraft = uiState.commentDrafts[suggestion.id] ?: "",
                    isCommentExpanded = uiState.expandedCommentId == suggestion.id,
                    onApprove = { viewModel.approveSuggestion(suggestion, report.id) },
                    onReject = { viewModel.rejectSuggestion(suggestion, report.id) },
                    onCommentChange = { text -> viewModel.updateCommentDraft(suggestion.id, text) },
                    onToggleComment = { viewModel.toggleCommentExpanded(suggestion.id) }
                )
            }
        }

        // Bottom padding to clear the navigation bar
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    commentDraft: String,
    isCommentExpanded: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCommentChange: (String) -> Unit,
    onToggleComment: () -> Unit
) {
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

            // Optional comment toggle — tap "Add comment" to expand the field.
            TextButton(
                onClick = onToggleComment,
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = if (isCommentExpanded) "Hide comment" else "Add comment",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Comment field — animated in/out
            AnimatedVisibility(visible = isCommentExpanded) {
                OutlinedTextField(
                    value = commentDraft,
                    onValueChange = onCommentChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Comment (optional)") },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(4.dp))

            // Yes / No buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Yes, try it")
                }
                OutlinedButton(
                    onClick = onReject,
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

/**
 * Compact per-tier count card: "Last 24 hours" with four labeled counts.
 * Tiers not present in [counts] render as 0.
 */
@Composable
private fun TierBreakdownCard(counts: Map<Int, Int>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = "Last 24 hours",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TierCell("Interrupt", counts[3] ?: 0, MaterialTheme.colorScheme.error)
                TierCell("Worth",     counts[2] ?: 0, MaterialTheme.colorScheme.primary)
                TierCell("Noise",     counts[1] ?: 0, MaterialTheme.colorScheme.onSurfaceVariant)
                TierCell("Invisible", counts[0] ?: 0, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TierCell(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
