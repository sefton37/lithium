package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A human judgment comparing two notifications. Emitted by the Training tab.
 *
 * Scoring snapshot: the tier/classification/confidence of each side is captured
 * at judgment time so subsequent reclassification of the source rows does not
 * retroactively change the training signal.
 *
 * [choice] values:
 *   - "left"  — user said the left notification is more important
 *   - "right" — user said the right notification is more important
 *   - "tie"   — user said they're equally important
 *   - "skip"  — user couldn't judge; exclude from training but keep to avoid re-surfacing
 */
@Entity(tableName = "training_judgments")
data class TrainingJudgment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "left_notification_id")
    val leftNotificationId: Long,

    @ColumnInfo(name = "right_notification_id")
    val rightNotificationId: Long,

    @ColumnInfo(name = "choice")
    val choice: String,

    @ColumnInfo(name = "left_tier")
    val leftTier: Int,

    @ColumnInfo(name = "right_tier")
    val rightTier: Int,

    @ColumnInfo(name = "left_tier_reason")
    val leftTierReason: String?,

    @ColumnInfo(name = "right_tier_reason")
    val rightTierReason: String?,

    @ColumnInfo(name = "left_ai_classification")
    val leftAiClassification: String?,

    @ColumnInfo(name = "right_ai_classification")
    val rightAiClassification: String?,

    @ColumnInfo(name = "left_confidence")
    val leftConfidence: Float?,

    @ColumnInfo(name = "right_confidence")
    val rightConfidence: Float?,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** XP awarded for this judgment (0 for skip; computed at submit time). */
    @ColumnInfo(name = "xp_awarded")
    val xpAwarded: Int = 0,

    /** True if this judgment closed a set of [SET_SIZE] pairs. Used for UI history. */
    @ColumnInfo(name = "set_complete")
    val setComplete: Boolean = false,

    /** Bonus XP from set completion (0 unless [setComplete] is true). */
    @ColumnInfo(name = "set_bonus_xp")
    val setBonusXp: Int = 0
)
