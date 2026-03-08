package ai.talkingrock.lithium.ui.setup

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Setup screen — shown to first-time users or when Notification Access is not granted.
 *
 * Three permissions:
 * 1. Notification Access (required) — system settings deep-link
 * 2. Usage Access (required for M2 session tracking) — system settings deep-link
 * 3. Contacts (optional, recommended) — runtime permission dialog
 *
 * Navigation: when [SetupUiState.notificationAccessGranted] becomes true, navigate to
 * the briefing screen.
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Navigate to briefing as soon as notification access is granted.
    LaunchedEffect(uiState.notificationAccessGranted) {
        if (uiState.notificationAccessGranted) {
            onSetupComplete()
        }
    }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // State updates automatically via the ViewModel's flow on recomposition.
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "Set up Lithium",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Lithium needs a few permissions to manage your notifications.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // 1. Notification Access
        PermissionRow(
            title = "Notification Access",
            description = "Required. Allows Lithium to see and filter notifications.",
            granted = uiState.notificationAccessGranted,
            onGrant = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        // 2. Usage Access
        PermissionRow(
            title = "Usage Access",
            description = "Tracks how long you use apps after tapping a notification.",
            granted = uiState.usageAccessGranted,
            onGrant = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        )

        // 3. Contacts (optional)
        PermissionRow(
            title = "Contacts (Recommended)",
            description = "Identifies notifications from people you know. Optional.",
            granted = uiState.contactsGranted,
            onGrant = {
                contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
            }
        )
    }
}

@Composable
private fun PermissionRow(
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
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (granted) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Granted",
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
