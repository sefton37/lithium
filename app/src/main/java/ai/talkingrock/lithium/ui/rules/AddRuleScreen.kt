package ai.talkingrock.lithium.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Add Rule screen.
 *
 * A form for creating a user-sourced rule without going through an AI suggestion.
 * Fields:
 * - App selector (dropdown populated from observed notification packages)
 * - Action selector (Suppress / Queue)
 * - Optional channel filter
 * - Description (auto-generated, editable)
 *
 * On save, a Rule with source=user, status=approved is inserted. The RuleEngine picks it up
 * automatically through the StateFlow cache.
 *
 * [onSaved] is called after successful save; the caller pops the back stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRuleScreen(
    onSaved: () -> Unit,
    viewModel: AddRuleViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate back once save succeeds.
    LaunchedEffect(uiState.saveComplete) {
        if (uiState.saveComplete) onSaved()
    }

    when {
        uiState.isLoadingPackages -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "New Rule",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // ---- App selector ----
                AppDropdown(
                    packages = uiState.availablePackages,
                    selectedPackage = uiState.selectedPackage,
                    onSelected = { viewModel.selectPackage(it) }
                )

                // ---- Action selector ----
                ActionDropdown(
                    selected = uiState.selectedAction,
                    onSelected = { viewModel.selectAction(it) }
                )

                // ---- Channel filter ----
                OutlinedTextField(
                    value = uiState.channelFilter,
                    onValueChange = { viewModel.updateChannelFilter(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Channel filter (optional)") },
                    placeholder = { Text("e.g. email_notifications") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                // ---- Description ----
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = { viewModel.updateDescription(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                // ---- Error ----
                if (uiState.error != null) {
                    Text(
                        text = uiState.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // ---- Save button ----
                Button(
                    onClick = { viewModel.saveRule() },
                    enabled = !uiState.isSaving && uiState.selectedPackage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save Rule")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDropdown(
    packages: List<String>,
    selectedPackage: String?,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedPackage ?: "",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            label = { Text("App") },
            placeholder = { Text("Select an app") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodyMedium
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (packages.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No apps observed yet. Notifications will appear here after Lithium runs.") },
                    onClick = { expanded = false }
                )
            } else {
                packages.forEach { pkg ->
                    DropdownMenuItem(
                        text = { Text(pkg, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onSelected(pkg)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDropdown(
    selected: RuleActionOption,
    onSelected: (RuleActionOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            label = { Text("Action") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodyMedium
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            RuleActionOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
