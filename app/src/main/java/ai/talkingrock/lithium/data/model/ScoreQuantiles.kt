package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row storing the user's score-distribution quantile thresholds.
 *
 * Recomputed nightly by AiAnalysisWorker (Step 6.5) from the last [windowDays]
 * days of scored notifications. The scorer reads these thresholds to map a
 * continuous score s(x) → tier {0, 1, 2, 3}.
 *
 * Always stored as id=0 (singleton). Upsert replaces the previous row.
 *
 * Tier mapping:
 *   tier 3 (Interrupt) if s ≥ q90
 *   tier 2 (Worth)     if s ≥ q60
 *   tier 1 (Noise)     if s ≥ q20
 *   tier 0 (Invisible) else
 */
@Entity(tableName = "score_quantiles")
data class ScoreQuantiles(
    @PrimaryKey
    val id: Int = 0,

    @ColumnInfo(name = "window_days")
    val windowDays: Int,

    @ColumnInfo(name = "q20")
    val q20: Double,

    @ColumnInfo(name = "q60")
    val q60: Double,

    @ColumnInfo(name = "q90")
    val q90: Double,

    @ColumnInfo(name = "computed_at_ms")
    val computedAtMs: Long,

    @ColumnInfo(name = "sample_size")
    val sampleSize: Int
)
