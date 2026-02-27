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

    @Query(
        """
        SELECT ph.*
        FROM playback_history ph
        INNER JOIN (
            SELECT songId, MAX(playedAt) AS lastPlayedAt
            FROM playback_history
            GROUP BY songId
        ) latest
            ON ph.songId = latest.songId
            AND ph.playedAt = latest.lastPlayedAt
        ORDER BY ph.playedAt DESC
        LIMIT :limit
        """
    )
    fun getRecentlyPlayedUniqueSongs(limit: Int): Flow<List<PlaybackHistoryEntity>>

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
    
    @Query("""
        SELECT * FROM playback_history 
        WHERE artist = :artist COLLATE NOCASE
        GROUP BY songId 
        ORDER BY playedAt DESC
    """)
    fun getSongsByArtist(artist: String): Flow<List<PlaybackHistoryEntity>>
    
    // ═══════════════════════════════════════════
    // TASTE ANALYSIS QUERIES
    // ═══════════════════════════════════════════
    
    /**
     * Get top artists by play count
     */
    @Query("""
        SELECT 
            artist, 
            COUNT(*) as playCount,
            MAX(thumbnailUrl) as thumbnailUrl
        FROM playback_history 
        GROUP BY artist 
        ORDER BY playCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopArtistsByPlayCount(limit: Int = 10): List<ArtistPlayCount>
    
    /**
     * Get all distinct artists with play counts
     */
    @Query("""
        SELECT 
            artist, 
            COUNT(*) as playCount,
            MAX(thumbnailUrl) as thumbnailUrl
        FROM playback_history 
        GROUP BY artist 
        ORDER BY playCount DESC
    """)
    fun getAllArtists(): Flow<List<ArtistPlayCount>>
    
    /**
     * Get playback count by hour of day (0-23)
     */
    @Query("""
        SELECT ((playedAt / 1000 + :utcOffsetSeconds) / 3600) % 24 as hour, COUNT(*) as count
        FROM playback_history
        GROUP BY hour
        ORDER BY hour
    """)
    fun getPlaybackByHour(utcOffsetSeconds: Int): Flow<List<HourlyPlayback>>
    
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

    /**
     * Get songs with strong replay signal and their last play timestamp.
     * Used for rediscovery / forgotten-favorites ranking.
     */
    @Query("""
        SELECT
            songId,
            MAX(title) as title,
            MAX(artist) as artist,
            MAX(thumbnailUrl) as thumbnailUrl,
            COUNT(*) as playCount,
            MAX(playedAt) as lastPlayedAt
        FROM playback_history
        GROUP BY songId
        ORDER BY playCount DESC, lastPlayedAt DESC
        LIMIT :limit
    """)
    suspend fun getRediscoveryCandidates(limit: Int = 100): List<SongRediscoveryStats>

    /**
     * Get skip count — plays where user listened < 30s and didn't complete.
     * Used to compute skip rate for taste profiling.
     */
    @Query("SELECT COUNT(*) FROM playback_history WHERE playDuration > 0 AND playDuration < 30 AND completed = 0")
    suspend fun getSkipCount(): Int

    /**
     * Get total play count for skip rate denominator.
     */
    @Query("SELECT COUNT(*) FROM playback_history WHERE playDuration > 0")
    suspend fun getTotalPlayCount(): Int

    // ═══════════════════════════════════════════
    // QUICK PICKS SUPPORT QUERIES
    // ═══════════════════════════════════════════

    /**
     * Artists that the user skips frequently (≥5 skips, >70% skip rate).
     * Used for Quick Picks anti-preference filtering.
     */
    @Query("""
        SELECT artist FROM playback_history
        WHERE playDuration > 0
        GROUP BY artist
        HAVING COUNT(*) >= 5
          AND SUM(CASE WHEN playDuration < 30 AND completed = 0 THEN 1 ELSE 0 END) * 1.0 / COUNT(*) > 0.7
    """)
    suspend fun getSkippedArtists(): List<String>

    /**
     * All distinct song IDs the user has ever played.
     * Used to filter discovery candidates out of Quick Picks.
     */
    @Query("SELECT DISTINCT songId FROM playback_history")
    suspend fun getAllPlayedSongIds(): List<String>

    // ═══════════════════════════════════════════
    // LISTEN AGAIN SCORING QUERIES
    // ═══════════════════════════════════════════

    /**
     * Returns per-song aggregated stats needed by the Listen Again scoring engine.
     *
     * Columns computed in a single GROUP BY pass:
     * - play counts at 7d / 30d / 90d windows
     * - qualified listen count (duration ≥ max(30s, 50% of total) AND not skipped)
     * - completed count, total plays, skip count (30d)
     * - last played timestamp
     * - time-of-day and day-of-week distributions encoded as
     *   pipe-separated key:value pairs (e.g. "morning:5|afternoon:3")
     *
     * @param since90d epoch-millis threshold for 90-day window
     * @param since30d epoch-millis threshold for 30-day window
     * @param since7d  epoch-millis threshold for 7-day window
     */
    @Query("""
        SELECT
            songId,
            MAX(title)          AS title,
            MAX(artist)         AS artist,
            MAX(thumbnailUrl)   AS thumbnailUrl,
            MAX(playedAt)       AS lastPlayedAt,
            COUNT(*)            AS totalPlays,

            SUM(CASE WHEN playedAt >= :since90d
                      AND playDuration >= MAX(30, totalDuration * 0.5)
                      AND completed = 0  -- not a skip (skip = completed=0 AND short)
                      AND playDuration >= 30
                 THEN 1 ELSE 0 END)                   AS qualifiedListenCount,

            SUM(CASE WHEN playedAt >= :since90d THEN 1 ELSE 0 END) AS playCount90d,
            SUM(CASE WHEN playedAt >= :since30d THEN 1 ELSE 0 END) AS playCount30d,
            SUM(CASE WHEN playedAt >= :since7d  THEN 1 ELSE 0 END) AS playCount7d,

            SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END)         AS completedCount,

            SUM(CASE WHEN playedAt >= :since30d
                      AND playDuration > 0 AND playDuration < 30
                      AND completed = 0
                 THEN 1 ELSE 0 END)                                 AS skipCount30d,

            -- 7-day-prior window: plays between 8d ago and 14d ago
            SUM(CASE WHEN playedAt >= :since7d - (7 * 86400000)
                      AND playedAt < :since7d
                 THEN 1 ELSE 0 END)                                 AS playCount7dPrior,

            -- Time-of-day distribution as pipe-separated pairs (timezone-aware)
            GROUP_CONCAT(
                CASE
                    WHEN ((playedAt + :utcOffsetMs) / 3600000) % 24 < 6  THEN 'night'
                    WHEN ((playedAt + :utcOffsetMs) / 3600000) % 24 < 12 THEN 'morning'
                    WHEN ((playedAt + :utcOffsetMs) / 3600000) % 24 < 17 THEN 'afternoon'
                    ELSE 'evening'
                END, '|'
            ) AS timeOfDayRaw,

            -- Day-of-week distribution as pipe-separated pairs (timezone-aware)
            GROUP_CONCAT(
                CASE (((playedAt + :utcOffsetMs) / 86400000) + 4) % 7
                    WHEN 0 THEN 'mon'
                    WHEN 1 THEN 'tue'
                    WHEN 2 THEN 'wed'
                    WHEN 3 THEN 'thu'
                    WHEN 4 THEN 'fri'
                    WHEN 5 THEN 'sat'
                    WHEN 6 THEN 'sun'
                    ELSE 'mon'
                END, '|'
            ) AS dayOfWeekRaw

        FROM playback_history
        WHERE playedAt >= :since90d
        GROUP BY songId
        HAVING qualifiedListenCount >= 1
        ORDER BY lastPlayedAt DESC
    """)
    suspend fun getListenAgainRawStats(
        since90d: Long,
        since30d: Long,
        since7d: Long,
        utcOffsetMs: Long
    ): List<ListenAgainRawStats>
}

// Data classes for query results
data class ArtistPlayCount(
    val artist: String,
    val playCount: Int,
    val thumbnailUrl: String? = null
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

data class SongRediscoveryStats(
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val playCount: Int,
    val lastPlayedAt: Long
)

/**
 * Raw per-song aggregated stats returned by [PlaybackHistoryDao.getListenAgainRawStats].
 * Time/day distributions are encoded as pipe-separated labels parsed by the repository.
 */
data class ListenAgainRawStats(
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val lastPlayedAt: Long,
    val totalPlays: Int,
    val qualifiedListenCount: Int,
    val playCount90d: Int,
    val playCount30d: Int,
    val playCount7d: Int,
    val completedCount: Int,
    val skipCount30d: Int,
    val playCount7dPrior: Int,
    val timeOfDayRaw: String?,
    val dayOfWeekRaw: String?
)
