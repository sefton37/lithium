package ai.talkingrock.lithium.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings screen.
 *
 * Sections:
 * 1. Permissions status — live status with buttons to open system settings if not granted.
 * 2. Data retention — dropdown to select retention period (7–90 days). Stored in EncryptedSharedPreferences.
 * 3. Purge all data — confirmation dialog, then deletes all data except rules.
 * 4. Diagnostics opt-in — toggle (preference only; not yet wired to sending).
 * 5. About — app version, Talking Rock attribution, licenses placeholder.
 *
 * Material 3, dark theme, minimum 48dp tap targets.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect snackbar messages from the ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Contacts runtime permission launcher
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refresh()
    }

    // Purge confirmation dialog state
    var showPurgeDialog by rememberSaveable { mutableStateOf(false) }

    if (showPurgeDialog) {
        PurgeConfirmDialog(
            onConfirm = {
                showPurgeDialog = false
                viewModel.purgeAllData()
            },
            onDismiss = { showPurgeDialog = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(24.dp))

            // ── Section: Permissions ────────────────────────────────────────────────────
            SectionHeader("Permissions")
            Spacer(Modifier.height(12.dp))

            PermissionStatusRow(
                title = "Notification Access",
                description = "Required for Lithium to see and filter notifications.",
                granted = uiState.notificationAccessGranted,
                onGrant = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )
            Spacer(Modifier.height(12.dp))

            PermissionStatusRow(
                title = "Usage Access",
                description = "Tracks app usage after notification taps.",
                granted = uiState.usageAccessGranted,
                onGrant = {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            )
            Spacer(Modifier.height(12.dp))

            PermissionStatusRow(
                title = "Contacts (Recommended)",
                description = "Identifies notifications from people you know.",
                granted = uiState.contactsGranted,
                onGrant = {
                    contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                }
            )

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(28.dp))

            // ── Section: Data Retention ─────────────────────────────────────────────────
            SectionHeader("Data Retention")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Notifications older than the selected period are deleted automatically during overnight analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            RetentionDropdown(
                selectedDays = uiState.retentionDays,
                onSelect = viewModel::setRetentionDays
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Database: ~${uiState.notificationCount} notification records",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(28.dp))

            // ── Section: Analysis ────────────────────────────────────────────────────────
            SectionHeader("Analysis")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Controls when the AI analysis pipeline runs. Relaxing constraints lets it run more often but may use more battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            ConstraintToggleRow(
                title = "Require charging",
                description = "Only run when plugged into a wall charger.",
                checked = uiState.requireCharging,
                onCheckedChange = viewModel::setRequireCharging
            )
            Spacer(Modifier.height(8.dp))

            ConstraintToggleRow(
                title = "Require battery not low",
                description = "Skip analysis if battery is below ~15%.",
                checked = uiState.requireBatteryNotLow,
                onCheckedChange = viewModel::setRequireBatteryNotLow
            )
            Spacer(Modifier.height(8.dp))

            ConstraintToggleRow(
                title = "Require device idle",
                description = "Wait for Doze idle mode. Very restrictive — may prevent analysis from running.",
                checked = uiState.requireIdle,
                onCheckedChange = viewModel::setRequireIdle
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::runAnalysisNow,
                enabled = !uiState.isRunningAnalysis,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Run analysis now" }
            ) {
                Text(if (uiState.isRunningAnalysis) "Running…" else "Run Analysis Now")
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(28.dp))

            // ── Section: Purge Data ─────────────────────────────────────────────────────
            SectionHeader("Purge All Data")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Permanently delete all notification records, sessions, reports, suggestions, and queued items. Rules are not deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showPurgeDialog = true },
                enabled = !uiState.isPurging,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Purge all data" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(if (uiState.isPurging) "Purging…" else "Purge All Data")
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(28.dp))

            // ── Section: Diagnostics ────────────────────────────────────────────────────
            SectionHeader("Diagnostics")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Send anonymous crash reports and performance metrics. No notification content is ever sent. You can review each payload before it's sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Anonymous diagnostics",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 16.dp)
                )
                Switch(
                    checked = uiState.diagnosticsEnabled,
                    onCheckedChange = viewModel::setDiagnosticsEnabled,
                    modifier = Modifier.semantics {
                        contentDescription = if (uiState.diagnosticsEnabled) {
                            "Disable anonymous diagnostics"
                        } else {
                            "Enable anonymous diagnostics"
                        }
                    }
                )
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(28.dp))

            // ── Section: About ──────────────────────────────────────────────────────────
            SectionHeader("About")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Lithium v${viewModel.appVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "by Talking Rock",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://talkingrock.ai")
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .height(48.dp)
                    .semantics { contentDescription = "Visit talkingrock.ai" }
            ) {
                Text(
                    text = "talkingrock.ai",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Open-source licenses: see licenses.txt (in build artifacts)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// -----------------------------------------------------------------------------------------
// Sub-composables
// -----------------------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun PermissionStatusRow(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (granted) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "$title granted",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(
                onClick = onGrant,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Grant")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionDropdown(
    selectedDays: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "$selectedDays days",
            onValueChange = {},
            readOnly = true,
            label = { Text("Keep data for") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .semantics { contentDescription = "Data retention period: $selectedDays days" }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            RETENTION_OPTIONS.forEach { days ->
                DropdownMenuItem(
                    text = { Text("$days days") },
                    onClick = {
                        onSelect(days)
                        expanded = false
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
            }
        }
    }
}

@Composable
private fun ConstraintToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = if (checked) "Disable $title" else "Enable $title"
            }
        )
    }
}

@Composable
private fun PurgeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Purge All Data?") },
        text = {
            Text(
                "This will permanently delete all notifications, sessions, reports, suggestions, and queued items. Your rules will not be deleted. This cannot be undone.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Purge")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}
