package ai.talkingrock.lithium.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only screen showing the 50 most recent [NotificationRecord] rows.
 *
 * Gated behind [BuildConfig.DEBUG] in the nav graph — this composable is only reachable in
 * debug builds. It proves the listener is recording notifications during development.
 *
 * No actions. Read-only.
 */
@Composable
fun DebugNotificationLogScreen(
    viewModel: DebugNotificationLogViewModel = hiltViewModel()
) {
    val records by viewModel.recentNotifications.collectAsStateWithLifecycle()
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Debug: Notification Log",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        if (records.isEmpty()) {
            Text(
                text = "No notifications recorded yet.\nGrant Notification Access and wait for a notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn {
                items(records, key = { it.id }) { record ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = record.packageName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        record.title?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            text = dateFormat.format(Date(record.postedAtMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
