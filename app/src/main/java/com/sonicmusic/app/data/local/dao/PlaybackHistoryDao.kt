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
    
    // ═══════════════════════════════════════════
    // TASTE ANALYSIS QUERIES
    // ═══════════════════════════════════════════
    
    /**
     * Get top artists by play count
     */
    @Query("""
        SELECT artist, COUNT(*) as playCount 
        FROM playback_history 
        GROUP BY artist 
        ORDER BY playCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopArtistsByPlayCount(limit: Int = 10): List<ArtistPlayCount>
    
    /**
     * Get playback count by hour of day (0-23)
     */
    @Query("""
        SELECT 
            CAST((playedAt / 1000 / 3600) % 24 AS INTEGER) as hour,
            COUNT(*) as count
        FROM playback_history
        GROUP BY hour
        ORDER BY count DESC
    """)
    suspend fun getPlaybackByHour(): List<HourlyPlayback>
    
    /**
     * Get completion rate (songs completed vs total plays)
     */
    @Query("""
        SELECT 
            SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as completedCount,
            COUNT(*) as totalCount
        FROM playback_history
    """)
    suspend fun getCompletionStats(): CompletionStats
    
    /**
     * Get average play duration
     */
    @Query("SELECT AVG(playDuration) FROM playback_history WHERE playDuration > 0")
    suspend fun getAveragePlayDuration(): Long?
    
    /**
     * Get most played songs
     */
    @Query("""
        SELECT songId, title, artist, thumbnailUrl, COUNT(*) as playCount
        FROM playback_history
        GROUP BY songId
        ORDER BY playCount DESC
        LIMIT :limit
    """)
    suspend fun getMostPlayedSongs(limit: Int = 20): List<SongPlayStats>
}

// Data classes for query results
data class ArtistPlayCount(
    val artist: String,
    val playCount: Int
)

data class HourlyPlayback(
    val hour: Int,
    val count: Int
)

data class CompletionStats(
    val completedCount: Int,
    val totalCount: Int
)

data class SongPlayStats(
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val playCount: Int
)