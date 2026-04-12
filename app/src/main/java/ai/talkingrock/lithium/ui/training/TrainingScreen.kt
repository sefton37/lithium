package ai.talkingrock.lithium.ui.training

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.ai.AppNames
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrainingScreen(viewModel: TrainingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trainer by viewModel.trainer.collectAsStateWithLifecycle()
    val judgedCount by viewModel.judgmentCount.collectAsStateWithLifecycle()

    // Floating XP callouts keyed to an incrementing id so multiple rapid
    // judgments can stack briefly.
    var lastXpEvent by remember { mutableStateOf<Pair<Int, XpEvent?>>(0 to null) }
    LaunchedEffect(viewModel) {
        viewModel.xpEvents.collect { event ->
            lastXpEvent = (lastXpEvent.first + 1) to event
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TrainerHeader(
                trainer = trainer,
                judgedCount = judgedCount,
                setPosition = state.setPosition
            )
            when {
                state.isLoading -> LoadingState()
                state.exhausted -> ExhaustedState(count = judgedCount)
                state.left != null && state.right != null -> PairContent(
                    left = state.left!!,
                    right = state.right!!,
                    onLeft = { viewModel.submit("left") },
                    onRight = { viewModel.submit("right") },
                    onTie = { viewModel.submit("tie") },
                    onSkip = { viewModel.submit("skip") }
                )
            }
        }

        // XP callouts sit on top of everything.
        FloatingXpCallout(eventId = lastXpEvent.first, event = lastXpEvent.second)
    }
}

// -----------------------------------------------------------------------------------------
// Header: level, XP, progress, set dots
// -----------------------------------------------------------------------------------------

@Composable
private fun TrainerHeader(
    trainer: TrainerSnapshot,
    judgedCount: Int,
    setPosition: Int
) {
    val animatedProgress by animateFloatAsState(
        targetValue = trainer.progressWithinLevel,
        animationSpec = tween(durationMillis = 450),
        label = "levelProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = trainer.level.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${trainer.xp} XP",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
        )

        val nextLabel = trainer.nextLevel?.let {
            val toGo = (it.floor - trainer.xp).coerceAtLeast(0)
            "${toGo} XP to ${it.name}"
        } ?: "Master — keep closing the gap"
        Text(
            text = nextLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SetDots(position = setPosition)
            Text(
                text = "$judgedCount total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetDots(position: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(SET_SIZE) { i ->
            val filled = i < position
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// Pair content
// -----------------------------------------------------------------------------------------

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
                text = "No pairs to judge",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (count == 0)
                    "As Lithium collects more notifications, candidate pairs will appear here."
                else
                    "You've closed the ambiguity gap. New pairs will surface as your " +
                    "notification history grows and classification refines.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PairContent(
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NotificationCard(label = "A", record = left)
        Button(
            onClick = onLeft,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("A is more important") }

        NotificationCard(label = "B", record = right)
        Button(
            onClick = onRight,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("B is more important") }

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
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
            record.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            record.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = formatTimestamp(record.postedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// Floating XP callout — feedback on how much each judgment taught the model
// -----------------------------------------------------------------------------------------

@Composable
private fun FloatingXpCallout(eventId: Int, event: XpEvent?) {
    if (event == null) return
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(eventId) {
        visible = true
        delay(if (event is XpEvent.SetComplete) 1800L else 900L)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 4 },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            when (event) {
                is XpEvent.Judgment -> XpBadge(
                    text = "+${event.xp} XP",
                    sub = "signal captured"
                )
                is XpEvent.SetComplete -> XpBadge(
                    text = "Set complete! +${event.bonusXp} bonus",
                    sub = "earned ${event.totalSetXp + event.bonusXp} total this set"
                )
            }
        }
    }
}

@Composable
private fun XpBadge(text: String, sub: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(top = 72.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

// -----------------------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------------------

private fun tierBadge(tier: Int): String = when (tier) {
    0 -> "Invisible"
    1 -> "Noise"
    2 -> "Worth"
    3 -> "Interrupt"
    else -> "T$tier"
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
