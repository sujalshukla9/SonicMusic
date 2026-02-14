package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    suspend fun searchSongs(query: String, limit: Int = 50): Result<List<Song>>
    suspend fun getSearchSuggestions(query: String): Result<List<String>>
    suspend fun getSongById(id: String): Result<Song>
    suspend fun getStreamUrl(songId: String, quality: StreamQuality): Result<String>
    suspend fun getStreamWithInfo(songId: String, quality: StreamQuality): Result<Pair<String, AudioStreamInfo>>
    suspend fun getNewReleases(limit: Int = 25): Result<List<Song>>
    suspend fun getTrending(limit: Int = 30): Result<List<Song>>
    suspend fun getEnglishHits(limit: Int = 25): Result<List<Song>>
    
    // URL cache management
    suspend fun clearCachedStreamUrl(songId: String)
    
    suspend fun cacheSong(song: Song)
    suspend fun likeSong(song: Song)
    suspend fun unlikeSong(songId: String)
    fun getLikedSongs(): Flow<List<Song>>
    suspend fun isLiked(songId: String): Boolean
}
