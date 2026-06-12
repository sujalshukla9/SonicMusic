package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.LocalSong
import kotlinx.coroutines.flow.Flow

interface LocalMusicRepository {
    fun getLocalSongs(): Flow<List<LocalSong>>
    suspend fun scanDeviceMusic(): Result<List<LocalSong>>
    suspend fun refreshLocalMusic()
}