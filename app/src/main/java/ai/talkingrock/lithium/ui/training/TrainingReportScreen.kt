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
import ai.talkingrock.lithium.data.db.PatternStat
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
    val questXp: Map<String, Int>,
    val topUnmappedPatterns: List<PatternStat> = emptyList()
)

@HiltViewModel
class TrainingReportViewModel @Inject constructor(
    notificationDao: NotificationDao,
    private val judgmentDao: TrainingJudgmentDao
) : ViewModel() {

    private val _choices = MutableStateFlow<Map<String, Int>>(emptyMap())

    private data class Core(
        val xp: Int, val total: Int, val pool: Int
    )

    val state: StateFlow<TrainingReportState> = combine(
        combine(
            judgmentDao.totalXpFlow(),
            judgmentDao.countFlow(),
            notificationDao.countAmbiguityPoolFlow()
        ) { xp, total, pool -> Core(xp, total, pool) },
        _choices,
        judgmentDao.xpByQuestFlow().map { it.associate { q -> q.questId to q.xp } },
        notificationDao.getPatternStatsFlow()
    ) { core, choices, questXp, stats ->
        val mapped = stats.count { it.isMapped() }
        TrainingReportState(
            trainer = TrainerLevels.snapshot(core.xp, mapped, stats.size),
            totalJudged = core.total,
            poolSize = core.pool,
            choiceBreakdown = choices,
            questXp = questXp,
            topUnmappedPatterns = stats
                .filterNot { it.isMapped() }
                .sortedByDescending { it.total }
                .take(5)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TrainingReportState(
            trainer = TrainerLevels.snapshot(0, 0, 0),
            totalJudged = 0,
            poolSize = 0,
            choiceBreakdown = emptyMap(),
            questXp = emptyMap(),
            topUnmappedPatterns = emptyList()
        )
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
            PatternsMappedCard(s.trainer)
            if (s.topUnmappedPatterns.isNotEmpty()) {
                UnmappedPatternsCard(s.topUnmappedPatterns)
            }
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
                "${trainer.patternsMapped}/${it.floorPatterns} patterns to ${it.name} · ${trainer.xp} XP"
            } ?: "Master · ${trainer.xp} XP"
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
private fun PatternsMappedCard(trainer: TrainerSnapshot) {
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
                "Notification patterns mapped",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val pct = if (trainer.patternsTotal == 0) 0f
            else (trainer.patternsMapped.toFloat() / trainer.patternsTotal).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Text(
                "${trainer.patternsMapped} of ${trainer.patternsTotal} patterns mapped " +
                    "(${(pct * 100).toInt()}%)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Every app sends a few kinds of notifications — LinkedIn nudges, Gmail " +
                    "from strangers, Amazon shipping pings. Lithium groups them by kind. " +
                    "Judging 3 examples of each kind is all Lithium needs to learn your taste " +
                    "for that whole group — so you don't have to grade thousands one by one.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnmappedPatternsCard(patterns: List<PatternStat>) {
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
                "Biggest gaps — still unmapped",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "These kinds fill the most space in your history. Judging a few from each " +
                    "will level you up fastest.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            patterns.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${ai.talkingrock.lithium.ai.AppNames.friendlyName(p.packageName)} · ${p.tierReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${p.judged}/$MIN_JUDGMENTS_TO_MAP · ${p.total} rows",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
