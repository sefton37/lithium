package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A weak pairwise preference signal derived from real notification-shade behavior.
 *
 * Rows are written by [ai.talkingrock.lithium.service.LithiumNotificationListener] in
 * response to user taps ([kind] = TAP_OVER_PEER) and explicit dismissals
 * ([kind] = PEER_OVER_DISMISSED). DWELL is reserved for future use.
 *
 * Package/channel columns are denormalized at capture time because the source
 * rows in the notifications table are hard-deleted by retention cleanup. There is
 * intentionally no FOREIGN KEY to notifications — the notification IDs are retained
 * for debugging only and will dangle after retention runs.
 *
 * [winnerAiClass] / [winnerAiConf] / [loserAiClass] / [loserAiConf] are nullable —
 * AI classification runs asynchronously via AiAnalysisWorker and may not be available
 * at capture time. Rows with null classification still contribute to Elo replay but
 * are excluded from the category weight fit.
 *
 * [screenWasOn] is stored as Boolean (Room maps to INTEGER 0/1). Off-screen rows are
 * captured but excluded from both Elo replay and category weight fitting.
 *
 * Schema version: 12
 */
@Entity(tableName = "implicit_judgments")
data class ImplicitJudgment(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** TAP_OVER_PEER, PEER_OVER_DISMISSED, or DWELL (reserved). */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "winner_notification_id")
    val winnerNotificationId: Long,

    @ColumnInfo(name = "loser_notification_id")
    val loserNotificationId: Long,

    @ColumnInfo(name = "winner_package")
    val winnerPackage: String,

    @ColumnInfo(name = "winner_channel_id")
    val winnerChannelId: String,

    @ColumnInfo(name = "loser_package")
    val loserPackage: String,

    @ColumnInfo(name = "loser_channel_id")
    val loserChannelId: String,

    /** 0-based shade position of the winner (lower = higher in shade). */
    @ColumnInfo(name = "winner_rank")
    val winnerRank: Int,

    /** 0-based shade position of the loser (lower = higher in shade). */
    @ColumnInfo(name = "loser_rank")
    val loserRank: Int,

    /** AI classification label for the winner at capture time; null if not yet classified. */
    @ColumnInfo(name = "winner_ai_class")
    val winnerAiClass: String?,

    /** AI confidence for the winner at capture time; null if not yet classified. */
    @ColumnInfo(name = "winner_ai_conf")
    val winnerAiConf: Float?,

    /** AI classification label for the loser at capture time; null if not yet classified. */
    @ColumnInfo(name = "loser_ai_class")
    val loserAiClass: String?,

    /** AI confidence for the loser at capture time; null if not yet classified. */
    @ColumnInfo(name = "loser_ai_conf")
    val loserAiConf: Float?,

    /** Number of notifications in the shade at capture time (after filtering). */
    @ColumnInfo(name = "cohort_size")
    val cohortSize: Int,

    /** True if the screen was interactive at capture time. Off-screen rows are excluded from refit. */
    @ColumnInfo(name = "screen_was_on")
    val screenWasOn: Boolean,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)
