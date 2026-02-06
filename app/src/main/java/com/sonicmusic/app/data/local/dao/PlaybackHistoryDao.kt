package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity
import com.sonicmusic.app.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlaybackHistoryEntity)

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playback_history h ON s.id = h.songId
        ORDER BY h.playedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyPlayed(limit: Int): List<SongEntity>
}
