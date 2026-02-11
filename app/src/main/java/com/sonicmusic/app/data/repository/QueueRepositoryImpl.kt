package com.sonicmusic.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.QueueRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue Repository Implementation - ViTune Style with Persistence
 *
 * Manages song recommendations for infinite queue functionality.
 * Key improvements:
 * - Persistent queue across app restarts
 * - Better duplicate prevention with proper sync
 * - Multiple recommendation strategies with smart fallbacks
 * - Context-aware recommendations based on current song
 * - YouTube Music radio mix for highly relevant suggestions
 * - "Play Next" functionality for immediate queue insertion
 * - Queue history tracking
 *
 * Recommendation Strategies (in priority order):
 * 1. YouTube Music "Up Next" API (Fastest & Best Quality)
 * 2. YouTube Music Radio Mix (RDAMVM - Song-based radio)
 * 3. Context-aware song mix based on artist + title
 * 4. Artist deep dive (more from same artist)
 * 5. Genre-aware discovery recommendations
 */
@Singleton
class QueueRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    @ApplicationContext private val context: Context
) : QueueRepository {

    private val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore(name = "queue_prefs")

    private object QueueKeys {
        val QUEUE_SONGS = stringPreferencesKey("queue_songs")
        val CURRENT_INDEX = intPreferencesKey("current_index")
        val INFINITE_MODE = stringPreferencesKey("infinite_mode")
    }

    companion object {
        private const val TAG = "QueueRepository"
        private const val MIN_QUEUE_SIZE = 3
        private const val RECOMMENDATION_BATCH_SIZE = 20
        private const val MAX_HISTORY_SIZE = 100 // Prevent memory bloat
        private const val RECOMMENDATION_DEBOUNCE_MS = 2000L // 2s debounce for snappier response
        
        // JSON configuration for safe serialization
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    // State flows for queue management
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _infiniteModeEnabled = MutableStateFlow(true)
    override val infiniteModeEnabled: StateFlow<Boolean> = _infiniteModeEnabled.asStateFlow()

    // Track queued song IDs to prevent duplicates
    private val queuedSongIds = LinkedHashSet<String>()
    
    // Track played songs to avoid recommending them again
    private val playedSongIds = LinkedHashSet<String>()
    
    // Vary recommendation queries for variety
    private var lastQueryType = 0
    private var lastRecommendationSongId: String? = null
    
    // Prevent duplicate recommendation fetches
    private var isFetchingRecommendations = false
    private var lastFetchTime = 0L
    
    // Cache last song details to avoid redundant API calls
    private var cachedSongDetails: Song? = null

    // ═══════════════════════════════════════════════════════════════
    // RECOMMENDATION FETCHING - Enhanced Quality
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getRelatedSongs(songId: String, limit: Int): Result<List<Song>> {
        // Debounce: prevent rapid recommendation fetches
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < RECOMMENDATION_DEBOUNCE_MS) {
            Log.d(TAG, "⚠️ Debouncing recommendation fetch (too soon)")
            return Result.success(emptyList())
        }
        
        // Prevent concurrent fetches (but allow re-fetch for same song after debounce)
        if (isFetchingRecommendations) {
            Log.d(TAG, "⚠️ Already fetching recommendations, skipping")
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
                    val newSongs = filterNewSongs(songs, songId).take(limit)
                    if (newSongs.isNotEmpty()) {
                        Log.d(TAG, "✅ Used YouTube Music Up Next: ${newSongs.size} songs")
                        return Result.success(newSongs)
                    }
                }
            }
            
            Log.d(TAG, "⚠️ Up Next returned insufficient results, trying radio mix...")

            // Priority 2: YouTube Music Radio Mix (song-based radio)
            val radioResult = fetchRadioMix(songId, limit)
            if (radioResult.isNotEmpty()) {
                val newSongs = filterNewSongs(radioResult, songId).take(limit)
                if (newSongs.isNotEmpty()) {
                    Log.d(TAG, "✅ Used Radio Mix: ${newSongs.size} songs")
                    return Result.success(newSongs)
                }
            }

            // Fallback: Try manual strategies in sequence
            var songs = listOf<Song>()
            
            // Strategy 3: Context-aware song mix
            if (songs.isEmpty()) {
                songs = fetchSongMix(songId, limit)
            }
            
            // Strategy 4: Artist deep dive
            if (songs.isEmpty()) {
                songs = fetchArtistSongs(songId, limit)
            }
            
            // Strategy 5: Genre-aware discovery
            if (songs.isEmpty()) {
                songs = fetchDiscoveryRecommendations(songId, limit)
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
     * Filter out songs that are:
     * 1. Already in queue
     * 2. Already played
     * 3. Not valid music content (videos, podcasts, etc.)
     * ViTune-style: Only keep actual songs
     */
    private fun filterNewSongs(songs: List<Song>, excludeSongId: String): List<Song> {
        val filtered = songs.filter { song ->
            song.id != excludeSongId &&
            !queuedSongIds.contains(song.id) &&
            !playedSongIds.contains(song.id) &&
            song.isValidMusicContent() // Only songs, not videos/podcasts/live streams
        }
        
        val filteredCount = songs.size - filtered.size
        if (filteredCount > 0) {
            // Detailed log for debugging radio mix
            Log.d(TAG, "🎬 Filtered out $filteredCount songs. Original: ${songs.size}, New: ${filtered.size}")
        }
        
        return filtered
    }

    /**
     * Get cached or fresh song details
     */
    private suspend fun getSongDetailsForId(songId: String): Song? {
        // Use cache if available for the same song
        cachedSongDetails?.let {
            if (it.id == songId) return it
        }
        
        return try {
            youTubeiService.getSongDetails(songId).getOrNull()?.also {
                cachedSongDetails = it
            }
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Could not get song details for $songId")
            null
        }
    }

    /**
     * Strategy 2: YouTube Music Radio Mix
     * Uses RDAMVM{videoId} playlist format for highly relevant song radio
     */
    private suspend fun fetchRadioMix(songId: String, limit: Int): List<Song> {
        Log.d(TAG, "📻 Trying Radio Mix for: $songId")
        return try {
            youTubeiService.getRadioMix(songId)
                .getOrNull()
                ?.take(limit)
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Radio mix failed", e)
            emptyList()
        }
    }

    /**
     * Strategy 3: Context-aware song mix based on current song
     * Uses smarter queries that leverage both title and artist
     */
    private suspend fun fetchSongMix(songId: String, limit: Int): List<Song> {
        val songDetails = getSongDetailsForId(songId) ?: return emptyList()
        
        // Smart query rotation - each focuses on a different aspect
        val queries = listOf(
            "${songDetails.artist} ${songDetails.title} mix",
            "songs similar to ${songDetails.title}",
            "${songDetails.artist} radio",
            "${songDetails.artist} best songs playlist",
            "if you like ${songDetails.artist}",
            "${songDetails.title} vibes",
            "more like ${songDetails.title} ${songDetails.artist}",
        )
        
        val queryIndex = lastQueryType % queries.size
        lastQueryType++
        val query = queries[queryIndex]
        
        Log.d(TAG, "🔍 Smart mix search: $query")
        
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
     * Strategy 4: Artist deep dive - more songs from the same artist
     */
    private suspend fun fetchArtistSongs(songId: String, limit: Int): List<Song> {
        val songDetails = getSongDetailsForId(songId) ?: return emptyList()
        
        // Rotate through different artist-focused queries
        val queries = listOf(
            "${songDetails.artist} top songs",
            "${songDetails.artist} popular songs",
            "${songDetails.artist} all songs",
            "${songDetails.artist} hits",
        )
        
        val query = queries[lastQueryType % queries.size]
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
     * Strategy 5: Genre-aware discovery recommendations
     * Uses the current song's context to find similar music rather than hardcoded queries
     */
    private suspend fun fetchDiscoveryRecommendations(songId: String, limit: Int): List<Song> {
        val songDetails = getSongDetailsForId(songId)
        
        // If we have song details, use context-aware queries
        val queries = if (songDetails != null) {
            listOf(
                "${songDetails.artist} similar artists songs",
                "songs like ${songDetails.title}",
                "${songDetails.artist} genre mix",
                "artists similar to ${songDetails.artist} top songs",
                "recommended songs ${songDetails.artist}",
            )
        } else {
            // True fallback - general music discovery
            listOf(
                "top songs this week",
                "trending music today",
                "popular songs right now",
                "viral hits music",
                "best new music today",
            )
        }
        
        val query = queries.random()
        Log.d(TAG, "🔍 Discovery search: $query")
        
        return try {
            youTubeiService.searchSongs(query, limit * 2)
                .getOrNull()
                ?.take(limit)
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Discovery search failed", e)
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // QUEUE MANAGEMENT - ViTune Style
    // ═══════════════════════════════════════════════════════════════

    override suspend fun addToQueue(songs: List<Song>) {
        // Filter out any duplicates AND non-music content (ViTune-style)
        val newSongs = songs
            .filter { !queuedSongIds.contains(it.id) }
            .filter { it.isValidMusicContent() } // Only add songs, not videos/podcasts/etc.

        if (newSongs.isEmpty()) {
            Log.d(TAG, "⚠️ No new songs to add (all duplicates or non-music content)")
            return
        }

        // Log filtered content
        val filteredCount = songs.size - newSongs.size
        if (filteredCount > 0) {
            Log.d(TAG, "🎬 Filtered $filteredCount non-music items from queue")
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
            
            // ViTune-style: If only one song left (current one), fetch aggressively
            val batchSize = if (upcoming <= 1) RECOMMENDATION_BATCH_SIZE * 2 else RECOMMENDATION_BATCH_SIZE
            val result = getRelatedSongs(currentSongId, batchSize)
            
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
     * ViTune-style: Filter to only music songs
     */
    fun syncQueueState(songs: List<Song>, currentIndex: Int) {
        // Filter to only valid music content
        val filteredSongs = songs.filter { it.isValidMusicContent() }

        if (filteredSongs.size < songs.size) {
            Log.d(TAG, "🎬 Filtered ${songs.size - filteredSongs.size} non-music items from queue sync")
        }

        _queue.value = filteredSongs
        _currentIndex.value = currentIndex.coerceIn(0, filteredSongs.size - 1)

        // Update tracking set to match actual queue
        queuedSongIds.clear()
        filteredSongs.forEach { queuedSongIds.add(it.id) }

        Log.d(TAG, "🔄 Queue synced: ${filteredSongs.size} songs, index: $currentIndex")
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
    // PERSISTENT QUEUE - ViTune Style
    // ═══════════════════════════════════════════════════════════════

    /**
     * Save current queue state to persistent storage
     */
    suspend fun saveQueueState() {
        try {
            val queueJson = json.encodeToString(_queue.value)
            context.queueDataStore.edit { preferences ->
                preferences[QueueKeys.QUEUE_SONGS] = queueJson
                preferences[QueueKeys.CURRENT_INDEX] = _currentIndex.value
                preferences[QueueKeys.INFINITE_MODE] = _infiniteModeEnabled.value.toString()
            }
            Log.d(TAG, "💾 Queue saved: ${_queue.value.size} songs, index: ${_currentIndex.value}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save queue state", e)
        }
    }

    /**
     * Restore queue state from persistent storage
     */
    suspend fun restoreQueueState(): Boolean {
        return try {
            val preferences = context.queueDataStore.data.first()
            val queueJson = preferences[QueueKeys.QUEUE_SONGS]
            val currentIndex = preferences[QueueKeys.CURRENT_INDEX] ?: 0
            val infiniteMode = preferences[QueueKeys.INFINITE_MODE]?.toBoolean() ?: true

            if (!queueJson.isNullOrEmpty()) {
                val songs = json.decodeFromString<List<Song>>(queueJson)
                if (songs.isNotEmpty()) {
                    _queue.value = songs
                    _currentIndex.value = currentIndex.coerceIn(0, songs.size - 1)
                    _infiniteModeEnabled.value = infiniteMode

                    // Restore tracking
                    queuedSongIds.clear()
                    songs.forEach { queuedSongIds.add(it.id) }

                    Log.d(TAG, "📂 Queue restored: ${songs.size} songs, index: $currentIndex")
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to restore queue state", e)
            // Clear corrupted data
            clearSavedQueue()
            false
        }
    }

    /**
     * Clear saved queue state
     */
    suspend fun clearSavedQueue() {
        try {
            context.queueDataStore.edit { preferences ->
                preferences.remove(QueueKeys.QUEUE_SONGS)
                preferences.remove(QueueKeys.CURRENT_INDEX)
                preferences.remove(QueueKeys.INFINITE_MODE)
            }
            Log.d(TAG, "🗑️ Saved queue cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear saved queue", e)
        }
    }

    /**
     * Check if there's a saved queue to restore
     */
    suspend fun hasSavedQueue(): Boolean {
        return try {
            val preferences = context.queueDataStore.data.first()
            val queueJson = preferences[QueueKeys.QUEUE_SONGS]
            !queueJson.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // QUEUE MANAGEMENT - Enhanced
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add songs to play next (right after current song)
     * ViTune-style: Insert at position currentIndex + 1
     */
    suspend fun addToPlayNext(songs: List<Song>) {
        if (songs.isEmpty()) return

        // Filter out duplicates AND non-music content
        val newSongs = songs
            .filter { !queuedSongIds.contains(it.id) }
            .filter { it.isValidMusicContent() } // Only songs, not videos/podcasts

        if (newSongs.isEmpty()) {
            Log.d(TAG, "⚠️ No new songs to add to play next (all duplicates or non-music)")
            return
        }

        val currentIndex = _currentIndex.value
        val currentQueue = _queue.value.toMutableList()

        // Insert after current song
        val insertIndex = (currentIndex + 1).coerceAtMost(currentQueue.size)
        currentQueue.addAll(insertIndex, newSongs)

        // Update tracking
        newSongs.forEach { queuedSongIds.add(it.id) }
        trimTrackingSets()

        // Update queue
        _queue.value = currentQueue.toList()

        Log.d(TAG, "⏭️ Added ${newSongs.size} songs to play next at index $insertIndex")
    }

    /**
     * Move a song in the queue
     */
    fun moveSong(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex < 0 || fromIndex >= _queue.value.size) return
        if (toIndex < 0 || toIndex >= _queue.value.size) return

        val currentQueue = _queue.value.toMutableList()
        val song = currentQueue.removeAt(fromIndex)
        currentQueue.add(toIndex, song)

        _queue.value = currentQueue.toList()

        // Update current index if needed
        val currentId = _currentSongId()
        if (currentId != null) {
            _currentIndex.value = currentQueue.indexOfFirst { it.id == currentId }
        }

        Log.d(TAG, "🔀 Moved song from $fromIndex to $toIndex")
    }

    /**
     * Remove a song from the queue
     */
    fun removeFromQueue(index: Int): Boolean {
        if (index < 0 || index >= _queue.value.size) return false

        val currentQueue = _queue.value.toMutableList()
        val removedSong = currentQueue.removeAt(index)

        // Update tracking
        queuedSongIds.remove(removedSong.id)

        // Update queue
        _queue.value = currentQueue.toList()

        // Adjust current index if needed
        if (index < _currentIndex.value) {
            _currentIndex.value = _currentIndex.value - 1
        } else if (index == _currentIndex.value && currentQueue.isNotEmpty()) {
            // Current song was removed, keep same index (will be next song)
            _currentIndex.value = _currentIndex.value.coerceIn(0, currentQueue.size - 1)
        }

        Log.d(TAG, "🗑️ Removed song at index $index: ${removedSong.title}")
        return true
    }

    /**
     * Get the current song ID
     */
    private fun _currentSongId(): String? {
        val index = _currentIndex.value
        val queue = _queue.value
        return if (index >= 0 && index < queue.size) {
            queue[index].id
        } else {
            null
        }
    }

    /**
     * Get upcoming songs (remaining in queue)
     */
    fun getUpcomingSongs(): List<Song> {
        val index = _currentIndex.value
        val queue = _queue.value
        return if (index >= 0 && index < queue.size) {
            queue.subList(index + 1, queue.size)
        } else {
            emptyList()
        }
    }

    /**
     * Get number of remaining songs in queue
     */
    fun getRemainingCount(): Int {
        val index = _currentIndex.value
        val queueSize = _queue.value.size
        return if (index >= 0 && index < queueSize) {
            queueSize - index - 1
        } else {
            0
        }
    }

    /**
     * Get only music songs from queue (ViTune-style filtering)
     * Filters out videos, podcasts, live streams, shorts, etc.
     */
    fun getMusicSongsOnly(): List<Song> {
        return _queue.value.filter { it.isValidMusicContent() }
    }

    /**
     * Check if queue contains any non-music content
     */
    fun hasNonMusicContent(): Boolean {
        return _queue.value.any { !it.isValidMusicContent() }
    }

    /**
     * Remove all non-music content from queue
     */
    fun removeNonMusicContent(): Int {
        val originalSize = _queue.value.size
        val filteredQueue = _queue.value.filter { it.isValidMusicContent() }
        val removedCount = originalSize - filteredQueue.size

        if (removedCount > 0) {
            _queue.value = filteredQueue
            // Adjust current index
            if (_currentIndex.value >= filteredQueue.size) {
                _currentIndex.value = filteredQueue.size - 1
            }
            Log.d(TAG, "🎬 Removed $removedCount non-music items from queue")
        }

        return removedCount
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
