package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    suspend fun searchSongs(query: String): Result<List<Song>>
    suspend fun getSong(id: String): Result<Song>
    suspend fun toggleLike(song: Song)
    fun getLikedSongs(): Flow<List<Song>>
    
    // New methods for Home Screen & Playback
    suspend fun getNewReleases(limit: Int): Result<List<Song>>
    suspend fun getTrending(limit: Int): Result<List<Song>>
    suspend fun getEnglishHits(limit: Int): Result<List<Song>>
    suspend fun getStreamUrl(songId: String, quality: Int): Result<String>
}
