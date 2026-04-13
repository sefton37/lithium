package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Aggregated Elo-style ranking for a single (package, channel) pair, built from
 * channel-vs-channel judgments in the Training tab.
 *
 * Mirrors [AppRanking] exactly, but keyed on the composite (package_name, channel_id)
 * primary key — the same shape as [NotificationChannel].
 *
 * Scores start at [DEFAULT_ELO] = 1200. Updates follow the standard Elo formula with
 * K=32 (see [ai.talkingrock.lithium.ui.training.updateElo]).
 *
 * Channel ratings feed the hierarchical scorer: θ_c shrinks toward its parent app's
 * θ_a when [judgments] is low, and asserts its own signal as [judgments] grows.
 */
@Entity(
    tableName = "channel_rankings",
    primaryKeys = ["package_name", "channel_id"]
)
data class ChannelRanking(
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "channel_id")
    val channelId: String,

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
