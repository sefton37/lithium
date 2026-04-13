package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.ScoreQuantiles

@Dao
interface ScoreQuantilesDao {

    /** Returns the singleton quantile row, or null if not yet computed. */
    @Query("SELECT * FROM score_quantiles WHERE id = 0")
    suspend fun get(): ScoreQuantiles?

    /** Upsert the singleton quantile row (always id=0). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(q: ScoreQuantiles)
}
