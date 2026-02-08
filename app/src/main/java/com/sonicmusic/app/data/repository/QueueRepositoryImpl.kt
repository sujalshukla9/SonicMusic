package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.QueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue Repository Implementation
 * 
 * Manages song recommendations for infinite queue functionality.
 * 
 * Recommendation Strategies:
 * 1. Song mix - based on current song's artist
 * 2. Artist songs - more songs from same artist
 * 3. Generic recommendations - trending/popular music
 * 
 * Prevents duplicates using a set of already-queued song IDs.
 */
@Singleton
class QueueRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService
) : QueueRepository {

    companion object {
        private const val TAG = "QueueRepository"
        private const val MIN_QUEUE_SIZE = 3
        private const val RECOMMENDATION_BATCH_SIZE = 15
    }

    // State flows for queue management
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _infiniteModeEnabled = MutableStateFlow(true)
    override val infiniteModeEnabled: StateFlow<Boolean> = _infiniteModeEnabled.asStateFlow()

    // Track queued song IDs to prevent duplicates
    private val queuedSongIds = mutableSetOf<String>()
    
    // Vary recommendation queries
    private var lastQueryType = 0

    // ═══════════════════════════════════════════════════════════════
    // RECOMMENDATION FETCHING
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getRelatedSongs(songId: String, limit: Int): Result<List<Song>> {
        Log.d(TAG, "🎵 Getting related songs for: $songId")
        
        return try {
            // Try multiple strategies in sequence
            var songs = listOf<Song>()
            
            // Strategy 1: Song mix based on current song
            if (songs.isEmpty()) {
                songs = fetchSongMix(songId, limit)
            }
            
            // Strategy 2: Artist songs
            if (songs.isEmpty()) {
                songs = fetchArtistSongs(songId, limit)
            }
            
            // Strategy 3: Generic recommendations
            if (songs.isEmpty()) {
                songs = fetchGenericRecommendations(limit)
            }
            
            // Filter out duplicates
            val newSongs = songs.filter { 
                !queuedSongIds.contains(it.id) && it.id != songId 
            }.take(limit)
            
            Log.d(TAG, "✅ Found ${newSongs.size} unique recommendations")
            Result.success(newSongs)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get related songs", e)
            Result.failure(e)
        }
    }

    override suspend fun getSongMix(songId: String, limit: Int): Result<List<Song>> {
        return try {
            val songs = fetchSongMix(songId, limit)
            val filtered = songs.filter { !queuedSongIds.contains(it.id) }
            Result.success(filtered)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Strategy 1: Get mix based on current song's artist
     */
    private suspend fun fetchSongMix(songId: String, limit: Int): List<Song> {
        // Get song details to know the artist
        val songDetails = try {
            youTubeiService.getSongDetails(songId).getOrNull()
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Could not get song details")
            null
        }
        
        if (songDetails == null) return emptyList()
        
        // Search for similar music
        val queries = listOf(
            "${songDetails.artist} mix",
            "${songDetails.artist} top songs",
            "${songDetails.title} similar",
            "songs like ${songDetails.artist}"
        )
        
        // Rotate through query types
        val queryIndex = lastQueryType % queries.size
        lastQueryType++
        val query = queries[queryIndex]
        
        Log.d(TAG, "🔍 Searching: $query")
        
        return try {
            youTubeiService.searchSongs(query, limit * 2)
                .getOrNull()
                ?.filter { it.id != songId }
                ?.take(limit)
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Song mix search failed", e)
            emptyList()
        }
    }

    /**
     * Strategy 2: Get more songs from same artist
     */
    private suspend fun fetchArtistSongs(songId: String, limit: Int): List<Song> {
        val songDetails = try {
            youTubeiService.getSongDetails(songId).getOrNull()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        
        val query = "${songDetails.artist} songs"
        Log.d(TAG, "🔍 Artist search: $query")
        
        return try {
            youTubeiService.searchSongs(query, limit * 2)
                .getOrNull()
                ?.filter { it.id != songId }
                ?.take(limit)
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Artist search failed", e)
            emptyList()
        }
    }

    /**
     * Strategy 3: Generic trending/popular recommendations
     */
    private suspend fun fetchGenericRecommendations(limit: Int): List<Song> {
        val queries = listOf(
            "top songs 2024 India",
            "trending Hindi songs",
            "popular Bollywood music",
            "viral songs India 2024",
            "best Indian music 2024"
        )
        
        val query = queries.random()
        Log.d(TAG, "🔍 Generic search: $query")
        
        return try {
            youTubeiService.searchSongs(query, limit * 2)
                .getOrNull()
                ?.take(limit)
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Generic search failed", e)
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // QUEUE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    override suspend fun addToQueue(songs: List<Song>) {
        val newSongs = songs.filter { !queuedSongIds.contains(it.id) }
        newSongs.forEach { queuedSongIds.add(it.id) }
        _queue.value = _queue.value + newSongs
        Log.d(TAG, "➕ Added ${newSongs.size} songs. Queue size: ${_queue.value.size}")
    }

    override suspend fun clearQueue() {
        _queue.value = emptyList()
        _currentIndex.value = 0
        queuedSongIds.clear()
        lastQueryType = 0
        Log.d(TAG, "🗑️ Queue cleared")
    }

    override suspend fun setInfiniteMode(enabled: Boolean) {
        _infiniteModeEnabled.value = enabled
        Log.d(TAG, "♾️ Infinite mode: $enabled")
    }

    override suspend fun ensureQueueNotEmpty(currentSongId: String): Boolean {
        if (!_infiniteModeEnabled.value) {
            Log.d(TAG, "⏸️ Infinite mode disabled")
            return false
        }

        val queue = _queue.value
        val index = _currentIndex.value
        val upcoming = if (index >= 0 && index < queue.size) {
            queue.size - index - 1
        } else {
            0
        }

        Log.d(TAG, "📊 Queue: $upcoming upcoming songs")

        if (upcoming < MIN_QUEUE_SIZE) {
            Log.d(TAG, "🔄 Fetching more songs...")
            
            val result = getRelatedSongs(currentSongId, RECOMMENDATION_BATCH_SIZE)
            
            result.onSuccess { songs ->
                if (songs.isNotEmpty()) {
                    addToQueue(songs)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Update current playing index
     */
    fun updateCurrentIndex(index: Int) {
        _currentIndex.value = index
    }

    /**
     * Mark a song as queued (prevent duplicates)
     */
    fun markSongAsQueued(songId: String) {
        queuedSongIds.add(songId)
    }

    /**
     * Check if a song is already queued
     */
    fun isSongQueued(songId: String): Boolean {
        return queuedSongIds.contains(songId)
    }
}
