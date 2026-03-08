package ai.talkingrock.lithium.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI-generated digest report (M3/M4).
 * Stub schema for Room compilation. Full schema in M1.
 */
@Entity(tableName = "reports")
data class Report(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "generated_at_ms")
    val generatedAtMs: Long = 0L,

    @ColumnInfo(name = "summary_json")
    val summaryJson: String = "{}",

    @ColumnInfo(name = "reviewed")
    val reviewed: Boolean = false
)
