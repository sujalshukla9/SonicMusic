package com.sonicmusic.app.data.repository

import com.sonicmusic.app.domain.repository.PlaylistRepository
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor() : PlaylistRepository {
    override suspend fun addSongToPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return Result.success(Unit)
    }
}
