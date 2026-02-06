package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

// Placeholder model for Playlist if not exists
// import com.sonicmusic.app.domain.model.Playlist

interface PlaylistRepository {
    // defined in PRD
    // fun getAllPlaylists(): Flow<List<Playlist>>
    // suspend fun createPlaylist(name: String, description: String?): Result<Long>
    suspend fun addSongToPlaylist(playlistId: Long, songId: String): Result<Unit>
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit>
}
