package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun getRecentlyPlayed(limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getRecentlyPlayedSongs(limit: Int = 15): Flow<List<PlaybackHistory>>
    suspend fun recordPlayback(song: Song, playDuration: Int = 0, completed: Boolean = false)
    suspend fun clearHistory()
    suspend fun pruneOldHistory(keepCount: Int = 100)
}