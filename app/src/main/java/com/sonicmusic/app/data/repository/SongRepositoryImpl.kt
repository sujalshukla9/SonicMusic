package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.data.remote.api.YouTubeiService
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.sonicmusic.app.data.manager.CacheManager

class SongRepositoryImpl @Inject constructor(
    private val api: YouTubeiService,
    private val dao: SongDao,
    private val cacheManager: CacheManager
) : SongRepository {

    override suspend fun searchSongs(query: String): Result<List<Song>> {
        return api.searchSongs(query)
    }

    override suspend fun getSong(id: String): Result<Song> {
        val localSong = dao.getSong(id)
        if (localSong != null) {
            return Result.success(localSong.toDomain())
        }
        
        return api.getStreamUrl(id).map { url ->
             Song(
                id = id,
                title = "Unknown",
                artist = "Unknown",
                artistId = null,
                album = null,
                albumId = null,
                duration = 0,
                thumbnailUrl = "",
                streamUrl = url
            )
        }
    }

    override suspend fun toggleLike(song: Song) {
        val current = dao.getSong(song.id)
        val isLiked = !(current?.isLiked ?: false)
        val timestamp = if (isLiked) System.currentTimeMillis() else null
        
        if (current == null) {
            dao.insertSong(song.toEntity().copy(isLiked = isLiked, likedAt = timestamp))
        } else {
            dao.updateLikeStatus(song.id, isLiked, timestamp)
        }
    }

    override fun getLikedSongs(): Flow<List<Song>> {
        return dao.getLikedSongs().map { entities ->
            entities.map { it.toDomain() }
        }
    }
    
    override suspend fun getNewReleases(limit: Int): Result<List<Song>> {
        // Placeholder: Use search API with specific query
        return api.searchSongs("New Music Release")
    }

    override suspend fun getTrending(limit: Int): Result<List<Song>> {
         return api.searchSongs("Trending Songs")
    }

    override suspend fun getEnglishHits(limit: Int): Result<List<Song>> {
         return api.searchSongs("Top English Songs")
    }

    override suspend fun getStreamUrl(songId: String, quality: Int): Result<String> {
        val cached = cacheManager.getCachedStreamUrl(songId, quality)
        if (cached != null) return Result.success(cached)
        
        return api.getStreamUrl(songId).onSuccess { url ->
            cacheManager.cacheStreamUrl(songId, url, quality, 6)
        }
    }

    private fun SongEntity.toDomain(): Song {
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
            year = null,
            category = "Music",
            viewCount = null,
            isLiked = isLiked,
            cachedStreamUrl = streamUrl
        )
    }
}
