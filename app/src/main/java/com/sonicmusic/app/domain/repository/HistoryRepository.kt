package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    suspend fun getRecentlyPlayed(limit: Int): Result<List<Song>>
    suspend fun recordPlayback(songId: String)
}
