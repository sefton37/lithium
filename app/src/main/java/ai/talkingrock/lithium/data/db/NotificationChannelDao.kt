package ai.talkingrock.lithium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.talkingrock.lithium.data.model.NotificationChannel

@Dao
interface NotificationChannelDao {

    /** Insert or replace the channel cache entry for this (packageName, channelId) pair. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: NotificationChannel)

    /**
     * Returns the cached display name for a channel, or null if not yet seen.
     * Used by TrainingViewModel when building a ChannelPair challenge.
     */
    @Query(
        "SELECT display_name FROM notification_channels " +
        "WHERE package_name = :packageName AND channel_id = :channelId " +
        "LIMIT 1"
    )
    suspend fun getDisplayName(packageName: String, channelId: String): String?
}
