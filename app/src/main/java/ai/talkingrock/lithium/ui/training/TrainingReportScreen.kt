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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.data.db.ChoiceCount
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of everything the report screen needs. */
data class TrainingReportState(
    val trainer: TrainerSnapshot,
    val totalJudged: Int,
    val poolSize: Int,
    val choiceBreakdown: Map<String, Int>,
    val questXp: Map<String, Int>
)

@HiltViewModel
class TrainingReportViewModel @Inject constructor(
    notificationDao: NotificationDao,
    private val judgmentDao: TrainingJudgmentDao
) : ViewModel() {

    private val _choices = MutableStateFlow<Map<String, Int>>(emptyMap())
    val state: StateFlow<TrainingReportState> = combine(
        judgmentDao.totalXpFlow(),
        judgmentDao.countFlow(),
        notificationDao.countAmbiguityPoolFlow(),
        _choices,
        judgmentDao.xpByQuestFlow().map { it.associate { q -> q.questId to q.xp } }
    ) { xp, total, pool, choices, questXp ->
        TrainingReportState(
            trainer = TrainerLevels.snapshot(xp),
            totalJudged = total,
            poolSize = pool,
            choiceBreakdown = choices,
            questXp = questXp
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TrainingReportState(TrainerLevels.snapshot(0), 0, 0, emptyMap(), emptyMap())
    )

    init {
        viewModelScope.launch {
            _choices.value = judgmentDao.getChoiceBreakdown().associate { it.choice to it.count }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingReportScreen(
    onBack: () -> Unit,
    viewModel: TrainingReportViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Training report") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LevelCard(s.trainer, s.totalJudged)
            GapClosedCard(s.totalJudged, s.poolSize)
            ChoiceBreakdownCard(s.choiceBreakdown)
            QuestProgressCard(s.questXp)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LevelCard(trainer: TrainerSnapshot, totalJudged: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                trainer.level.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                trainer.level.unlock,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { trainer.progressWithinLevel },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            val sub = trainer.nextLevel?.let {
                "${trainer.xp} XP · ${(it.floor - trainer.xp).coerceAtLeast(0)} to ${it.name}"
            } ?: "${trainer.xp} XP · Master"
            Text(sub, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "$totalJudged pairs evaluated",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GapClosedCard(judged: Int, pool: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Ambiguity closed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val pct = if (pool == 0) 0f else (judged.toFloat() / pool).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Text(
                "${(pct * 100).toInt()}% · $judged of $pool candidates judged",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Leveling focuses on inflection points (quality), not exhaustion (quantity). " +
                "A small amount of training in each quest is worth more than grinding through everything.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChoiceBreakdownCard(counts: Map<String, Int>) {
    val total = counts.values.sum().coerceAtLeast(1)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Choice breakdown",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            listOf("left", "right", "tie", "skip").forEach { key ->
                val n = counts[key] ?: 0
                val pct = (n.toFloat() / total * 100).toInt()
                ChoiceRow(label = key.replaceFirstChar { it.uppercase() }, count = n, pct = pct)
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, count: Int, pct: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text("$count ($pct%)", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuestProgressCard(questXp: Map<String, Int>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Quests",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Quests.all.filter { it.id != Quests.FREE_PLAY_ID }.forEach { quest ->
                val xp = questXp[quest.id] ?: 0
                val pct = if (quest.goalXp == 0) 0f
                else (xp.toFloat() / quest.goalXp).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(quest.name, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (xp >= quest.goalXp) "Complete · $xp XP" else "$xp / ${quest.goalXp} XP",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (xp >= quest.goalXp) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}
