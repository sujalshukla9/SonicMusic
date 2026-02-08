package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity
import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: PlaybackHistoryDao
) : HistoryRepository {

    override fun getRecentlyPlayed(limit: Int): Flow<List<PlaybackHistory>> {
        return historyDao.getRecentlyPlayed(limit).map { entities ->
            entities.map { it.toPlaybackHistory() }
        }
    }

    override fun getRecentlyPlayedSongs(limit: Int): Flow<List<PlaybackHistory>> {
        return historyDao.getRecentlyPlayed(limit).map { entities ->
            entities.distinctBy { it.songId }.take(limit).map { it.toPlaybackHistory() }
        }
    }

    override suspend fun recordPlayback(songId: String, playDuration: Int, completed: Boolean) {
        // TODO: Fetch song details from database or API
        val entity = PlaybackHistoryEntity(
            songId = songId,
            title = "", // TODO: Get from SongRepository
            artist = "",
            thumbnailUrl = "",
            playedAt = System.currentTimeMillis(),
            playDuration = playDuration,
            completed = completed
        )
        historyDao.insertPlayback(entity)
        historyDao.pruneOldHistory(100)
    }

    override suspend fun clearHistory() {
        historyDao.clearAllHistory()
    }

    override suspend fun pruneOldHistory(keepCount: Int) {
        historyDao.pruneOldHistory(keepCount)
    }

    private fun PlaybackHistoryEntity.toPlaybackHistory(): PlaybackHistory {
        return PlaybackHistory(
            id = id,
            songId = songId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            playedAt = playedAt,
            playDuration = playDuration,
            completed = completed
        )
    }
}