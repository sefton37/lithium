package ai.talkingrock.lithium.ui.training

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.ai.AppNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Training tab — shows two notifications side-by-side (stacked on phone) and
 * asks the user to mark which is more important. Each judgment is persisted
 * to `training_judgments` and drives active-learning pair selection.
 */
@Composable
fun TrainingScreen(viewModel: TrainingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val count by viewModel.judgmentCount.collectAsStateWithLifecycle()

    when {
        state.isLoading -> LoadingState()
        state.exhausted -> ExhaustedState(count = count)
        state.left != null && state.right != null -> PairContent(
            count = count,
            left = state.left!!,
            right = state.right!!,
            onLeft = { viewModel.submit("left") },
            onRight = { viewModel.submit("right") },
            onTie = { viewModel.submit("tie") },
            onSkip = { viewModel.submit("skip") }
        )
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ExhaustedState(count: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Nothing to judge right now",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (count == 0)
                    "As Lithium collects more notifications, candidate pairs will appear here."
                else
                    "You've reviewed $count pair(s). New candidates will surface as your " +
                    "notification history grows and classification updates.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PairContent(
    count: Int,
    left: NotificationRecord,
    right: NotificationRecord,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onTie: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Training",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Which notification matters more to you? $count judged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NotificationCard(label = "A", record = left)
        Button(
            onClick = onLeft,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("A is more important") }

        Spacer(Modifier.height(4.dp))

        NotificationCard(label = "B", record = right)
        Button(
            onClick = onRight,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("B is more important") }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onTie,
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Tie") }
            TextButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Text("Skip") }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NotificationCard(label: String, record: NotificationRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$label · ${AppNames.friendlyName(record.packageName)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = tierBadge(record.tier),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            record.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            record.text?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = formatTimestamp(record.postedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun tierBadge(tier: Int): String = when (tier) {
    0 -> "Invisible"
    1 -> "Noise"
    2 -> "Worth"
    3 -> "Interrupt"
    else -> "T$tier"
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
