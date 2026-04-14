package ai.talkingrock.lithium.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Chat tab entry point.
 *
 * A tool-launcher UI: action cards at the top, conversation thread in the middle,
 * input bar at the bottom. Tool outputs (briefing reports, rule draft forms) are
 * rendered as inline messages in the thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message when the thread grows.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            ToolLauncher(
                isBriefingRunning = state.isBriefingRunning,
                isQaThinking = state.isQaThinking,
                onInvokeBriefing = viewModel::invokeBriefing,
                onStartRuleTool = viewModel::startRuleCreationTool,
                onStartQa = viewModel::startQaMode,
            )
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages) { msg ->
                    ChatMessageItem(
                        msg = msg,
                        onFieldEdit = viewModel::updateDraftField,
                        onApprove = viewModel::approveRule,
                        onCancel = viewModel::cancelDraft,
                    )
                }
                if (state.isExtracting || state.isBriefingRunning) {
                    item { IndeterminateRow() }
                }
            }

            // Input bar is visible for RULE_CREATION and Q&A modes (DOD-18).
            // In Q&A mode, submitQaInput is called directly with the current draft.
            if (state.activeTool == ChatTool.RULE_CREATION || state.activeTool == ChatTool.QA) {
                val isQaMode = state.activeTool == ChatTool.QA
                HorizontalDivider()
                ChatInputBar(
                    value = state.inputDraft,
                    onValueChange = viewModel::updateInputDraft,
                    onSend = {
                        if (isQaMode) {
                            viewModel.submitQaInput(state.inputDraft)
                            viewModel.updateInputDraft("")
                        } else {
                            viewModel.submitInput()
                        }
                    },
                    enabled = if (isQaMode) !state.isQaThinking else !state.isExtracting,
                    placeholder = if (isQaMode) "Ask about your notification history…"
                                  else "Describe or refine the rule…",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tool launcher
// ---------------------------------------------------------------------------

@Composable
private fun ToolLauncher(
    isBriefingRunning: Boolean,
    isQaThinking: Boolean,
    onInvokeBriefing: () -> Unit,
    onStartRuleTool: () -> Unit,
    onStartQa: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Info,
            label = "Briefing",
            sublabel = if (isBriefingRunning) "Generating…" else "Generate now",
            enabled = !isBriefingRunning,
            onClick = onInvokeBriefing,
        )
        ToolCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Build,
            label = "Create rule",
            sublabel = "From a description",
            enabled = true,
            onClick = onStartRuleTool,
        )
        // Q&A tool card — always visible; input bar shown in ChatTool.QA mode (DOD-18).
        ToolCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Search,
            label = "Ask",
            sublabel = if (isQaThinking) "Thinking…" else "Ask anything",
            enabled = !isQaThinking,
            onClick = onStartQa,
        )
    }
}

@Composable
private fun ToolCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(sublabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Message rendering
// ---------------------------------------------------------------------------

@Composable
private fun ChatMessageItem(
    msg: ChatMessage,
    onFieldEdit: (String, (RuleDraftState) -> RuleDraftState) -> Unit,
    onApprove: (RuleDraftState) -> Unit,
    onCancel: () -> Unit,
) {
    when (msg) {
        is ChatMessage.UserText -> UserBubble(msg.text)
        is ChatMessage.SystemMessage -> SystemRow(msg.text)
        is ChatMessage.BriefingResult -> BriefingCard(msg)
        is ChatMessage.RuleExtractionProgress -> ProgressRow(msg)
        is ChatMessage.RuleDraft -> RuleDraftCard(msg.draft, onFieldEdit, onApprove, onCancel)
        // Q&A assistant answer — rendered as a left-aligned assistant bubble.
        is ChatMessage.AssistantAnswer -> AssistantAnswerBubble(msg.text)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(text, modifier = Modifier.padding(10.dp))
        }
    }
}

/** Left-aligned card for natural-language answers from the Q&A tool-calling loop. */
@Composable
private fun AssistantAnswerBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(text, modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
private fun SystemRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

@Composable
private fun BriefingCard(msg: ChatMessage.BriefingResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Briefing", fontWeight = FontWeight.SemiBold)
            Text(msg.reportText, modifier = Modifier.padding(top = 6.dp))
            if (msg.suggestionCount > 0) {
                Text(
                    "${msg.suggestionCount} suggestion(s) available — see the Rules tab.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressRow(msg: ChatMessage.RuleExtractionProgress) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Analysing field ${msg.fieldsComplete} of ${msg.totalFields}…",
            style = MaterialTheme.typography.labelSmall,
        )
        LinearProgressIndicator(
            progress = { msg.fieldsComplete.toFloat() / msg.totalFields },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IndeterminateRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

// ---------------------------------------------------------------------------
// Rule review form
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDraftCard(
    draft: RuleDraftState,
    onFieldEdit: (String, (RuleDraftState) -> RuleDraftState) -> Unit,
    onApprove: (RuleDraftState) -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review rule", fontWeight = FontWeight.SemiBold)
            Text("You said: \"${draft.originalInput}\"", style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = draft.packageName.orEmpty(),
                onValueChange = { v -> onFieldEdit(RuleDraftFields.PACKAGE) { it.copy(packageName = v.ifBlank { null }) } },
                label = { Text("App package") },
                placeholder = { if (draft.packageName == null) Text("Couldn't determine — fill in") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = draft.channelId.orEmpty(),
                onValueChange = { v -> onFieldEdit(RuleDraftFields.CHANNEL) { it.copy(channelId = v.ifBlank { null }) } },
                label = { Text("Channel (optional)") },
                placeholder = { Text("Leave empty to match all channels") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            CategoryDropdown(
                selected = draft.category,
                onSelect = { v -> onFieldEdit(RuleDraftFields.CATEGORY) { it.copy(category = v) } },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.notFromContact,
                    onCheckedChange = { v ->
                        onFieldEdit(RuleDraftFields.NOT_FROM_CONTACT) { it.copy(notFromContact = v) }
                    },
                )
                Text("Only when sender is NOT a contact")
            }

            ActionDropdown(
                selected = draft.action,
                onSelect = { v -> onFieldEdit(RuleDraftFields.ACTION) { it.copy(action = v) } },
            )

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel, enabled = !draft.isSaving) { Text("Cancel") }
                FilledTonalButton(
                    onClick = { onApprove(draft) },
                    enabled = !draft.isSaving && draft.savedRuleId == null,
                ) {
                    Text(if (draft.savedRuleId != null) "Saved" else "Approve & save")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(selected: String?, onSelect: (String?) -> Unit) {
    val options = listOf(
        null, "personal", "engagement_bait", "promotional",
        "transactional", "system", "social_signal", "background",
    )
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected ?: "(any)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt ?: "(any)") },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("suppress", "queue")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Action") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Input bar
// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    placeholder: String = "Describe or refine the rule…",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            enabled = enabled,
            maxLines = 4,
        )
        IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Icon(Icons.Filled.Send, contentDescription = "Send")
        }
    }
}
