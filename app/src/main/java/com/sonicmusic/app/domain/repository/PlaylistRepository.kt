package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun getAllPlaylists(): Flow<List<Playlist>>
    suspend fun getPlaylistById(playlistId: Long): Result<Playlist>
    suspend fun createPlaylist(name: String, description: String? = null): Result<Long>
    suspend fun addSongToPlaylist(playlistId: Long, songId: String): Result<Unit>
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit>
    suspend fun deletePlaylist(playlistId: Long): Result<Unit>
    suspend fun updatePlaylistOrder(playlistId: Long, songIds: List<String>): Result<Unit>
    suspend fun updatePlaylist(playlistId: Long, name: String, description: String?): Result<Unit>
}