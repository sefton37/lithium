package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ai.talkingrock.lithium.data.model.TrainingJudgment
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingJudgmentDao {

    @Insert
    suspend fun insert(judgment: TrainingJudgment): Long

    @Query("SELECT COUNT(*) FROM training_judgments")
    fun countFlow(): Flow<Int>

    /**
     * Returns notification IDs that have already appeared on either side of a
     * judgment (including 'skip'). Used to avoid re-surfacing the same row.
     */
    @Query(
        "SELECT left_notification_id FROM training_judgments " +
        "UNION " +
        "SELECT right_notification_id FROM training_judgments"
    )
    suspend fun getJudgedNotificationIds(): List<Long>

    /** Distribution of choice values — used for future dashboards. */
    @Query("SELECT choice, COUNT(*) AS count FROM training_judgments GROUP BY choice")
    suspend fun getChoiceBreakdown(): List<ChoiceCount>
}

data class ChoiceCount(val choice: String, val count: Int)
