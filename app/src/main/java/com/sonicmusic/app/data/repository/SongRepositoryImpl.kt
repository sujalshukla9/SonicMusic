package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.downloadmanager.SongDownloadManager
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.data.remote.source.AudioEnhancementService
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.AudioEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.data.mapper.toSong
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRepositoryImpl @Inject constructor(
    private val songDao: SongDao,
    private val youTubeiService: YouTubeiService,
    private val audioStreamExtractor: AudioStreamExtractor,
    private val audioEnhancementService: AudioEnhancementService,
    private val songDownloadManager: SongDownloadManager,
    private val audioEngine: AudioEngine,
) : SongRepository {
    
    companion object {
        private const val TAG = "SongRepo"
    }

    // Keep quality awareness for in-memory URL cache validity.
    private val streamQualityCache = ConcurrentHashMap<String, StreamQuality>()
    
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
        // Prefer offline file when the song is downloaded.
        songDownloadManager.getPlaybackFile(songId).getOrNull()?.let { localFile ->
            val localUri = localFile.toURI().toString()
            Log.d(TAG, "📦 Using offline file for song=$songId")
            return Result.success(localUri)
        }

        val effectiveQuality = resolveQuality(quality)

        // Check if we have a cached stream URL
        val cachedSong = songDao.getSongById(songId)
        if (cachedSong?.cachedStreamUrl != null &&
            cachedSong.cacheExpiry != null && 
            cachedSong.cacheExpiry > System.currentTimeMillis() &&
            streamQualityCache[songId] == effectiveQuality) {
            return Result.success(cachedSong.cachedStreamUrl)
        }

        // Extract fresh stream URL (URL-only for backward compatibility)
        return audioStreamExtractor.extractAudioStreamUrl(songId, effectiveQuality)
            .map { streamUrl ->
                // Route through FFmpeg backend if enhanced audio is enabled
                val finalUrl = if (audioEngine.isEnhancedAudioEnabled()) {
                    enhanceStreamUrl(songId, streamUrl)
                } else {
                    streamUrl
                }

                streamQualityCache[songId] = effectiveQuality
                Log.d(
                    TAG,
                    "🎚️ Stream quality requested=${quality.name}, effective=${effectiveQuality.name}, song=$songId"
                )
                
                // Cache the URL for 30 minutes (YouTube URLs expire quickly)
                val expiry = System.currentTimeMillis() + (30 * 60 * 1000)
                songDao.updateSong(
                    cachedSong?.copy(
                        cachedStreamUrl = finalUrl,
                        cacheExpiry = expiry,
                    ) ?: SongEntity(
                        id = songId,
                        title = "",
                        artist = "",
                        duration = 0,
                        thumbnailUrl = "",
                        cachedStreamUrl = finalUrl,
                        cacheExpiry = expiry,
                    )
                )
                
                finalUrl
            }
    }
    
    /**
     * Route stream URL through FFmpeg backend for Opus → M4A ALAC transcoding.
     * Falls back to original URL if backend is unavailable.
     */
    private suspend fun enhanceStreamUrl(songId: String, originalUrl: String): String {
        return audioEnhancementService.enhanceStream(songId, originalUrl)
            .map { enhanced ->
                Log.d(TAG, "🔊 Enhanced stream: ${enhanced.codec} ${enhanced.bitrate}kbps")
                enhanced.enhancedUrl
            }
            .getOrElse { 
                Log.w(TAG, "⚠️ Enhancement failed, using original URL")
                originalUrl 
            }
    }

    override suspend fun getStreamWithInfo(
        songId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> {
        val effectiveQuality = resolveQuality(quality)
        return audioStreamExtractor.extractAudioStream(songId, effectiveQuality)
            .onSuccess { (_, info) ->
                streamQualityCache[songId] = effectiveQuality
                audioEngine.updateStreamInfo(info)
            }
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

    override suspend fun likeSong(song: Song) {
        val existingSong = songDao.getSongById(song.id)
        if (existingSong == null) {
            // Song doesn't exist, insert it with isLiked = true
            songDao.insertSong(song.toEntity().copy(isLiked = true, likedAt = System.currentTimeMillis()))
        } else {
            // Song exists, just update like status
            songDao.updateLikeStatus(song.id, true, System.currentTimeMillis())
        }
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
        streamQualityCache.remove(songId)
    }

    override suspend fun cacheSong(song: Song) {
        val existing = songDao.getSongById(song.id)
        val entity = song.toEntity().let { base ->
            if (existing != null) {
                base.copy(
                    isLiked = existing.isLiked,
                    likedAt = existing.likedAt,
                    cachedStreamUrl = existing.cachedStreamUrl,
                    cacheExpiry = existing.cacheExpiry
                )
            } else {
                base
            }
        }
        songDao.insertSong(entity)
    }

    override suspend fun isLiked(songId: String): Boolean {
        return songDao.isLiked(songId) ?: false
    }

    private fun resolveQuality(requested: StreamQuality): StreamQuality {
        // Most callers pass BEST as a playback default. Map it to network-aware user setting.
        return if (requested == StreamQuality.BEST) {
            audioEngine.getOptimalQuality()
        } else {
            requested
        }
    }
}
