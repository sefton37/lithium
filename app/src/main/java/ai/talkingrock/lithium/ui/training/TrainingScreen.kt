package ai.talkingrock.lithium.ui.training

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.ai.AppNames
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrainingScreen(
    onOpenReport: () -> Unit = {},
    viewModel: TrainingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trainer by viewModel.trainer.collectAsStateWithLifecycle()
    val activeQuest by viewModel.activeQuest.collectAsStateWithLifecycle()
    val questXp by viewModel.questXp.collectAsStateWithLifecycle()
    val judgedCount by viewModel.judgmentCount.collectAsStateWithLifecycle()

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
                setPosition = state.setPosition,
                onOpenReport = onOpenReport
            )
            QuestChipRow(
                activeQuestId = activeQuest.id,
                questXp = questXp,
                onSelect = viewModel::selectQuest
            )
            when {
                state.isLoading -> LoadingState()
                state.exhausted -> ExhaustedState(count = judgedCount, quest = activeQuest)
                state.left != null && state.right != null -> PairContent(
                    left = state.left!!,
                    right = state.right!!,
                    battle = state.lastBattle,
                    onLeft = { viewModel.submit("left") },
                    onRight = { viewModel.submit("right") },
                    onTie = { viewModel.submit("tie") },
                    onSkip = { viewModel.submit("skip") }
                )
            }
        }
        FloatingXpCallout(eventId = lastXpEvent.first, event = lastXpEvent.second)
    }
}

// -----------------------------------------------------------------------------------------
// Header
// -----------------------------------------------------------------------------------------

@Composable
private fun TrainerHeader(
    trainer: TrainerSnapshot,
    judgedCount: Int,
    setPosition: Int,
    onOpenReport: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = trainer.progressWithinLevel,
        animationSpec = tween(durationMillis = 450),
        label = "levelProgress"
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = trainer.level.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    trainer.level.unlock,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${trainer.xp} XP",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onOpenReport) {
                    Icon(Icons.Filled.Info, contentDescription = "Training report")
                }
            }
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
        val nextLabel = trainer.nextLevel?.let {
            val toGo = (it.floor - trainer.xp).coerceAtLeast(0)
            "$toGo XP to ${it.name}"
        } ?: "Master — RLHF unlocked"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SetDots(position = setPosition)
            Text(
                nextLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetDots(position: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(SET_SIZE) { i ->
            val filled = i < position
            Box(
                modifier = Modifier
                    .size(8.dp)
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
// Quest chips
// -----------------------------------------------------------------------------------------

@Composable
private fun QuestChipRow(
    activeQuestId: String,
    questXp: Map<String, Int>,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Quests.all.forEach { quest ->
            val xp = questXp[quest.id] ?: 0
            val label = when {
                quest.id == Quests.FREE_PLAY_ID -> quest.name
                xp >= quest.goalXp -> "${quest.name} ✓"
                else -> "${quest.name} · $xp/${quest.goalXp}"
            }
            FilterChip(
                selected = quest.id == activeQuestId,
                onClick = { onSelect(quest.id) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// Pair content with battle animation
// -----------------------------------------------------------------------------------------

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ExhaustedState(count: Int, quest: Quest) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No pairs in ${quest.name}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (count == 0)
                    "Collect more notifications and they'll appear here."
                else
                    "Try another quest — each sharpens a different slice of the model.",
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
    battle: BattleOutcome?,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onTie: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BattleCard(
            label = "A",
            record = left,
            state = when (battle) {
                BattleOutcome.LEFT_WINS -> CardBattleState.WINNING
                BattleOutcome.RIGHT_WINS -> CardBattleState.LOSING
                BattleOutcome.TIE -> CardBattleState.TIED
                else -> CardBattleState.NEUTRAL
            }
        )
        Button(
            onClick = onLeft,
            enabled = battle == null,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("A is more important") }

        BattleCard(
            label = "B",
            record = right,
            state = when (battle) {
                BattleOutcome.RIGHT_WINS -> CardBattleState.WINNING
                BattleOutcome.LEFT_WINS -> CardBattleState.LOSING
                BattleOutcome.TIE -> CardBattleState.TIED
                else -> CardBattleState.NEUTRAL
            }
        )
        Button(
            onClick = onRight,
            enabled = battle == null,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("B is more important") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onTie,
                enabled = battle == null,
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Tie") }
            TextButton(
                onClick = onSkip,
                enabled = battle == null,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Text("Skip") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private enum class CardBattleState { NEUTRAL, WINNING, LOSING, TIED }

/**
 * Notification card with animated scale/alpha driven by [state]. Winners
 * briefly pulse up in scale; losers shrink to nothing; tied cards pulse lightly.
 */
@Composable
private fun BattleCard(label: String, record: NotificationRecord, state: CardBattleState) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(state, record.id) {
        when (state) {
            CardBattleState.WINNING -> {
                scale.snapTo(1f); alpha.snapTo(1f)
                scope.launch { scale.animateTo(1.08f, tween(180)) }
                scope.launch { alpha.animateTo(1f) }
            }
            CardBattleState.LOSING -> {
                scope.launch { scale.animateTo(0.6f, tween(400)) }
                scope.launch { alpha.animateTo(0f, tween(500)) }
            }
            CardBattleState.TIED -> {
                scope.launch { scale.animateTo(0.95f, tween(150)) }
                scope.launch { alpha.animateTo(0.8f, tween(150)) }
            }
            CardBattleState.NEUTRAL -> {
                scale.snapTo(1f); alpha.snapTo(1f)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                CardBattleState.WINNING -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
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
                Text(it, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            record.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                formatTimestamp(record.postedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// Floating XP callout
// -----------------------------------------------------------------------------------------

@Composable
private fun FloatingXpCallout(eventId: Int, event: XpEvent?) {
    if (event == null) return
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(eventId) {
        visible = true
        delay(when (event) {
            is XpEvent.QuestComplete -> 2400L
            is XpEvent.SetComplete -> 1800L
            else -> 900L
        })
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 4 },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (event) {
                is XpEvent.Judgment ->
                    XpBadge("+${event.xp} XP", "signal captured")
                is XpEvent.SetComplete ->
                    XpBadge("Set complete! +${event.bonusXp} bonus",
                        "${event.totalSetXp + event.bonusXp} total this set")
                is XpEvent.QuestComplete ->
                    XpBadge("Quest complete: ${event.quest.name}",
                        "+${event.totalXp} XP toward this slice")
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
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
