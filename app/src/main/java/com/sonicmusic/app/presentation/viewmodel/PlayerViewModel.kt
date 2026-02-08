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
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Player ViewModel
 * 
 * Coordinates between UI, PlayerServiceConnection, and repositories.
 * Handles:
 * - Stream URL fetching
 * - Playback control delegation
 * - Infinite queue recommendations
 * - Like/unlike functionality
 * - Sleep timer
 * - Error handling
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

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════
    
    init {
        // Connect to PlaybackService
        playerServiceConnection.connect()
        
        // Set up callback for infinite queue
        playerServiceConnection.setOnQueueNeedsMoreSongs { songId ->
            if (_infiniteModeEnabled.value && !isRecommendationFetching) {
                fetchAndAddRecommendations(songId)
            }
        }
        
        // Set up callback for URL expiration (retry with fresh URL)
        playerServiceConnection.setOnStreamUrlExpired { songId ->
            retryWithFreshUrl(songId)
        }
        
        // Observe current song for like status
        viewModelScope.launch {
            currentSong.collect { song ->
                song?.let {
                    _isLiked.value = songRepository.isLiked(it.id)
                }
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
    // PLAYBACK OPERATIONS
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Play a song - fetches stream URL and starts playback
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

                    // Fetch stream URL
                    val result = songRepository.getStreamUrl(song.id, StreamQuality.BEST)

                    result.onSuccess { streamUrl ->
                        Log.d(TAG, "✅ Got stream URL, starting playback")

                        // Start playback
                        playerServiceConnection.playSong(song, streamUrl)

                        // Update like status
                        viewModelScope.launch {
                            _isLiked.value = songRepository.isLiked(song.id)
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
     * Play a list of songs starting at a specific index
     * 
     * @param songs List of songs
     * @param startIndex Index to start playing from
     */
    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                Log.d(TAG, "▶️ Loading queue of ${songs.size} songs")
                
                // Fetch stream URLs in parallel
                val streamUrls = fetchStreamUrlsBatch(songs)
                
                if (streamUrls.isEmpty()) {
                    _error.value = "No playable songs found"
                    return@launch
                }
                
                // Filter songs that have valid URLs
                val playableSongs = songs.filter { streamUrls.containsKey(it.id) }
                val adjustedStartIndex = startIndex.coerceIn(0, playableSongs.size - 1)
                
                // Start playback
                playerServiceConnection.playWithQueue(playableSongs, streamUrls, adjustedStartIndex)
                
                // Update like status
                playableSongs.getOrNull(adjustedStartIndex)?.let { song ->
                    _isLiked.value = songRepository.isLiked(song.id)
                }
                
                // Prefetch recommendations
                if (_infiniteModeEnabled.value) {
                    playableSongs.lastOrNull()?.let { lastSong ->
                        fetchAndAddRecommendations(lastSong.id)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing queue", e)
                _error.value = "Error: ${e.message}"
            } finally {
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
                val result = songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                result.onSuccess { streamUrl ->
                    playerServiceConnection.addToQueue(song, streamUrl)
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
    
    fun removeFromQueue(index: Int) = playerServiceConnection.removeFromQueue(index)
    
    fun clearQueue() = playerServiceConnection.clearQueue()
    
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
                // TODO: Implement volume fade when audio API supports it
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
    // INFINITE QUEUE
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Toggle infinite queue mode
     */
    fun toggleInfiniteMode() {
        _infiniteModeEnabled.value = !_infiniteModeEnabled.value
        Log.d(TAG, "♾️ Infinite mode: ${_infiniteModeEnabled.value}")
    }

    /**
     * Fetch recommendations and add to queue
     */
    private fun fetchAndAddRecommendations(currentSongId: String) {
        // Cancel any existing job
        recommendationJob?.cancel()
        
        recommendationJob = viewModelScope.launch {
            if (isRecommendationFetching) return@launch
            isRecommendationFetching = true
            
            try {
                Log.d(TAG, "🔄 Fetching recommendations for: $currentSongId")
                
                // Get related songs
                val result = queueRepository.getRelatedSongs(currentSongId, 10)
                
                result.onSuccess { songs ->
                    if (songs.isEmpty()) {
                        Log.d(TAG, "⚠️ No recommendations found")
                        return@onSuccess
                    }
                    
                    Log.d(TAG, "✅ Got ${songs.size} recommendations")
                    
                    // Fetch stream URLs
                    val streamUrls = fetchStreamUrlsBatch(songs)
                    
                    if (streamUrls.isNotEmpty()) {
                        // Filter songs with valid URLs
                        val validSongs = songs.filter { streamUrls.containsKey(it.id) }
                        
                        // Add to queue
                        playerServiceConnection.addToQueue(validSongs, streamUrls)
                        Log.d(TAG, "➕ Added ${validSongs.size} recommendations to queue")
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

    override fun onCleared() {
        super.onCleared()
        recommendationJob?.cancel()
        // Don't disconnect - service should persist
    }
}