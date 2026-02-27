package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.data.local.dao.ArtistPlayCount
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun getRecentlyPlayed(limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getRecentlyPlayedSongs(limit: Int = 15): Flow<List<PlaybackHistory>>
    fun getAllArtists(): Flow<List<ArtistPlayCount>>
    fun getSongsByArtist(artist: String): Flow<List<PlaybackHistory>>
    suspend fun recordPlayback(song: Song, playDuration: Int = 0, completed: Boolean = false, totalDuration: Int = 0)
    suspend fun clearHistory()
    suspend fun pruneOldHistory(keepCount: Int = 100)
}