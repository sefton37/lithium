package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Aggregated Elo-style ranking for a single Android package, built from
 * app-vs-app judgments in the Training tab.
 *
 * Scores start at [DEFAULT_ELO]. Updates follow the standard Elo formula:
 *   expected = 1 / (1 + 10^((opponent - self) / 400))
 *   new_self = self + K * (actual - expected)
 * where actual = 1 (win) / 0.5 (tie) / 0 (loss) and K = 32.
 *
 * The score is a *prior* that feeds downstream: suggestion ranking,
 * candidate pair selection weights. It captures app-level preference
 * only — within-app nuance comes from notification-pair judgments.
 */
@Entity(tableName = "app_rankings")
data class AppRanking(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "elo_score")
    val eloScore: Int = DEFAULT_ELO,

    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,

    @ColumnInfo(name = "judgments")
    val judgments: Int = 0,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long
) {
    companion object {
        const val DEFAULT_ELO = 1200
    }
}
