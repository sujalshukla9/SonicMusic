package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.ArtistPlayCount
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: PlaybackHistoryDao,
    private val settingsDataStore: SettingsDataStore
) : HistoryRepository {
    companion object {
        private const val MAX_HISTORY_ROWS = 1000
    }

    override fun getRecentlyPlayed(limit: Int): Flow<List<PlaybackHistory>> {
        return historyDao.getRecentlyPlayed(limit).map { entities ->
            entities.take(limit).map { it.toPlaybackHistory() }
        }
    }

    override fun getRecentlyPlayedSongs(limit: Int): Flow<List<PlaybackHistory>> {
        return historyDao.getRecentlyPlayedUniqueSongs(limit).map { entities ->
            entities.take(limit).map { it.toPlaybackHistory() }
        }
    }

    override fun getAllArtists(): Flow<List<ArtistPlayCount>> {
        return historyDao.getAllArtists()
    }

    override fun getSongsByArtist(artist: String): Flow<List<PlaybackHistory>> {
        return historyDao.getSongsByArtist(artist).map { entities ->
            entities.map { it.toPlaybackHistory() }
        }
    }

    override suspend fun recordPlayback(song: Song, playDuration: Int, completed: Boolean, totalDuration: Int) {
        if (settingsDataStore.pauseHistory.first()) {
            return
        }

        val entity = PlaybackHistoryEntity(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            playedAt = System.currentTimeMillis(),
            playDuration = playDuration,
            totalDuration = totalDuration,
            completed = completed
        )
        historyDao.insertPlayback(entity)
        historyDao.pruneOldHistory(MAX_HISTORY_ROWS)
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
