package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI-generated rule suggestion attached to a [Report].
 * Stub schema for Room compilation. Full schema in M1.
 */
@Entity(tableName = "suggestions")
data class Suggestion(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "report_id")
    val reportId: Long = 0L,

    @ColumnInfo(name = "condition_json")
    val conditionJson: String = "{}",

    @ColumnInfo(name = "action")
    val action: String = "suppress",

    @ColumnInfo(name = "rationale")
    val rationale: String = "",

    /** "pending" | "accepted" | "rejected" */
    @ColumnInfo(name = "status")
    val status: String = "pending",

    @ColumnInfo(name = "user_comment")
    val userComment: String? = null
)
