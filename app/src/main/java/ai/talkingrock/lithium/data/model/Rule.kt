package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User-approved filtering or routing rule.
 *
 * condition_json stores a serialized [RuleCondition] sealed class.
 * Deserialization happens at the repository layer, not here.
 * Full schema in M1.
 */
@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "condition_json")
    val conditionJson: String = "{}",

    @ColumnInfo(name = "action")
    val action: String = "suppress",

    /** "pending_review" | "approved" | "rejected" */
    @ColumnInfo(name = "status")
    val status: String = "pending_review",

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = 0L,

    @ColumnInfo(name = "source")
    val source: String = "user"
)
