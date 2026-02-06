package com.sonicmusic.app.data.repository

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.HistoryRepository
import javax.inject.Inject

class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: com.sonicmusic.app.data.local.dao.PlaybackHistoryDao,
    private val songDao: com.sonicmusic.app.data.local.dao.SongDao
) : HistoryRepository {

    override suspend fun getRecentlyPlayed(limit: Int): Result<List<Song>> {
        return try {
            val entities = historyDao.getRecentlyPlayed(limit)
            val songs = entities.map { it.toDomain() }
            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun com.sonicmusic.app.data.local.entity.SongEntity.toDomain(): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            album = album,
            albumId = albumId,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            isLiked = isLiked,
            streamUrl = cachedStreamUrl
        )
    }
    
    // Actually I should update the interface to suspend.
    // Let's self-correct: Update interface first.
    
    override suspend fun recordPlayback(songId: String) {
        historyDao.insert(
             com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity(
                 songId = songId,
                 playedAt = System.currentTimeMillis()
             )
        )
    }
}
