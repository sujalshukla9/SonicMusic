package com.sonicmusic.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.downloadmanager.SongDownloadManager
import com.sonicmusic.app.data.repository.QueueRepositoryImpl
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository

import com.sonicmusic.app.domain.usecase.SleepTimerManager
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
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
    private val songDownloadManager: SongDownloadManager,
    private val historyRepository: HistoryRepository,
    private val playlistRepository: PlaylistRepository,
    private val queueRepository: QueueRepositoryImpl,
    private val settingsRepository: SettingsRepository,
    val sleepTimerManager: SleepTimerManager
) : ViewModel() {

    companion object {
        private const val TAG = "SonicPlayer"
        private const val MAX_URL_RETRY_ATTEMPTS = 3
        private const val URL_RETRY_DELAY_MS = 1500L
        private const val RECOMMENDATION_BATCH_SIZE = 5 // Increased for faster population
        private const val RECOMMENDATION_FETCH_LIMIT = 15
        private const val SAME_SONG_RETRY_COOLDOWN_MS = 12000L
        // History dedup by song ID only (StateFlow emits same song twice per transition)
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

    private val _isCurrentSongDownloaded = MutableStateFlow(false)
    val isCurrentSongDownloaded: StateFlow<Boolean> = _isCurrentSongDownloaded.asStateFlow()

    /** Download progress for the currently playing song (null = not downloading) */
    val currentSongDownloadProgress: StateFlow<com.sonicmusic.app.data.downloadmanager.DownloadProgress?> =
        songDownloadManager.activeDownloads
            .combine(currentSong) { downloads, song ->
                song?.let { downloads[it.id] }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var downloadCheckJob: Job? = null

    val fullPlayerStyle: StateFlow<FullPlayerStyle> = settingsRepository.fullPlayerStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FullPlayerStyle.NORMAL)

    val dynamicColorsEnabled: StateFlow<Boolean> = settingsRepository.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val dynamicColorIntensity: StateFlow<Int> = settingsRepository.dynamicColorIntensity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 85)

    val albumArtBlurEnabled: StateFlow<Boolean> = settingsRepository.albumArtBlur
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blacklistedSongIds: StateFlow<Set<String>> = settingsRepository.blacklistedSongIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Track active recommendation fetch to prevent duplicates
    private var recommendationJob: Job? = null
    private var isRecommendationFetching = false
    private var lastRecommendationSongId: String? = null
    private var lastRecommendationRequestAt: Long = 0L
    private var lastHistorySongId: String? = null
    /** Timestamp (System.currentTimeMillis) when the current song started playing. */
    private var currentSongStartTimeMs: Long = 0L

    /** Limits concurrent stream URL fetch requests to avoid socket exhaustion. */
    private val urlFetchSemaphore = Semaphore(3)

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    init {
        // Connect to PlaybackService
        playerServiceConnection.connect()

        // Keep infinite recommendations aligned with setting.
        viewModelScope.launch {
            settingsRepository.autoQueueSimilar.collect { enabled ->
                _infiniteModeEnabled.value = enabled
                queueRepository.setInfiniteMode(enabled)
                Log.d(TAG, "♾️ Auto queue similar from settings: $enabled")
            }
        }
        
        // Set up callback for infinite queue (ViTune-style)
        playerServiceConnection.setOnQueueNeedsMoreSongs { songId ->

            if (_infiniteModeEnabled.value && !isRecommendationFetching) {

                fetchAndAddRecommendations(songId)
            } else {

            }
        }
        
        // Set up callback for URL expiration (retry with fresh URL)
        playerServiceConnection.setOnStreamUrlExpired { songId ->
            Log.d(TAG, "🔄 Stream URL expired for: $songId")
            retryWithFreshUrl(songId)
        }
        
        // Observe current song for like status and played tracking
        var likeObserverJob: Job? = null
        viewModelScope.launch {
            currentSong.collect { song ->
                song?.let {
                    try {
                        if (lastHistorySongId != it.id) {
                            // Record history for the PREVIOUS song with actual play duration
                            val previousSongId = lastHistorySongId
                            if (previousSongId != null && currentSongStartTimeMs > 0L) {
                                val elapsedMs = System.currentTimeMillis() - currentSongStartTimeMs
                                val playDurationSecs = (elapsedMs / 1000).toInt().coerceAtLeast(0)
                                // Find the previous song in the queue for metadata
                                val prevSong = queue.value.find { q -> q.id == previousSongId }
                                if (prevSong != null) {
                                    val totalDurationSecs = prevSong.duration
                                    val completed = totalDurationSecs > 0 &&
                                            playDurationSecs.toFloat() / totalDurationSecs >= 0.8f
                                    historyRepository.recordPlayback(
                                        prevSong,
                                        playDuration = playDurationSecs,
                                        completed = completed,
                                        totalDuration = totalDurationSecs
                                    )
                                }
                            }

                            // Start tracking the NEW song
                            lastHistorySongId = it.id
                            currentSongStartTimeMs = System.currentTimeMillis()
                        }

                        // Cancel previous observer and start a new one for the current song
                        likeObserverJob?.cancel()
                        likeObserverJob = launch {
                            songRepository.observeIsLiked(it.id).collect { isLiked ->
                                _isLiked.value = isLiked
                            }
                        }

                        // Check download state for current song
                        downloadCheckJob?.cancel()
                        downloadCheckJob = launch {
                            _isCurrentSongDownloaded.value = songDownloadManager.isDownloaded(it.id)
                        }
                        
                        // Mark only the IMMEDIATELY preceding song as played (O(1)).
                        // Previous approach iterated entire queue prefix which was O(n).
                        val currentIndex = currentQueueIndex.value
                        val currentQueue = queue.value
                        if (currentIndex > 0 && currentIndex <= currentQueue.size) {
                            val previouslyPlayedSong = currentQueue.getOrNull(currentIndex - 1)
                            previouslyPlayedSong?.let { playedSong ->
                                try {
                                    queueRepository.markSongAsPlayed(playedSong.id)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to mark song as played: ${playedSong.id}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in current song observer", e)
                    }
                }
            }
        }
        
        // Sync queue state + current index with repository (single combined collector)
        viewModelScope.launch {
            combine(queue, currentQueueIndex) { songs, index -> songs to index }
                .collect { (songs, index) ->
                    try {
                        queueRepository.syncQueueState(songs, index)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing queue state", e)
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
            
            // Immediate UI feedback
            playerServiceConnection.preparePlayback(song)

            // Clear and reset queue state ONCE before retry loop
            queueRepository.clearQueue()
            lastRecommendationSongId = null
            lastRecommendationRequestAt = 0L

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

                val safeStartIndex = startIndex.coerceIn(0, songs.lastIndex)

                // Reset queue tracking before starting a new playback context.
                queueRepository.clearQueue()
                lastRecommendationSongId = null
                lastRecommendationRequestAt = 0L
                songs.forEach { queueRepository.markSongAsQueued(it.id) }

                playerServiceConnection.playSongsLazy(
                    songs = songs,
                    startIndex = safeStartIndex,
                    songRepository = songRepository
                )

                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing queue", e)
                _error.value = "Error: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * Add a song to the queue
     * ViTune-style: Only add if it's valid music content
     */
    fun addToQueue(song: Song) {
        viewModelScope.launch {
            try {
                if (blacklistedSongIds.value.contains(song.id)) {
                    _error.value = "\"${song.title}\" is blacklisted"
                    return@launch
                }

                // Filter non-music content
                if (!song.isValidMusicContent()) {
                    Log.d(TAG, "🎬 Cannot add non-music content to queue: ${song.title} (${song.contentType})")
                    _error.value = "Cannot add ${song.contentType.name.lowercase().replace('_', ' ')} to queue"
                    return@launch
                }

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

    /**
     * Filter queue to show only music songs
     * ViTune-style: Remove videos, podcasts, live streams, etc.
     */
    fun filterQueueToMusicOnly() {
        viewModelScope.launch {
            val removedCount = queueRepository.removeNonMusicContent()
            if (removedCount > 0) {
                Log.d(TAG, "🎬 Filtered $removedCount non-music items from queue")
                // Queue will auto-update through StateFlow from repository
            }
        }
    }

    /**
     * Check if current queue has non-music content
     */
    fun hasNonMusicContent(): Boolean {
        return queueRepository.hasNonMusicContent()
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
        val removedSongId = queue.value.getOrNull(index)?.id
        playerServiceConnection.removeFromQueue(index)
        // Update repository tracking
        removedSongId?.let { songId ->
            queueRepository.removeFromTracking(songId)
        }
    }
    
    fun clearQueue() {
        viewModelScope.launch {
            // Clear repository first to prevent stale sync from queue collector
            queueRepository.clearQueue()
            playerServiceConnection.clearQueue()
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

    // ═══════════════════════════════════════════════════════════════
    // AUDIO ENGINE & EQUALIZER
    // ═══════════════════════════════════════════════════════════════

    // Expose Audio Engine State
    val audioEngineState = playerServiceConnection.audioEngine.audioEngineState
    val currentStreamInfo = playerServiceConnection.audioEngine.currentStreamInfo
    val outputDeviceType = playerServiceConnection.audioEngine.outputDeviceType

    // Equalizer
    fun setEqualizerEnabled(enabled: Boolean) = playerServiceConnection.audioEngine.setEqualizer(enabled)
    fun setEqPreset(preset: String) = playerServiceConnection.audioEngine.setEqualizer(true, preset)
    fun setEqBandLevel(band: Int, level: Short) = playerServiceConnection.audioEngine.setEqBandLevel(band, level)
    fun getEqBands() = playerServiceConnection.audioEngine.getEqBandInfo()
    fun getEqPresets() = playerServiceConnection.audioEngine.getEqPresets()

    // Bass Boost
    fun setBassBoost(enabled: Boolean, strength: Int) = playerServiceConnection.audioEngine.setBassBoost(enabled, strength)

    // Spatial Audio (Virtualizer)
    fun setSpatialAudio(enabled: Boolean, strength: Int) = playerServiceConnection.audioEngine.setSpatialAudio(enabled, strength)
    
    // Reverb
    fun setReverb(enabled: Boolean, preset: Int) = playerServiceConnection.audioEngine.setReverb(enabled, preset)
    fun getReverbPresets() = playerServiceConnection.audioEngine.getReverbPresets()

    // Sound Check
    fun setSoundCheck(enabled: Boolean) = playerServiceConnection.audioEngine.setSoundCheck(enabled)

    // Quality Tiers
    fun setWifiQuality(quality: StreamQuality) = playerServiceConnection.audioEngine.setWifiQualityTier(quality)
    fun setCellularQuality(quality: StreamQuality) = playerServiceConnection.audioEngine.setCellularQualityTier(quality)

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
                        Log.d(TAG, "💔 Unliked: ${song.title}")
                    } else {
                        songRepository.likeSong(song) // Pass full song object
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
        val newValue = !_infiniteModeEnabled.value
        viewModelScope.launch {
            settingsRepository.setAutoQueueSimilar(newValue)
            _infiniteModeEnabled.value = newValue
            queueRepository.setInfiniteMode(newValue)
        }
        Log.d(TAG, "♾️ Infinite mode: $newValue")
    }
    
    /**
     * Enable/disable infinite mode explicitly
     */
    fun setInfiniteMode(enabled: Boolean) {
        _infiniteModeEnabled.value = enabled
        viewModelScope.launch {
            settingsRepository.setAutoQueueSimilar(enabled)
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


        val now = System.currentTimeMillis()
        // Prevent immediate duplicate fetches for same seed song, but allow periodic refill.
        if (currentSongId == lastRecommendationSongId &&
            (now - lastRecommendationRequestAt) < SAME_SONG_RETRY_COOLDOWN_MS
        ) {

            return
        }
        
        // Cancel any existing job
        recommendationJob?.cancel()
        
        recommendationJob = viewModelScope.launch {
            if (isRecommendationFetching) {

                return@launch
            }
            
            isRecommendationFetching = true
            lastRecommendationSongId = currentSongId
            lastRecommendationRequestAt = now
            
            try {
                Log.d(TAG, "🔄 Fetching recommendations for: $currentSongId")

                val primarySongs = queueRepository
                    .getRelatedSongs(currentSongId, RECOMMENDATION_FETCH_LIMIT)
                    .getOrElse { error ->
                        Log.e(TAG, "❌ Failed to fetch related songs", error)
                        emptyList()
                    }

                val candidateSongs = if (primarySongs.isNotEmpty()) {
                    primarySongs
                } else {
                    Log.d(TAG, "⚠️ Related queue empty, trying mix fallback")
                    queueRepository.getSongMix(currentSongId, RECOMMENDATION_FETCH_LIMIT).getOrDefault(emptyList())
                }

                val blacklist = blacklistedSongIds.value
                val filteredCandidates = candidateSongs.filterNot { blacklist.contains(it.id) }

                if (filteredCandidates.isEmpty()) {
                    Log.d(TAG, "⚠️ No recommendations found")
                    // Allow retry for the same seed song on the next queue check.
                    lastRecommendationSongId = null
                    lastRecommendationRequestAt = 0L
                    return@launch
                }

                Log.d(TAG, "✅ Got ${filteredCandidates.size} recommendations")

                var totalAdded = 0

                filteredCandidates.chunked(RECOMMENDATION_BATCH_SIZE).forEachIndexed { index, batch ->
                    if (!currentCoroutineContext().isActive) return@forEachIndexed



                    val streamUrls = fetchStreamUrlsBatch(batch)
                    if (streamUrls.isEmpty()) return@forEachIndexed

                    val validSongs = batch.filter { streamUrls.containsKey(it.id) }
                    if (validSongs.isEmpty()) return@forEachIndexed

                    playerServiceConnection.addToQueue(validSongs, streamUrls)
                    queueRepository.addToQueue(validSongs)
                    totalAdded += validSongs.size

                    Log.d(
                        TAG,
                        "➕ Added batch of ${validSongs.size} (total added: $totalAdded, player queue: ${playerServiceConnection.queue.value.size})"
                    )

                    if (index < filteredCandidates.size / RECOMMENDATION_BATCH_SIZE - 1) {
                        delay(100)
                    }
                }

                if (totalAdded == 0) {
                    // URL extraction failed for all candidates; retry later.
                    lastRecommendationSongId = null
                    lastRecommendationRequestAt = 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in recommendation fetch", e)
                lastRecommendationSongId = null
                lastRecommendationRequestAt = 0L
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
                    urlFetchSemaphore.withPermit {
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
            lastRecommendationRequestAt = 0L
            fetchAndAddRecommendations(song.id)
        }
    }

    fun downloadCurrentSongOffline() {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            runCatching {
                songRepository.cacheSong(song)

                if (songDownloadManager.isDownloaded(song.id)) {
                    _error.value = "Already downloaded"
                    return@runCatching
                }

                val downloadQuality = settingsRepository.downloadQuality.first()
                songDownloadManager.downloadSong(song, downloadQuality).getOrThrow()
            }.onSuccess {
                _isCurrentSongDownloaded.value = true
                _error.value = "Downloaded for offline"
                Log.d(TAG, "⬇️ Downloaded for offline: ${song.title}")
            }.onFailure { exception ->
                _error.value = "Download failed: ${exception.message}"
                Log.e(TAG, "Failed downloading song for offline", exception)
            }
        }
    }

    fun deleteCurrentSongDownload() {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            val deleted = songDownloadManager.deleteDownload(song.id)
            if (deleted) {
                _isCurrentSongDownloaded.value = false
                _error.value = "Download removed"
                Log.d(TAG, "🗑️ Removed download: ${song.title}")
            }
        }
    }

    fun addCurrentSongToPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            runCatching {
                songRepository.cacheSong(song)
                playlistRepository.addSongToPlaylist(playlistId, song.id).getOrThrow()
            }.onSuccess {
                _error.value = "Added to playlist"
            }.onFailure { error ->
                _error.value = "Failed to add to playlist: ${error.message}"
                Log.e(TAG, "Failed adding current song to playlist", error)
            }
        }
    }

    fun createPlaylistAndAddCurrentSong(name: String, description: String? = null) {
        viewModelScope.launch {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) return@launch

            val song = currentSong.value ?: return@launch
            runCatching {
                songRepository.cacheSong(song)
                val playlistId = playlistRepository.createPlaylist(trimmedName, description).getOrThrow()
                playlistRepository.addSongToPlaylist(playlistId, song.id).getOrThrow()
            }.onSuccess {
                _error.value = "Playlist created and song added"
            }.onFailure { error ->
                _error.value = "Failed to create playlist: ${error.message}"
                Log.e(TAG, "Failed creating playlist from player", error)
            }
        }
    }

    fun addCurrentSongToBlacklist() {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            val isAlreadyBlacklisted = blacklistedSongIds.value.contains(song.id)
            if (isAlreadyBlacklisted) {
                _error.value = "\"${song.title}\" is already blacklisted"
                return@launch
            }

            runCatching {
                settingsRepository.addBlacklistedSongId(song.id)
            }.onSuccess {
                val index = currentQueueIndex.value
                if (index in queue.value.indices) {
                    removeFromQueue(index)
                }
                _error.value = "Added to blacklist"
            }.onFailure { error ->
                _error.value = "Failed to blacklist song: ${error.message}"
                Log.e(TAG, "Failed blacklisting song", error)
            }
        }
    }
    
    /**
     * Add a song to play next (immediate queue position after current)
     * ViTune-style queue management
     */
    fun addToPlayNext(song: Song) {
        viewModelScope.launch {
            try {
                // Check if already in queue
                if (queueRepository.isSongQueued(song.id)) {
                    Log.d(TAG, "⚠️ Song already in queue, moving to play next: ${song.title}")
                    // Find and remove existing position
                    val currentQueue = queue.value
                    val existingIndex = currentQueue.indexOfFirst { it.id == song.id }
                    if (existingIndex != -1) {
                        playerServiceConnection.removeFromQueue(existingIndex)
                        queueRepository.removeFromTracking(song.id)
                    }
                }

                // Get stream URL
                songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        val insertIndex = (currentQueueIndex.value + 1).coerceAtLeast(0)

                        // Capture queue size BEFORE adding so we know the appended index.
                        val queueSizeBefore = queue.value.size

                        // Add then move right after current song.
                        playerServiceConnection.addToQueue(song, streamUrl)
                        queueRepository.markSongAsQueued(song.id)

                        // The new song was appended at queueSizeBefore (0-indexed).
                        val appendedIndex = queueSizeBefore
                        if (appendedIndex > insertIndex) {
                            playerServiceConnection.reorderQueue(appendedIndex, insertIndex)
                            queueRepository.moveSong(appendedIndex, insertIndex)
                        }

                        Log.d(TAG, "⏭️ Added to play next: ${song.title}")
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "Failed to add to play next", exception)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding song to play next", e)
            }
        }
    }

    /**
     * Move a song in the queue
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playerServiceConnection.reorderQueue(fromIndex, toIndex)
        queueRepository.moveSong(fromIndex, toIndex)
        Log.d(TAG, "🔀 Moved queue item from $fromIndex to $toIndex")
    }

    /**
     * Get upcoming songs (remaining in queue)
     */
    fun getUpcomingSongs(): List<Song> {
        return queueRepository.getUpcomingSongs()
    }

    /**
     * Get queue stats for debugging
     */
    fun getQueueStats(): String {
        val (queued, played, repoQueue) = queueRepository.getTrackingStats()
        val playerQueue = queue.value.size
        val remaining = queueRepository.getRemainingCount()
        return "Player: $playerQueue, Repository: $repoQueue, Tracked: $queued, Played: $played, Remaining: $remaining"
    }

    // ═══════════════════════════════════════════════════════════════
    // PERSISTENT QUEUE - ViTune Style
    // ═══════════════════════════════════════════════════════════════

    /**
     * Save current queue state for persistence across app restarts
     */
    fun saveQueueState() {
        viewModelScope.launch {
            try {
                queueRepository.saveQueueState()
                Log.d(TAG, "💾 Queue state saved")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save queue state", e)
            }
        }
    }

    /**
     * Restore queue state from persistent storage
     * Returns true if queue was restored
     */
    suspend fun restoreQueueState(): Boolean {
        return try {
            val restored = queueRepository.restoreQueueState()
            if (restored) {
                Log.d(TAG, "📂 Queue state restored successfully")
                // Sync with player service connection
                val songs = queueRepository.queue.value
                val index = queueRepository.currentIndex.value
                if (songs.isNotEmpty()) {
                    Log.d(TAG, "📂 Restoring ${songs.size} songs at index $index to player")
                    // Re-feed songs to the player via lazy loading (URLs fetched on demand)
                    playerServiceConnection.playSongsLazy(
                        songs = songs,
                        startIndex = index.coerceIn(0, songs.lastIndex),
                        songRepository = songRepository
                    )
                }
            }
            restored
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore queue state", e)
            false
        }
    }

    /**
     * Check if there's a saved queue to restore
     */
    suspend fun hasSavedQueue(): Boolean {
        return queueRepository.hasSavedQueue()
    }

    /**
     * Clear saved queue state
     */
    fun clearSavedQueue() {
        viewModelScope.launch {
            try {
                queueRepository.clearSavedQueue()
                Log.d(TAG, "🗑️ Saved queue cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear saved queue", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recommendationJob?.cancel()
        // Save queue state before clearing
        saveQueueState()
        // Don't disconnect - service should persist
    }
}
