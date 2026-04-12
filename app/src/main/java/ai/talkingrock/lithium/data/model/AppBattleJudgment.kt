package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single app-vs-app judgment emitted by the Training tab. Each row
 * captures both scores at judgment time so retroactive Elo recomputation
 * is possible if the formula ever changes.
 *
 * [choice] values: "left" | "right" | "tie" | "skip".
 */
@Entity(tableName = "app_battle_judgments")
data class AppBattleJudgment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "left_package")
    val leftPackage: String,

    @ColumnInfo(name = "right_package")
    val rightPackage: String,

    val choice: String,

    @ColumnInfo(name = "left_elo_before")
    val leftEloBefore: Int,

    @ColumnInfo(name = "right_elo_before")
    val rightEloBefore: Int,

    @ColumnInfo(name = "left_elo_after")
    val leftEloAfter: Int,

    @ColumnInfo(name = "right_elo_after")
    val rightEloAfter: Int,

    @ColumnInfo(name = "xp_awarded")
    val xpAwarded: Int,

    @ColumnInfo(name = "quest_id")
    val questId: String = "free_play",

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long
)
