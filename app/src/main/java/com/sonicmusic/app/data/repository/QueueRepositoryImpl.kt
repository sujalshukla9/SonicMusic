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
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.QueueRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val userTasteRepository: UserTasteRepository,
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
        private const val RECOMMENDATION_DEBOUNCE_MS = 2000L // Same-seed debounce
        private const val DIFFERENT_SONG_GUARD_MS = 350L // Avoid rapid bursts across songs
        private const val MIN_PRIMARY_RECOMMENDATIONS = 6
        private const val PRIMARY_FETCH_TIMEOUT_MS = 3000L
        private const val RECOMMENDATION_CACHE_TTL_MS = 90_000L
        private const val RECOMMENDATION_CACHE_MAX_ENTRIES = 40
        
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

    // Lock for thread-safe access to tracking sets
    private val trackingLock = Any()

    // Track queued song IDs to prevent duplicates
    private val queuedSongIds = LinkedHashSet<String>()
    
    // Track played songs to avoid recommending them again
    private val playedSongIds = LinkedHashSet<String>()
    
    // Vary recommendation queries for variety
    private var lastQueryType = 0
    
    // Prevent duplicate recommendation fetches
    private var isFetchingRecommendations = false
    private var lastFetchTime = 0L
    private var lastFetchSongId: String? = null
    
    // Cache last song details to avoid redundant API calls
    private var cachedSongDetails: Song? = null
    private val recommendationCache = LinkedHashMap<String, RecommendationCacheEntry>()

    private data class RecommendationCacheEntry(
        val timestampMs: Long,
        val songs: List<Song>
    )

    // ═══════════════════════════════════════════════════════════════
    // RECOMMENDATION FETCHING - Enhanced Quality
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getRelatedSongs(songId: String, limit: Int): Result<List<Song>> {
        val targetLimit = limit.coerceAtLeast(1)
        val cachedRecommendations = getCachedRecommendations(songId, targetLimit)
        if (cachedRecommendations.isNotEmpty()) {
            Log.d(TAG, "⚡ Serving ${cachedRecommendations.size} cached recommendations for: $songId")
            return Result.success(cachedRecommendations)
        }

        // Debounce: prevent rapid recommendation fetches
        val now = System.currentTimeMillis()
        val minGap = if (lastFetchSongId == songId) RECOMMENDATION_DEBOUNCE_MS else DIFFERENT_SONG_GUARD_MS
        if (now - lastFetchTime < minGap) {
            Log.d(TAG, "⚠️ Debouncing recommendation fetch (too soon, seed=$songId)")
            return Result.success(emptyList())
        }
        
        // Prevent concurrent fetches (but allow re-fetch for same song after debounce)
        if (isFetchingRecommendations) {
            Log.d(TAG, "⚠️ Already fetching recommendations, skipping")
            return Result.success(emptyList())
        }
        
        isFetchingRecommendations = true
        lastFetchTime = now
        lastFetchSongId = songId
        
        Log.d(TAG, "🎵 Getting related songs for: $songId")
        
        return try {
            val (seedSong, upNextSongs, radioSongs) = coroutineScope {
                val seedDeferred = async { getSongDetailsForId(songId) }
                val upNextDeferred = async {
                    withTimeoutOrNull(PRIMARY_FETCH_TIMEOUT_MS) {
                        youTubeiService.getUpNext(songId).getOrNull().orEmpty()
                    } ?: emptyList()
                }
                val radioDeferred = async {
                    withTimeoutOrNull(PRIMARY_FETCH_TIMEOUT_MS) {
                        fetchRadioMix(songId, targetLimit * 2)
                    } ?: emptyList()
                }
                Triple(seedDeferred.await(), upNextDeferred.await(), radioDeferred.await())
            }

            val blendedCandidates = interleaveRecommendations(
                primary = upNextSongs,
                secondary = radioSongs,
                maxSize = targetLimit * 4
            )

            val rankedCandidates = rankCandidates(seedSong, blendedCandidates)
            val primaryNewSongs = filterNewSongs(rankedCandidates, songId)
            if (primaryNewSongs.size >= minOf(targetLimit, MIN_PRIMARY_RECOMMENDATIONS)) {
                Log.d(
                    TAG,
                    "✅ Used blended Up Next + Radio: ${primaryNewSongs.size} songs (upNext=${upNextSongs.size}, radio=${radioSongs.size})"
                )
                cacheRecommendations(songId, primaryNewSongs)
                return Result.success(primaryNewSongs.take(targetLimit))
            }

            Log.d(TAG, "⚠️ Up Next + Radio insufficient (${primaryNewSongs.size}), enriching with fallbacks...")

            val collected = LinkedHashMap<String, Song>()
            fun collect(candidates: List<Song>) {
                filterNewSongs(rankCandidates(seedSong, candidates), songId).forEach { candidate ->
                    if (collected.size < targetLimit) {
                        collected.putIfAbsent(candidate.id, candidate)
                    }
                }
            }

            collect(primaryNewSongs)
            if (collected.size < targetLimit) {
                collect(fetchSongMix(songId, targetLimit * 2))
            }
            if (collected.size < targetLimit) {
                collect(fetchArtistSongs(songId, targetLimit * 2))
            }
            if (collected.size < targetLimit) {
                collect(fetchDiscoveryRecommendations(songId, targetLimit * 2))
            }
            if (collected.size < targetLimit) {
                collect(fetchTasteBasedRecommendations(songId, targetLimit * 2))
            }
            if (collected.size < targetLimit) {
                val broadQuery = seedSong?.let { "${it.artist} radio songs mix" } ?: "trending songs now"
                val broadFallback = youTubeiService.searchSongs(broadQuery, targetLimit * 2).getOrNull().orEmpty()
                collect(broadFallback)
            }

            val resultSongs = collected.values.take(targetLimit)
            cacheRecommendations(songId, collected.values.toList())
            Log.d(TAG, "✅ Final related songs: ${resultSongs.size} (target=$targetLimit)")
            Result.success(resultSongs)
            
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
        val (queued, played) = synchronized(trackingLock) {
            queuedSongIds.toHashSet() to playedSongIds.toHashSet()
        }
        val filtered = songs
            .asSequence()
            .filter { song ->
                song.id != excludeSongId &&
                    !queued.contains(song.id) &&
                    !played.contains(song.id) &&
                    song.isStrictQueueSong() // Only songs, not videos/podcasts/live streams
            }
            .distinctBy { it.id }
            .toList()
        
        val filteredCount = songs.size - filtered.size
        if (filteredCount > 0) {
            // Detailed log for debugging radio mix
            Log.d(TAG, "🎬 Filtered out $filteredCount songs. Original: ${songs.size}, New: ${filtered.size}")
        }
        
        return filtered
    }

    private fun interleaveRecommendations(
        primary: List<Song>,
        secondary: List<Song>,
        maxSize: Int
    ): List<Song> {
        val merged = mutableListOf<Song>()
        val seen = HashSet<String>()
        var primaryIndex = 0
        var secondaryIndex = 0

        fun addCandidate(song: Song) {
            if (seen.add(song.id)) {
                merged.add(song)
            }
        }

        while (merged.size < maxSize && (primaryIndex < primary.size || secondaryIndex < secondary.size)) {
            repeat(2) {
                if (primaryIndex < primary.size) {
                    addCandidate(primary[primaryIndex])
                    primaryIndex += 1
                }
            }
            if (secondaryIndex < secondary.size) {
                addCandidate(secondary[secondaryIndex])
                secondaryIndex += 1
            }
        }

        return merged
    }

    private fun rankCandidates(seedSong: Song?, candidates: List<Song>): List<Song> {
        if (seedSong == null || candidates.isEmpty()) return candidates

        val normalizedSeedArtist = normalizeArtist(seedSong.artist)
        val seedTitleTokens = tokenize(seedSong.title)

        return candidates.sortedByDescending { candidate ->
            var score = 0
            val normalizedCandidateArtist = normalizeArtist(candidate.artist)
            val candidateTitleTokens = tokenize(candidate.title)

            if (normalizedCandidateArtist == normalizedSeedArtist) {
                score += 6
            }

            val overlap = seedTitleTokens.intersect(candidateTitleTokens).size
            score += overlap * 2

            if (candidate.duration in 90..420) {
                score += 1
            }

            if (candidate.artist.isBlank() || candidate.artist.equals("Unknown Artist", ignoreCase = true)) {
                score -= 2
            }
            if (candidate.title.length < 3) {
                score -= 2
            }

            score
        }
    }

    private fun normalizeArtist(value: String): String {
        return value.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(value: String): Set<String> {
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()
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

    /**
     * Strategy 6: User taste-biased recommendations
     * Uses the user's accumulated listening preferences (top artists, genres)
     * to generate highly personalized queue candidates.
     */
    private suspend fun fetchTasteBasedRecommendations(songId: String, limit: Int): List<Song> {
        Log.d(TAG, "🧠 Trying taste-based recommendations...")
        return try {
            val tasteProfile = userTasteRepository.getUserTasteProfile()
            
            if (tasteProfile.topArtists.isEmpty()) {
                Log.d(TAG, "⚠️ No taste data yet, skipping taste-based recs")
                return emptyList()
            }
            
            // Build query combining top artist + genre for high relevance
            val artist = tasteProfile.topArtists.random()
            val genre = tasteProfile.topGenres.firstOrNull() ?: ""
            val query = if (genre.isNotEmpty()) {
                "$artist $genre songs"
            } else {
                "$artist best songs mix"
            }
            
            Log.d(TAG, "🧠 Taste query: $query")
            
            youTubeiService.searchSongs(query, limit * 2)
                .getOrNull()
                ?.filter { it.id != songId }
                ?.take(limit)
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Taste-based recommendations failed", e)
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // QUEUE MANAGEMENT - ViTune Style
    // ═══════════════════════════════════════════════════════════════

    override suspend fun addToQueue(songs: List<Song>) {
        // Filter out any duplicates AND non-music content (ViTune-style)
        val newSongs = synchronized(trackingLock) {
            songs
                .filter { !queuedSongIds.contains(it.id) }
                .filter { it.isStrictQueueSong() } // Only add songs, not videos/podcasts/etc.
        }

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
        synchronized(trackingLock) {
            newSongs.forEach { queuedSongIds.add(it.id) }
            trimTrackingSets()
        }

        // Update queue
        _queue.value = _queue.value + newSongs
        Log.d(TAG, "➕ Added ${newSongs.size} songs. Queue size: ${_queue.value.size}, Total tracked: ${queuedSongIds.size}")
    }

    override suspend fun clearQueue() {
        _queue.value = emptyList()
        _currentIndex.value = 0
        synchronized(trackingLock) {
            queuedSongIds.clear()
        }
        synchronized(recommendationCache) {
            recommendationCache.clear()
        }
        lastQueryType = 0
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
        synchronized(trackingLock) {
            queuedSongIds.add(songId)
        }
    }

    /**
     * Mark a song as played (won't be recommended again)
     * ViTune-style: Keeps recommendations fresh
     */
    fun markSongAsPlayed(songId: String) {
        synchronized(trackingLock) {
            playedSongIds.add(songId)
            // Also remove from queued since it's now played
            queuedSongIds.remove(songId)
            trimTrackingSets()
        }
    }

    /**
     * Check if a song is already queued
     */
    fun isSongQueued(songId: String): Boolean {
        return synchronized(trackingLock) {
            queuedSongIds.contains(songId)
        }
    }

    /**
     * Check if a song has been played
     */
    fun isSongPlayed(songId: String): Boolean {
        return synchronized(trackingLock) {
            playedSongIds.contains(songId)
        }
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
        // Bug 2 fix: Do NOT filter during sync — the repository must track exactly
        // what the player has. Content filtering happens at ingestion time only.
        _queue.value = songs
        _currentIndex.value = if (songs.isEmpty()) {
            -1
        } else {
            currentIndex.coerceIn(0, songs.size - 1)
        }

        // Update tracking set to match actual queue
        synchronized(trackingLock) {
            queuedSongIds.clear()
            songs.forEach { queuedSongIds.add(it.id) }
        }

        Log.d(TAG, "🔄 Queue synced: ${songs.size} songs, index: $currentIndex")
    }

    /**
     * Remove a song from tracking when it's removed from queue
     */
    fun removeFromTracking(songId: String) {
        synchronized(trackingLock) {
            queuedSongIds.remove(songId)
        }
    }

    /**
     * Clear played history (if user wants fresh recommendations)
     */
    fun clearPlayedHistory() {
        synchronized(trackingLock) {
            playedSongIds.clear()
        }
        Log.d(TAG, "🗑️ Played history cleared")
    }

    /**
     * Get tracking stats for debugging
     */
    fun getTrackingStats(): Triple<Int, Int, Int> {
        return synchronized(trackingLock) {
            Triple(queuedSongIds.size, playedSongIds.size, _queue.value.size)
        }
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
                    synchronized(trackingLock) {
                        queuedSongIds.clear()
                        songs.forEach { queuedSongIds.add(it.id) }
                    }

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
        val newSongs = synchronized(trackingLock) {
            songs
                .filter { !queuedSongIds.contains(it.id) }
                .filter { it.isStrictQueueSong() } // Only songs, not videos/podcasts
        }

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
        synchronized(trackingLock) {
            newSongs.forEach { queuedSongIds.add(it.id) }
            trimTrackingSets()
        }

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
        synchronized(trackingLock) {
            queuedSongIds.remove(removedSong.id)
        }

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
        return _queue.value.filter { it.isStrictQueueSong() }
    }

    /**
     * Check if queue contains any non-music content
     */
    fun hasNonMusicContent(): Boolean {
        return _queue.value.any { !it.isStrictQueueSong() }
    }

    /**
     * Remove all non-music content from queue
     */
    fun removeNonMusicContent(): Int {
        val originalSize = _queue.value.size
        val filteredQueue = _queue.value.filter { it.isStrictQueueSong() }
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

    private fun Song.isStrictQueueSong(): Boolean {
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.UNKNOWN -> duration == 0 || duration in 30..900
            else -> false
        }
    }

    private fun getCachedRecommendations(songId: String, limit: Int): List<Song> {
        val now = System.currentTimeMillis()
        val cached = synchronized(recommendationCache) {
            recommendationCache.entries.removeAll { (_, value) ->
                now - value.timestampMs > RECOMMENDATION_CACHE_TTL_MS
            }
            recommendationCache[songId]
        } ?: return emptyList()

        return filterNewSongs(cached.songs, songId).take(limit)
    }

    private fun cacheRecommendations(songId: String, songs: List<Song>) {
        if (songId.isBlank() || songs.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(recommendationCache) {
            recommendationCache[songId] = RecommendationCacheEntry(
                timestampMs = now,
                songs = songs
            )
            trimRecommendationCacheLocked(now)
        }
    }

    private fun trimRecommendationCacheLocked(nowMs: Long) {
        recommendationCache.entries.removeAll { (_, value) ->
            nowMs - value.timestampMs > RECOMMENDATION_CACHE_TTL_MS
        }
        while (recommendationCache.size > RECOMMENDATION_CACHE_MAX_ENTRIES) {
            val firstKey = recommendationCache.keys.firstOrNull() ?: break
            recommendationCache.remove(firstKey)
        }
    }
}
