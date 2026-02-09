package com.sonicmusic.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.repository.QueueRepositoryImpl
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.usecase.EqualizerManager
import com.sonicmusic.app.domain.usecase.SleepTimerManager
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Player ViewModel - ViTune Style
 * 
 * Coordinates between UI, PlayerServiceConnection, and repositories.
 * Handles:
 * - Stream URL fetching
 * - Playback control delegation
 * - Infinite queue recommendations (ViTune-style)
 * - Like/unlike functionality
 * - Sleep timer
 * - Error handling
 * - Queue state synchronization
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
    private val queueRepository: QueueRepositoryImpl,
    val sleepTimerManager: SleepTimerManager,
    val equalizerManager: EqualizerManager
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val MAX_URL_RETRY_ATTEMPTS = 3
        private const val URL_RETRY_DELAY_MS = 1500L
        private const val RECOMMENDATION_BATCH_SIZE = 3 // Process in small batches for instant feel
        private const val RECOMMENDATION_FETCH_LIMIT = 15
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK STATE (delegated from PlayerServiceConnection)
    // ═══════════════════════════════════════════════════════════════
    
    val currentSong: StateFlow<Song?> = playerServiceConnection.currentSong
    val isPlaying: StateFlow<Boolean> = playerServiceConnection.isPlaying
    val isBuffering: StateFlow<Boolean> = playerServiceConnection.isBuffering
    val progress: StateFlow<Float> = playerServiceConnection.progress
    val currentPosition: StateFlow<Long> = playerServiceConnection.currentPosition
    val duration: StateFlow<Long> = playerServiceConnection.duration
    val queue: StateFlow<List<Song>> = playerServiceConnection.queue
    val currentQueueIndex: StateFlow<Int> = playerServiceConnection.currentQueueIndex
    val repeatMode: StateFlow<Int> = playerServiceConnection.repeatMode
    val shuffleEnabled: StateFlow<Boolean> = playerServiceConnection.shuffleEnabled
    val playbackError: StateFlow<String?> = playerServiceConnection.playbackError
    val playbackSpeed: StateFlow<Float> = playerServiceConnection.playbackSpeed
    val isLoadingMore: StateFlow<Boolean> = playerServiceConnection.isLoadingMore

    // ═══════════════════════════════════════════════════════════════
    // LOCAL STATE
    // ═══════════════════════════════════════════════════════════════
    
    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _infiniteModeEnabled = MutableStateFlow(true)
    val infiniteModeEnabled: StateFlow<Boolean> = _infiniteModeEnabled.asStateFlow()

    // Track active recommendation fetch to prevent duplicates
    private var recommendationJob: Job? = null
    private var isRecommendationFetching = false
    private var lastRecommendationSongId: String? = null

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    init {
        // Connect to PlaybackService
        playerServiceConnection.connect()
        
        // Set up callback for infinite queue (ViTune-style)
        playerServiceConnection.setOnQueueNeedsMoreSongs { songId ->
            if (_infiniteModeEnabled.value && !isRecommendationFetching) {
                Log.d(TAG, "🎵 Queue requested more songs for: $songId")
                fetchAndAddRecommendations(songId)
            }
        }
        
        // Set up callback for URL expiration (retry with fresh URL)
        playerServiceConnection.setOnStreamUrlExpired { songId ->
            Log.d(TAG, "🔄 Stream URL expired for: $songId")
            retryWithFreshUrl(songId)
        }
        
        // Observe current song for like status and played tracking
        viewModelScope.launch {
            currentSong.collect { song ->
                song?.let {
                    // Update like status
                    _isLiked.value = songRepository.isLiked(it.id)
                    
                    // Mark previous songs as played (ViTune-style)
                    // This prevents them from being recommended again
                    val currentIndex = currentQueueIndex.value
                    if (currentIndex > 0) {
                        queue.value.take(currentIndex).forEach { playedSong ->
                            queueRepository.markSongAsPlayed(playedSong.id)
                        }
                    }
                }
            }
        }
        
        // Sync queue state with repository
        viewModelScope.launch {
            queue.collect { songs ->
                val index = currentQueueIndex.value
                queueRepository.syncQueueState(songs, index)
            }
        }
        
        // Sync current index with repository
        viewModelScope.launch {
            currentQueueIndex.collect { index ->
                queueRepository.updateCurrentIndex(index)
            }
        }
    }
    
    /**
     * Retry playback with a fresh stream URL (when current URL expires)
     */
    private fun retryWithFreshUrl(songId: String) {
        viewModelScope.launch {
            Log.d(TAG, "🔄 Retrying with fresh URL for: $songId")

            // Clear cached URL first
            songRepository.clearCachedStreamUrl(songId)

            // Re-fetch and resume playback with retry logic
            currentSong.value?.let { song ->
                if (song.id == songId) {
                    _error.value = "Refreshing stream..."

                    var lastException: Exception? = null

                    // Retry with progressive delay
                    repeat(MAX_URL_RETRY_ATTEMPTS) { attempt ->
                        try {
                            Log.d(TAG, "📡 URL refresh attempt ${attempt + 1}/$MAX_URL_RETRY_ATTEMPTS")

                            val result = songRepository.getStreamUrl(song.id, StreamQuality.BEST)

                            result.onSuccess { streamUrl ->
                                Log.d(TAG, "✅ Got fresh stream URL, resuming playback")
                                _error.value = null
                                playerServiceConnection.playSong(song, streamUrl)
                                return@launch
                            }.onFailure { exception ->
                                Log.e(TAG, "❌ Failed to get fresh URL", exception)
                                lastException = exception as? Exception
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error refreshing URL", e)
                            lastException = e
                        }

                        // Wait before retry (except on last attempt)
                        if (attempt < MAX_URL_RETRY_ATTEMPTS - 1) {
                            delay(URL_RETRY_DELAY_MS * (attempt + 1))
                        }
                    }

                    // All retries failed
                    _error.value = "Failed to refresh stream: ${lastException?.message ?: "Unknown error"}"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK OPERATIONS - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Play a song - ViTune Style (instant recommendations)
     *
     * @param song The song to play
     */
    fun playSong(song: Song) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            var lastException: Exception? = null

            // Retry loop for initial playback
            repeat(MAX_URL_RETRY_ATTEMPTS) { attempt ->
                try {
                    Log.d(TAG, "▶️ Loading stream for: ${song.title} (attempt ${attempt + 1})")
                    
                    // Clear and reset queue state
                    queueRepository.clearQueue()
                    lastRecommendationSongId = null

                    // Fetch stream URL
                    val result = songRepository.getStreamUrl(song.id, StreamQuality.BEST)

                    result.onSuccess { streamUrl ->
                        Log.d(TAG, "✅ Got stream URL, starting playback")

                        // Start playback
                        playerServiceConnection.playSong(song, streamUrl)

                        // Update like status
                        _isLiked.value = songRepository.isLiked(song.id)
                        
                        // ViTune Logic: Instant Radio - fetch recommendations immediately
                        if (_infiniteModeEnabled.value) {
                            delay(500) // Small delay to let playback start first
                            fetchAndAddRecommendations(song.id)
                        }

                        _isLoading.value = false
                        return@launch

                    }.onFailure { exception ->
                        Log.e(TAG, "❌ Failed to get stream URL", exception)
                        lastException = exception as? Exception
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error playing song", e)
                    lastException = e
                }

                // Wait before retry (except on last attempt)
                if (attempt < MAX_URL_RETRY_ATTEMPTS - 1) {
                    delay(URL_RETRY_DELAY_MS * (attempt + 1))
                }
            }

            // All retries failed
            _error.value = "Failed to load song: ${lastException?.message ?: "Unknown error"}"
            _isLoading.value = false
        }
    }

    /**
     * Play a list of songs starting at a specific index - ViTune Style
     * Optimized for "Just-in-Time" playback with instant recommendations
     */
    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                Log.d(TAG, "▶️ Playing queue: ${songs.size} songs, starting at $startIndex")
                
                // 1. Prioritize starting the requested song immediately
                val targetSong = songs.getOrNull(startIndex) ?: return@launch
                
                // Reset queue state
                queueRepository.clearQueue()
                lastRecommendationSongId = null
                songs.forEach { queueRepository.markSongAsQueued(it.id) }
                
                // Fetch URL for ONLY the target song first (Fast Start)
                val targetUrlResult = songRepository.getStreamUrl(targetSong.id, StreamQuality.BEST)
                
                targetUrlResult.onSuccess { targetUrl ->
                    // Start playing immediately with what we have
                    val initialMap = mapOf(targetSong.id to targetUrl)
                    playerServiceConnection.playWithQueue(songs, initialMap, startIndex)
                    
                    _isLoading.value = false
                    
                    // Update like status
                    _isLiked.value = songRepository.isLiked(targetSong.id)

                    // 2. Fetch remaining URLs in background
                    launch(Dispatchers.IO) {
                        try {
                            // Fetch rest of songs in batches to avoid network congestion
                            val remainingSongs = songs.filter { it.id != targetSong.id }
                            
                            remainingSongs.chunked(5).forEach { batch ->
                                if (!this.isActive) return@forEach
                                val batchUrls = fetchStreamUrlsBatch(batch)
                                if (batchUrls.isNotEmpty()) {
                                    playerServiceConnection.updateStreamUrls(batchUrls)
                                }
                                delay(100) // Small delay between batches
                            }
                            
                            // ViTune: Prefetch recommendations when queue is running low
                            if (_infiniteModeEnabled.value) {
                                val lastSong = songs.lastOrNull()
                                lastSong?.let {
                                    delay(1000) // Wait for initial playback to settle
                                    fetchAndAddRecommendations(it.id)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Background fetch failed", e)
                        }
                    }
                }.onFailure { e ->
                    _error.value = "Failed to play: ${e.message}"
                    _isLoading.value = false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing queue", e)
                _error.value = "Error: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * Add a song to the queue
     */
    fun addToQueue(song: Song) {
        viewModelScope.launch {
            try {
                // Check if already in queue (ViTune-style duplicate prevention)
                if (queueRepository.isSongQueued(song.id)) {
                    Log.d(TAG, "⚠️ Song already in queue: ${song.title}")
                    return@launch
                }
                
                val result = songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                result.onSuccess { streamUrl ->
                    playerServiceConnection.addToQueue(song, streamUrl)
                    queueRepository.markSongAsQueued(song.id)
                    Log.d(TAG, "➕ Added to queue: ${song.title}")
                }.onFailure {
                    Log.e(TAG, "Failed to add to queue", it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to queue", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═══════════════════════════════════════════════════════════════
    
    fun togglePlayPause() = playerServiceConnection.togglePlayPause()
    
    fun skipToNext() = playerServiceConnection.skipToNext()
    
    fun skipToPrevious() = playerServiceConnection.skipToPrevious()
    
    fun seekTo(progress: Float) = playerServiceConnection.seekTo(progress)
    
    fun seekToPosition(positionMs: Long) = playerServiceConnection.seekToPosition(positionMs)
    
    fun toggleRepeatMode() = playerServiceConnection.toggleRepeatMode()
    
    fun toggleShuffle() = playerServiceConnection.toggleShuffle()

    fun reorderQueue(from: Int, to: Int) = playerServiceConnection.reorderQueue(from, to)
    
    fun removeFromQueue(index: Int) {
        playerServiceConnection.removeFromQueue(index)
        // Update repository tracking
        queue.value.getOrNull(index)?.let { song ->
            queueRepository.removeFromTracking(song.id)
        }
    }
    
    fun clearQueue() {
        playerServiceConnection.clearQueue()
        viewModelScope.launch {
            queueRepository.clearQueue()
        }
    }
    
    fun skipToQueueItem(index: Int) = playerServiceConnection.skipToQueueItem(index)
    
    fun stop() = playerServiceConnection.stop()

    fun setPlaybackSpeed(speed: Float) = playerServiceConnection.setPlaybackSpeed(speed)

    // ═══════════════════════════════════════════════════════════════
    // SLEEP TIMER
    // ═══════════════════════════════════════════════════════════════
    
    // Expose sleep timer states
    val sleepTimerRemaining: StateFlow<Duration?> = sleepTimerManager.remainingTime
    val sleepTimerActive: StateFlow<Boolean> = sleepTimerManager.isActive

    /**
     * Start sleep timer with the specified duration.
     * Music will stop and fade out when timer completes.
     */
    fun startSleepTimer(duration: Duration) {
        sleepTimerManager.startTimer(
            duration = duration,
            scope = viewModelScope,
            onComplete = {
                Log.d(TAG, "😴 Sleep timer complete - stopping playback")
                stop()
            },
            onFade = { volumeMultiplier ->
                Log.d(TAG, "🔉 Fading volume: $volumeMultiplier")
            }
        )
        Log.d(TAG, "⏰ Sleep timer started: $duration")
    }

    /**
     * Cancel the active sleep timer.
     */
    fun cancelSleepTimer() {
        sleepTimerManager.cancelTimer()
        Log.d(TAG, "⏰ Sleep timer cancelled")
    }

    /**
     * Add 5 minutes to the active sleep timer.
     */
    fun extendSleepTimer() {
        sleepTimerManager.addFiveMinutes(viewModelScope)
        Log.d(TAG, "⏰ Sleep timer extended by 5 minutes")
    }

    // ═══════════════════════════════════════════════════════════════
    // EQUALIZER
    // ═══════════════════════════════════════════════════════════════

    val equalizerEnabled = equalizerManager.enabled
    val equalizerBands = equalizerManager.bands
    val equalizerPresets = equalizerManager.presets
    val currentPreset = equalizerManager.currentPreset

    fun setEqualizerEnabled(enabled: Boolean) = equalizerManager.setEnabled(enabled)

    fun setBandLevel(bandId: Short, level: Short) = equalizerManager.setBandLevel(bandId, level)

    fun usePreset(presetIndex: Short) = equalizerManager.usePreset(presetIndex)

    // ═══════════════════════════════════════════════════════════════
    // LIKE FUNCTIONALITY
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Toggle like status for current song
     */
    fun toggleLike() {
        viewModelScope.launch {
            currentSong.value?.let { song ->
                try {
                    if (_isLiked.value) {
                        songRepository.unlikeSong(song.id)
                        _isLiked.value = false
                        Log.d(TAG, "💔 Unliked: ${song.title}")
                    } else {
                        songRepository.likeSong(song.id)
                        _isLiked.value = true
                        Log.d(TAG, "❤️ Liked: ${song.title}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling like", e)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INFINITE QUEUE - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Toggle infinite queue mode
     */
    fun toggleInfiniteMode() {
        _infiniteModeEnabled.value = !_infiniteModeEnabled.value
        viewModelScope.launch {
            queueRepository.setInfiniteMode(_infiniteModeEnabled.value)
        }
        Log.d(TAG, "♾️ Infinite mode: ${_infiniteModeEnabled.value}")
    }
    
    /**
     * Enable/disable infinite mode explicitly
     */
    fun setInfiniteMode(enabled: Boolean) {
        _infiniteModeEnabled.value = enabled
        viewModelScope.launch {
            queueRepository.setInfiniteMode(enabled)
        }
        Log.d(TAG, "♾️ Infinite mode set to: $enabled")
    }

    /**
     * Fetch recommendations and add to queue - ViTune Style
     * Uses BATCH LOADING for instant perception of speed
     * Prevents duplicate fetches and handles edge cases
     */
    private fun fetchAndAddRecommendations(currentSongId: String) {
        // Prevent duplicate fetches for the same song
        if (currentSongId == lastRecommendationSongId) {
            Log.d(TAG, "⚠️ Already fetching recommendations for: $currentSongId")
            return
        }
        
        // Cancel any existing job
        recommendationJob?.cancel()
        
        recommendationJob = viewModelScope.launch {
            if (isRecommendationFetching) {
                Log.d(TAG, "⚠️ Recommendation fetch already in progress")
                return@launch
            }
            
            isRecommendationFetching = true
            lastRecommendationSongId = currentSongId
            
            try {
                Log.d(TAG, "🔄 Fetching recommendations for: $currentSongId")
                
                // Get related songs (Fast due to getUpNext optimization)
                val result = queueRepository.getRelatedSongs(currentSongId, RECOMMENDATION_FETCH_LIMIT)
                
                result.onSuccess { songs ->
                    if (songs.isEmpty()) {
                        Log.d(TAG, "⚠️ No recommendations found")
                        return@onSuccess
                    }
                    
                    Log.d(TAG, "✅ Got ${songs.size} recommendations")
                    
                    // ViTune: PROCESS IN BATCHES (Chunk by 3)
                    // This creates an "instant" feel as the first few songs appear immediately
                    songs.chunked(RECOMMENDATION_BATCH_SIZE).forEachIndexed { index, batch ->
                        if (!isActive) return@forEachIndexed
                        
                        Log.d(TAG, "📦 Processing batch ${index + 1} (${batch.size} songs)")
                        
                        // Fetch URLs for this batch in parallel
                        val streamUrls = fetchStreamUrlsBatch(batch)
                        
                        if (streamUrls.isNotEmpty()) {
                            val validSongs = batch.filter { streamUrls.containsKey(it.id) }
                            if (validSongs.isNotEmpty()) {
                                // Add to queue immediately
                                playerServiceConnection.addToQueue(validSongs, streamUrls)
                                
                                // Update repository tracking
                                validSongs.forEach { queueRepository.markSongAsQueued(it.id) }
                                
                                Log.d(TAG, "➕ Added batch of ${validSongs.size} to queue")
                            }
                        }
                        
                        // Small delay between batches to not overwhelm the player
                        if (index < songs.size / RECOMMENDATION_BATCH_SIZE - 1) {
                            delay(200)
                        }
                    }
                    
                }.onFailure { e ->
                    Log.e(TAG, "❌ Failed to fetch recommendations", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in recommendation fetch", e)
            } finally {
                isRecommendationFetching = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Fetch stream URLs for multiple songs in parallel
     */
    private suspend fun fetchStreamUrlsBatch(songs: List<Song>): Map<String, String> = 
        withContext(Dispatchers.IO) {
            val urlMap = mutableMapOf<String, String>()
            
            val jobs = songs.map { song ->
                async {
                    try {
                        songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                            .getOrNull()
                            ?.let { url -> song.id to url }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get URL for ${song.id}", e)
                        null
                    }
                }
            }
            
            jobs.awaitAll().filterNotNull().forEach { (id, url) ->
                urlMap[id] = url
            }
            
            urlMap
        }

    /**
     * Clear error message
     */
    fun clearError() {
        _error.value = null
        playerServiceConnection.clearError()
    }
    
    /**
     * Force refresh recommendations (useful for manual trigger)
     */
    fun refreshRecommendations() {
        currentSong.value?.let { song ->
            lastRecommendationSongId = null // Reset to force new fetch
            fetchAndAddRecommendations(song.id)
        }
    }
    
    /**
     * Get queue stats for debugging
     */
    fun getQueueStats(): String {
        val (queued, played, repoQueue) = queueRepository.getTrackingStats()
        val playerQueue = queue.value.size
        val remaining = playerServiceConnection.getRemainingQueueSize()
        return "Player: $playerQueue, Repository: $repoQueue, Tracked: $queued, Played: $played, Remaining: $remaining"
    }

    override fun onCleared() {
        super.onCleared()
        recommendationJob?.cancel()
        // Don't disconnect - service should persist
    }
}
