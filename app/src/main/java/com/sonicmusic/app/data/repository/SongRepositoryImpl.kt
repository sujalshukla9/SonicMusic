package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRepositoryImpl @Inject constructor(
    private val songDao: SongDao,
    private val youTubeiService: YouTubeiService,
    private val audioStreamExtractor: AudioStreamExtractor
) : SongRepository {
    
    override suspend fun searchSongs(query: String, limit: Int): Result<List<Song>> {
        return youTubeiService.searchSongs(query, limit)
            .onSuccess { songs ->
                // Cache songs to database
                val entities = songs.map { it.toEntity() }
                songDao.insertAll(entities)
            }
    }

    override suspend fun getSearchSuggestions(query: String): Result<List<String>> {
        return youTubeiService.getSearchSuggestions(query)
    }

    override suspend fun getSongById(id: String): Result<Song> {
        // Try to get from database first
        val cachedSong = songDao.getSongById(id)
        if (cachedSong != null) {
            return Result.success(cachedSong.toSong())
        }

        // Fetch from API
        return youTubeiService.getSongDetails(id)
            .onSuccess { song ->
                songDao.insertSong(song.toEntity())
            }
    }

    override suspend fun getStreamUrl(songId: String, quality: StreamQuality): Result<String> {
        // Check if we have a cached stream URL
        val cachedSong = songDao.getSongById(songId)
        if (cachedSong?.cachedStreamUrl != null && 
            cachedSong.cacheExpiry != null && 
            cachedSong.cacheExpiry > System.currentTimeMillis()) {
            return Result.success(cachedSong.cachedStreamUrl)
        }

        // Extract fresh stream URL (URL-only for backward compatibility)
        return audioStreamExtractor.extractAudioStreamUrl(songId, quality)
            .onSuccess { streamUrl ->
                // Cache the URL for 30 minutes (YouTube URLs expire quickly)
                val expiry = System.currentTimeMillis() + (30 * 60 * 1000)
                songDao.updateSong(
                    cachedSong?.copy(
                        cachedStreamUrl = streamUrl,
                        cacheExpiry = expiry,
                    ) ?: SongEntity(
                        id = songId,
                        title = "",
                        artist = "",
                        duration = 0,
                        thumbnailUrl = "",
                        cachedStreamUrl = streamUrl,
                        cacheExpiry = expiry,
                    )
                )
            }
    }

    override suspend fun getStreamWithInfo(
        songId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> {
        return audioStreamExtractor.extractAudioStream(songId, quality)
    }

    override suspend fun getNewReleases(limit: Int): Result<List<Song>> {
        return youTubeiService.getNewReleases(limit)
    }

    override suspend fun getTrending(limit: Int): Result<List<Song>> {
        return youTubeiService.getTrendingSongs(limit)
    }

    override suspend fun getEnglishHits(limit: Int): Result<List<Song>> {
        return youTubeiService.getEnglishHits(limit)
    }

    override suspend fun likeSong(songId: String) {
        songDao.updateLikeStatus(songId, true, System.currentTimeMillis())
    }

    override suspend fun unlikeSong(songId: String) {
        songDao.updateLikeStatus(songId, false, null)
    }

    override fun getLikedSongs(): Flow<List<Song>> {
        return songDao.getLikedSongs().map { entities ->
            entities.map { it.toSong() }
        }
    }

    override suspend fun clearCachedStreamUrl(songId: String) {
        val cachedSong = songDao.getSongById(songId)
        cachedSong?.let {
            songDao.updateSong(it.copy(cachedStreamUrl = null, cacheExpiry = null))
        }
    }

    override suspend fun isLiked(songId: String): Boolean {
        return songDao.isLiked(songId) ?: false
    }

    private fun Song.toEntity(): SongEntity {
        return SongEntity(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            album = album,
            albumId = albumId,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            year = year,
            category = category,
            viewCount = viewCount,
            isLiked = isLiked,
            likedAt = likedAt
        )
    }

    private fun SongEntity.toSong(): Song {
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            album = album,
            albumId = albumId,
            duration = duration,
            // Force high-res thumbnail even if cache has low-res
            thumbnailUrl = if (thumbnailUrl.contains("i.ytimg.com")) {
                "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
            } else {
                thumbnailUrl
            },
            year = year,
            category = category,
            viewCount = viewCount,
            isLiked = isLiked,
            likedAt = likedAt
        )
    }
}