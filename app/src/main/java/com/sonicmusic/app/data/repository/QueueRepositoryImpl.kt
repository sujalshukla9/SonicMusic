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
 * Queue Repository Implementation - ViTune Style
 * 
 * Manages song recommendations for infinite queue functionality.
 * Key improvements:
 * - Better duplicate prevention with proper sync
 * - ViTune-style instant recommendations
 * - Smart queue management
 * - Multiple recommendation strategies
 * 
 * Recommendation Strategies (in priority order):
 * 1. YouTube Music "Up Next" API (Fastest & Best Quality)
 * 2. Song mix based on current artist
 * 3. More songs from same artist
 * 4. Generic trending recommendations
 */
@Singleton
class QueueRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService
) : QueueRepository {

    companion object {
        private const val TAG = "QueueRepository"
        private const val MIN_QUEUE_SIZE = 3
        private const val RECOMMENDATION_BATCH_SIZE = 15
        private const val MAX_HISTORY_SIZE = 100 // Prevent memory bloat
        private const val RECOMMENDATION_DEBOUNCE_MS = 5000L // Minimum 5s between fetches
    }

    // State flows for queue management
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _infiniteModeEnabled = MutableStateFlow(true)
    override val infiniteModeEnabled: StateFlow<Boolean> = _infiniteModeEnabled.asStateFlow()

    // Track queued song IDs to prevent duplicates (ViTune-style)
    // Using LinkedHashSet to maintain insertion order and efficient lookups
    private val queuedSongIds = LinkedHashSet<String>()
    
    // Track played songs to avoid recommending them again
    private val playedSongIds = LinkedHashSet<String>()
    
    // Vary recommendation queries for variety
    private var lastQueryType = 0
    private var lastRecommendationSongId: String? = null
    
    // Prevent duplicate recommendation fetches
    private var isFetchingRecommendations = false
    private var lastFetchTime = 0L

    // ═══════════════════════════════════════════════════════════════
    // RECOMMENDATION FETCHING - ViTune Style
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getRelatedSongs(songId: String, limit: Int): Result<List<Song>> {
        // Debounce: prevent rapid recommendation fetches
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < RECOMMENDATION_DEBOUNCE_MS) {
            Log.d(TAG, "⚠️ Debouncing recommendation fetch (too soon)")
            return Result.success(emptyList())
        }
        
        // Prevent duplicate fetches for the same song
        if (isFetchingRecommendations || songId == lastRecommendationSongId) {
            Log.d(TAG, "⚠️ Skipping duplicate recommendation fetch for: $songId")
            return Result.success(emptyList())
        }
        
        isFetchingRecommendations = true
        lastRecommendationSongId = songId
        lastFetchTime = now
        
        Log.d(TAG, "🎵 Getting related songs for: $songId")
        
        return try {
            // Priority 1: Get official "Up Next" from YouTube Music (Fastest & Best Quality)
            val upNextResult = youTubeiService.getUpNext(songId)
            
            if (upNextResult.isSuccess) {
                val songs = upNextResult.getOrNull()
                if (!songs.isNullOrEmpty()) {
                    // Filter out duplicates and already played songs
                    val newSongs = filterNewSongs(songs, songId).take(limit)
                    if (newSongs.isNotEmpty()) {
                        Log.d(TAG, "✅ Used YouTube Music Up Next: ${newSongs.size} songs")
                        return Result.success(newSongs)
                    }
                }
            }
            
            Log.d(TAG, "⚠️ Up Next failed or returned only duplicates, falling back to manual mix")

            // Fallback: Try manual strategies in sequence
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
            
            // Filter out duplicates and already played
            val newSongs = filterNewSongs(songs, songId).take(limit)
            
            Log.d(TAG, "✅ Found ${newSongs.size} unique recommendations from fallback")
            Result.success(newSongs)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get related songs", e)
            Result.failure(e)
        } finally {
            isFetchingRecommendations = false
        }
    }

    override suspend fun getSongMix(songId: String, limit: Int): Result<List<Song>> {
        return try {
            val songs = fetchSongMix(songId, limit)
            val filtered = filterNewSongs(songs, songId).take(limit)
            Result.success(filtered)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Filter out songs that are already in queue or have been played
     * ViTune-style: Keeps recommendations fresh
     */
    private fun filterNewSongs(songs: List<Song>, excludeSongId: String): List<Song> {
        return songs.filter { song ->
            song.id != excludeSongId && 
            !queuedSongIds.contains(song.id) && 
            !playedSongIds.contains(song.id)
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
        
        // Rotate through different query types for variety
        val queries = listOf(
            "${songDetails.artist} mix",
            "${songDetails.artist} top songs",
            "${songDetails.title} similar",
            "songs like ${songDetails.artist}",
            "${songDetails.artist} best hits"
        )
        
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
    // QUEUE MANAGEMENT - ViTune Style
    // ═══════════════════════════════════════════════════════════════

    override suspend fun addToQueue(songs: List<Song>) {
        // Filter out any duplicates
        val newSongs = songs.filter { !queuedSongIds.contains(it.id) }
        
        if (newSongs.isEmpty()) {
            Log.d(TAG, "⚠️ No new songs to add (all duplicates)")
            return
        }
        
        // Add to tracking set
        newSongs.forEach { queuedSongIds.add(it.id) }
        
        // Limit the size of tracking sets to prevent memory issues
        trimTrackingSets()
        
        // Update queue
        _queue.value = _queue.value + newSongs
        Log.d(TAG, "➕ Added ${newSongs.size} songs. Queue size: ${_queue.value.size}, Total tracked: ${queuedSongIds.size}")
    }

    override suspend fun clearQueue() {
        _queue.value = emptyList()
        _currentIndex.value = 0
        queuedSongIds.clear()
        lastQueryType = 0
        lastRecommendationSongId = null
        isFetchingRecommendations = false
        Log.d(TAG, "🗑️ Queue cleared, tracking reset")
    }

    override suspend fun setInfiniteMode(enabled: Boolean) {
        _infiniteModeEnabled.value = enabled
        Log.d(TAG, "♾️ Infinite mode: $enabled")
    }

    override suspend fun ensureQueueNotEmpty(currentSongId: String): Boolean {
        if (!_infiniteModeEnabled.value) {
            Log.d(TAG, "⏸️ Infinite mode disabled, skipping queue fill")
            return false
        }

        val queue = _queue.value
        val index = _currentIndex.value
        val upcoming = if (index >= 0 && index < queue.size) {
            queue.size - index - 1
        } else {
            0
        }

        Log.d(TAG, "📊 Queue check: $upcoming upcoming songs (min: $MIN_QUEUE_SIZE)")

        if (upcoming < MIN_QUEUE_SIZE) {
            Log.d(TAG, "🔄 Queue running low, fetching more songs...")
            
            val result = getRelatedSongs(currentSongId, RECOMMENDATION_BATCH_SIZE)
            
            result.onSuccess { songs ->
                if (songs.isNotEmpty()) {
                    addToQueue(songs)
                    Log.d(TAG, "✅ Added ${songs.size} songs to prevent queue empty")
                    return true
                } else {
                    Log.d(TAG, "⚠️ No new recommendations available")
                }
            }.onFailure { e ->
                Log.e(TAG, "❌ Failed to fetch queue fill songs", e)
            }
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // TRACKING & SYNC
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mark a song as queued (prevent duplicates)
     */
    fun markSongAsQueued(songId: String) {
        queuedSongIds.add(songId)
    }

    /**
     * Mark a song as played (won't be recommended again)
     * ViTune-style: Keeps recommendations fresh
     */
    fun markSongAsPlayed(songId: String) {
        playedSongIds.add(songId)
        // Also remove from queued since it's now played
        queuedSongIds.remove(songId)
        trimTrackingSets()
    }

    /**
     * Check if a song is already queued
     */
    fun isSongQueued(songId: String): Boolean {
        return queuedSongIds.contains(songId)
    }

    /**
     * Check if a song has been played
     */
    fun isSongPlayed(songId: String): Boolean {
        return playedSongIds.contains(songId)
    }

    /**
     * Update current playing index
     */
    fun updateCurrentIndex(index: Int) {
        _currentIndex.value = index
    }

    /**
     * Sync the repository's queue state with the actual player queue
     * Call this when player queue changes externally
     */
    fun syncQueueState(songs: List<Song>, currentIndex: Int) {
        _queue.value = songs
        _currentIndex.value = currentIndex
        
        // Update tracking set to match actual queue
        queuedSongIds.clear()
        songs.forEach { queuedSongIds.add(it.id) }
        
        Log.d(TAG, "🔄 Queue synced: ${songs.size} songs, index: $currentIndex")
    }

    /**
     * Remove a song from tracking when it's removed from queue
     */
    fun removeFromTracking(songId: String) {
        queuedSongIds.remove(songId)
    }

    /**
     * Clear played history (if user wants fresh recommendations)
     */
    fun clearPlayedHistory() {
        playedSongIds.clear()
        Log.d(TAG, "🗑️ Played history cleared")
    }

    /**
     * Get tracking stats for debugging
     */
    fun getTrackingStats(): Triple<Int, Int, Int> {
        return Triple(queuedSongIds.size, playedSongIds.size, _queue.value.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun trimTrackingSets() {
        // Keep only the most recent items to prevent memory bloat
        if (queuedSongIds.size > MAX_HISTORY_SIZE) {
            val iterator = queuedSongIds.iterator()
            var count = 0
            while (iterator.hasNext() && count < queuedSongIds.size - MAX_HISTORY_SIZE) {
                iterator.next()
                iterator.remove()
                count++
            }
        }
        
        if (playedSongIds.size > MAX_HISTORY_SIZE) {
            val iterator = playedSongIds.iterator()
            var count = 0
            while (iterator.hasNext() && count < playedSongIds.size - MAX_HISTORY_SIZE) {
                iterator.next()
                iterator.remove()
                count++
            }
        }
    }
}
