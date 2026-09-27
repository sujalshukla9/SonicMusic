package com.sonicmusic.app.features.library.data

import com.sonicmusic.app.data.local.dao.ArtistPlayCount
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity
import com.sonicmusic.app.features.recommendations.engine.RecommendationEngine
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
    private val settingsDataStore: SettingsDataStore,
    private val recommendationEngine: RecommendationEngine
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

        // Feed playback event into the recommendation engine
        recommendationEngine.onPlaybackCompleted(
            song = song,
            playDurationSec = playDuration,
            totalDurationSec = totalDuration
        )

        // Prune older than retention setting
        val retention = settingsDataStore.historyRetention.first()
        if (retention != "FOREVER") {
            val days = if (retention == "30_DAYS") 30L else 90L
            val threshold = System.currentTimeMillis() - days * 24 * 3600 * 1000L
            historyDao.pruneHistoryOlderThan(threshold)
        }

        // Cap entries at 500
        historyDao.pruneOldHistory(500)
    }

    override suspend fun deleteHistoryEntry(id: Long) {
        historyDao.deleteHistoryEntry(id)
    }

    override suspend fun restoreHistoryEntry(history: PlaybackHistory) {
        val entity = PlaybackHistoryEntity(
            id = history.id,
            songId = history.songId,
            title = history.title,
            artist = history.artist,
            thumbnailUrl = history.thumbnailUrl,
            playedAt = history.playedAt,
            playDuration = history.playDuration,
            completed = history.completed
        )
        historyDao.insertHistoryEntry(entity)
    }

    override suspend fun clearHistory() {
        historyDao.clearAllHistory()
    }

    override suspend fun pruneOldHistory(keepCount: Int) {
        val retention = settingsDataStore.historyRetention.first()
        if (retention != "FOREVER") {
            val days = if (retention == "30_DAYS") 30L else 90L
            val threshold = System.currentTimeMillis() - days * 24 * 3600 * 1000L
            historyDao.pruneHistoryOlderThan(threshold)
        }
        historyDao.pruneOldHistory(keepCount.coerceAtMost(500))
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
