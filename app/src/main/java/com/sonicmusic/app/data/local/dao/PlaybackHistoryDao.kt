package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY playedAt DESC LIMIT :limit")
    fun getRecentlyPlayed(limit: Int): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT DISTINCT songId FROM playback_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecentSongIds(limit: Int): List<String>

    @Insert
    suspend fun insertPlayback(history: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE id NOT IN (SELECT id FROM playback_history ORDER BY playedAt DESC LIMIT :keepCount)")
    suspend fun pruneOldHistory(keepCount: Int)

    @Query("DELETE FROM playback_history")
    suspend fun clearAllHistory()

    @Query("SELECT COUNT(*) FROM playback_history WHERE songId = :songId")
    suspend fun getPlayCount(songId: String): Int

    @Query("SELECT * FROM playback_history WHERE playedAt > :since ORDER BY playedAt DESC")
    suspend fun getMostPlayedSince(since: Long): List<PlaybackHistoryEntity>
}