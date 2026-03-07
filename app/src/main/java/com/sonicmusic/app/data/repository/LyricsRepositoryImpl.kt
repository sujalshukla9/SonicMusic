package com.sonicmusic.app.data.repository

import android.util.Log
import android.util.LruCache
import com.sonicmusic.app.data.remote.source.LyricsService
import com.sonicmusic.app.domain.model.LyricsResult
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.LyricsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [LyricsRepository] with in-memory LRU cache.
 *
 * Caches up to 20 lyrics results to avoid redundant network calls
 * when the user toggles lyrics for recently played songs.
 */
@Singleton
class LyricsRepositoryImpl @Inject constructor(
    private val lyricsService: LyricsService
) : LyricsRepository {

    companion object {
        private const val TAG = "LyricsRepo"
        private const val CACHE_SIZE = 20
    }

    private val cache = LruCache<String, LyricsResult>(CACHE_SIZE)

    override suspend fun getLyrics(song: Song): LyricsResult {
        val videoId = song.id
        // Check cache first
        cache.get(videoId)?.let { cached ->
            Log.d(TAG, "📋 Lyrics cache hit for: $videoId")
            return cached
        }

        // Fetch from network
        val result = lyricsService.fetchLyrics(song)

        // Cache successful results and NotFound (avoid re-fetching songs without lyrics)
        when (result) {
            is LyricsResult.Found,
            is LyricsResult.FoundSynced,
            is LyricsResult.NotFound -> {
                cache.put(videoId, result)
            }
            is LyricsResult.Error -> {
                // Don't cache errors — allow retry
            }
        }

        return result
    }
}
