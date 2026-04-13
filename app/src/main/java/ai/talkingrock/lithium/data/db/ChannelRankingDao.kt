package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.ChannelRanking
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelRankingDao {

    /** Upsert a channel ranking row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ranking: ChannelRanking)

    @Query("SELECT * FROM channel_rankings WHERE package_name = :pkg AND channel_id = :channelId")
    suspend fun get(pkg: String, channelId: String): ChannelRanking?

    /** All channel rankings ordered by score descending. Suitable for UI observation. */
    @Query("SELECT * FROM channel_rankings ORDER BY elo_score DESC")
    fun observe(): Flow<List<ChannelRanking>>
}
