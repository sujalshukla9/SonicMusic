package com.sonicmusic.app.features.player.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.features.downloads.data.SongDownloadManager
import com.sonicmusic.app.features.queue.data.QueueRepositoryImpl
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.domain.model.PlayerControlStyle
import com.sonicmusic.app.domain.model.LyricsResult
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.LyricsRepository
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository

import com.sonicmusic.app.features.queue.domain.SleepTimerManager
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.time.Duration
import com.sonicmusic.app.features.player.presentation.PlayerUiState
import com.sonicmusic.app.features.player.presentation.PlayerActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import androidx.media3.common.Player
import com.sonicmusic.app.features.queue.manager.AlbumLinkResolver
import com.sonicmusic.app.features.queue.manager.toQueueSong

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
 * - Lyrics fetching
 */

/**
 * UI state for lyrics display on the full player.
 */
sealed interface LyricsUiState {
    /** Lyrics haven't been requested yet. */
    data object Idle : LyricsUiState
    /** Lyrics are being fetched. */
    data object Loading : LyricsUiState
    /** Plain text lyrics loaded successfully. */
    data class LoadedPlain(
        val text: String,
        val originalText: String? = null,
        val source: String? = null
    ) : LyricsUiState
    /** Synchronized lyrics loaded successfully. */
    data class LoadedSynced(
        val lines: List<com.sonicmusic.app.domain.model.LyricLine>,
        val source: String? = null
    ) : LyricsUiState
    /** No lyrics available for this song. */
    data object Unavailable : LyricsUiState
    /** Error fetching lyrics. */
    data class Error(val message: String) : LyricsUiState
}

/**
 * UI state for album link resolution on the full player.
 */
sealed interface AlbumLinkState {
    data object Loading : AlbumLinkState
    data class Available(val browseId: String, val name: String) : AlbumLinkState
    data object Unavailable : AlbumLinkState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
    private val songDownloadManager: SongDownloadManager,
    private val historyRepository: HistoryRepository,
    private val playlistRepository: PlaylistRepository,
    private val queueRepository: QueueRepositoryImpl,
    private val settingsRepository: SettingsRepository,
    private val lyricsRepository: LyricsRepository,
    val sleepTimerManager: SleepTimerManager,
    private val audioCapabilityDetector: com.sonicmusic.app.features.player.audio.AudioCapabilityDetector,
    private val albumLinkResolver: AlbumLinkResolver,
    private val recommendationEngine: com.sonicmusic.app.features.recommendations.engine.RecommendationEngine
) : ViewModel(), PlayerActions {

    companion object {
        private const val TAG = "SonicPlayer"
        private const val RECOMMENDATION_BATCH_SIZE = 20 // Larger batches to load queue fast
        private const val TARGET_QUEUE_SIZE = 20
        private const val SAME_SONG_RETRY_COOLDOWN_MS = 2000L // Fast retry for queue refill
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
    val lyricsPosition = playerServiceConnection.observeLyricsPosition()
    val duration: StateFlow<Long> = playerServiceConnection.duration
    val queue: StateFlow<List<Song>> = playerServiceConnection.queue
    val currentQueueIndex: StateFlow<Int> = playerServiceConnection.currentQueueIndex
    val repeatMode: StateFlow<Int> = playerServiceConnection.repeatMode
    val shuffleEnabled: StateFlow<Boolean> = playerServiceConnection.shuffleEnabled
    val playbackError: StateFlow<String?> = playerServiceConnection.playbackError
    val playbackSpeed: StateFlow<Float> = playerServiceConnection.playbackSpeed
    val isLoadingMore: StateFlow<Boolean> = playerServiceConnection.isLoadingMore
    val currentStreamInfo: StateFlow<AudioStreamInfo?> = playerServiceConnection.currentStreamInfo
    val audioQualityUiState: StateFlow<com.sonicmusic.app.features.player.audio.AudioQualityUiState?> = playerServiceConnection.audioQualityUiState

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

    private val _downloadedFileSizeBytes = MutableStateFlow<Long?>(null)
    val downloadedFileSizeBytes: StateFlow<Long?> = _downloadedFileSizeBytes.asStateFlow()

    /** Download progress for the currently playing song (null = not downloading) */
    val currentSongDownloadProgress: StateFlow<com.sonicmusic.app.features.downloads.data.DownloadProgress?> =
        songDownloadManager.activeDownloads
            .combine(currentSong) { downloads, song ->
                song?.let { downloads[it.id] }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var downloadCheckJob: Job? = null

    val fullPlayerStyle: StateFlow<FullPlayerStyle> = settingsRepository.fullPlayerStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FullPlayerStyle.WAVY)

    val dynamicColorsEnabled: StateFlow<Boolean> = settingsRepository.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val dynamicColorIntensity: StateFlow<Int> = settingsRepository.dynamicColorIntensity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 85)

    val albumArtBlurEnabled: StateFlow<Boolean> = settingsRepository.albumArtBlur
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val thumbnailShape: StateFlow<ThumbnailShape> = settingsRepository.thumbnailShape
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThumbnailShape.ROUNDED_SQUARE)

    val playerControlStyle: StateFlow<PlayerControlStyle> = settingsRepository.playerControlStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerControlStyle.MODERN)

    // Seek preview position flow to support live dragging
    private val _seekPreviewPosition = MutableStateFlow<Long?>(null)
    val seekPreviewPosition: StateFlow<Long?> = _seekPreviewPosition.asStateFlow()

    val playerUiState: StateFlow<PlayerUiState> = combine(
        currentSong,
        isPlaying,
        duration,
        queue,
        currentQueueIndex,
        isBuffering,
        repeatMode,
        infiniteModeEnabled
    ) { array ->
        val song = array[0] as? Song
        val playing = array[1] as Boolean
        val dur = array[2] as Long
        @Suppress("UNCHECKED_CAST")
        val q = array[3] as List<Song>
        val idx = array[4] as Int
        val buffering = array[5] as Boolean
        val repeat = array[6] as Int
        val infinite = array[7] as Boolean

        val canPrev = idx > 0 || repeat == Player.REPEAT_MODE_ALL || repeat == Player.REPEAT_MODE_ONE
        val canNext = idx < q.size - 1 || repeat == Player.REPEAT_MODE_ALL || repeat == Player.REPEAT_MODE_ONE || infinite

        PlayerUiState(
            title = song?.title.orEmpty(),
            artist = song?.artist.orEmpty(),
            albumArtUrl = song?.thumbnailUrl.orEmpty(),
            isPlaying = playing,
            currentPositionMs = 0L,
            durationMs = dur,
            canSkipNext = canNext,
            canSkipPrevious = canPrev,
            isBuffering = buffering
        )
    }
        .distinctUntilChanged()
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState(
            title = "",
            artist = "",
            albumArtUrl = "",
            isPlaying = false,
            currentPositionMs = 0L,
            durationMs = 0L,
            canSkipNext = false,
            canSkipPrevious = false,
            isBuffering = false
        )
    )

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blacklistedSongIds: StateFlow<Set<String>> = settingsRepository.blacklistedSongIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Track active recommendation fetch to prevent duplicates
    private var recommendationJob: Job? = null
    // ── OPTIMIZATION 9: Mutex replaces boolean flag ──
    private val recommendationMutex = Mutex()
    private var lastRecommendationSongId: String? = null
    private var lastRecommendationRequestAt: Long = 0L
    private var lastHistorySongId: String? = null
    /** Timestamp (System.currentTimeMillis) when the current song started playing. */
    private var currentSongStartTimeMs: Long = 0L

    // ═══════════════════════════════════════════════════════════════
    // LYRICS STATE
    // ═══════════════════════════════════════════════════════════════

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private val _showOriginalScript = MutableStateFlow(false)
    val showOriginalScript: StateFlow<Boolean> = _showOriginalScript.asStateFlow()

    private var lyricsFetchJob: Job? = null
    private var lyricsSongId: String? = null

    private val _albumLinkState = MutableStateFlow<AlbumLinkState>(AlbumLinkState.Unavailable)
    val albumLinkState: StateFlow<AlbumLinkState> = _albumLinkState.asStateFlow()

    private var albumResolutionJob: Job? = null

    /** Limits concurrent stream URL fetch requests to avoid socket exhaustion. */
    private val urlFetchSemaphore = Semaphore(3)

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    fun connectToPlayback() {
        playerServiceConnection.connect()
    }

    init {
        // Forward download manager error events (e.g. storage full) to the UI error state.
        viewModelScope.launch {
            songDownloadManager.downloadErrors.collect { message ->
                _error.value = message
            }
        }

        // Keep infinite recommendations aligned with setting.
        viewModelScope.launch {
            settingsRepository.autoQueueSimilar.collect { enabled ->
                _infiniteModeEnabled.value = enabled
                playerServiceConnection.setInfiniteMode(enabled)
                queueRepository.setInfiniteMode(enabled)
                Log.d(TAG, "Auto queue similar from settings: $enabled")
            }
        }
        
        // Observe current song for like status and played tracking
        var likeObserverJob: Job? = null
        viewModelScope.launch {
            currentSong.collect { song ->
                if (lyricsSongId != song?.id) clearLyricsState()
                downloadCheckJob?.cancel()
                _isCurrentSongDownloaded.value = false
                _downloadedFileSizeBytes.value = null
                if (song != null) {
                    try {
                        // History tracking moved to PlaybackService AnalyticsListener


                        // Cancel previous observer and start a new one for the current song
                        likeObserverJob?.cancel()
                        likeObserverJob = launch {
                            songRepository.observeIsLiked(song.id).collect { isLiked ->
                                _isLiked.value = isLiked
                            }
                        }

                        downloadCheckJob = launch {
                            songDownloadManager.observeDownloadedFileSize(song.id).collect { size ->
                                _downloadedFileSizeBytes.value = size
                                _isCurrentSongDownloaded.value = size != null
                            }
                        }

                        // Resolve album info
                        albumResolutionJob?.cancel()
                        _albumLinkState.value = AlbumLinkState.Loading
                        albumResolutionJob = launch {
                            val state = albumLinkResolver.resolveAlbumInfo(song.toQueueSong())
                            _albumLinkState.value = state
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
                } else {
                    // Song cleared — reset lyrics and album link state
                    _albumLinkState.value = AlbumLinkState.Unavailable
                    albumResolutionJob?.cancel()
                }
            }
        }
        
        // ── OPTIMIZATION 8: Debounced queue sync ──────────────────
        // conflate() drops intermediate emissions during batch hydration
        // so syncQueueState() fires once instead of 4-8 times.
        viewModelScope.launch {
            combine(queue, currentQueueIndex) { songs, index -> songs to index }
                .conflate()
                .collect { (songs, index) ->
                    try {
                        queueRepository.syncQueueState(songs, index)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing queue state", e)
                    }
                }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK OPERATIONS - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Play a song - YouTube Music Style (instant playback + Up Next radio mix)
     *
     * @param song The song to play
     */
    fun playSong(song: Song) {
        _isLoading.value = false
        _error.value = null
        playerServiceConnection.playSongWithRadio(song)
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
                Log.d(TAG, "Playing queue: ${songs.size} songs, starting at $startIndex")

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
                Log.e(TAG, "Error playing queue", e)
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
                    Log.d(TAG, "Cannot add non-music content to queue: ${song.title} (${song.contentType})")
                    _error.value = "Cannot add ${song.contentType.name.lowercase().replace('_', ' ')} to queue"
                    return@launch
                }

                // Check if already in queue (ViTune-style duplicate prevention)
                if (queueRepository.isSongQueued(song.id)) {
                    Log.d(TAG, "Song already in queue: ${song.title}")
                    return@launch
                }

                playerServiceConnection.addSongToQueue(song)
                queueRepository.markSongAsQueued(song.id)
                Log.d(TAG, "Added to queue: ${song.title}")
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
                Log.d(TAG, "Filtered $removedCount non-music items from queue")
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

    /**
     * Insert song to play next (YouTube Music style)
     */
    fun playNext(song: Song) {
        addToPlayNext(song)
    }

    /**
     * Add song to play immediately after the current song
     */
    fun addToPlayNext(song: Song) {
        viewModelScope.launch {
            try {
                if (blacklistedSongIds.value.contains(song.id)) {
                    _error.value = "\"${song.title}\" is blacklisted"
                    return@launch
                }

                if (!song.isValidMusicContent()) {
                    _error.value = "Cannot add ${song.contentType.name.lowercase().replace('_', ' ')} to queue"
                    return@launch
                }

                playerServiceConnection.insertPlayNext(song)
                queueRepository.markSongAsQueued(song.id)
                _error.value = "Playing next: ${song.title}"
            } catch (e: Exception) {
                Log.e(TAG, "Error in addToPlayNext", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═══════════════════════════════════════════════════════════════
    
    fun togglePlayPause() = playerServiceConnection.togglePlayPause()
    
    fun skipToNext() = playerServiceConnection.skipToNext()
    
    fun skipToPrevious() = playerServiceConnection.skipToPrevious()
    
    fun forceSkipToPrevious() = playerServiceConnection.forceSkipToPrevious()
    
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
                Log.d(TAG, "Sleep timer complete - stopping playback")
                stop()
            },
            onFade = { volumeMultiplier ->
                Log.d(TAG, "Fading volume: $volumeMultiplier")
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
    val outputDeviceType = playerServiceConnection.audioEngine.outputDeviceType

    // ── Audio Capability Badges (DTS, Hi-Res, Spatial) ──────────
    val audioCapability = audioCapabilityDetector.capability.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = com.sonicmusic.app.features.player.audio.AudioCapabilityDetector.AudioCapability()
    )

    // Equalizer
    fun setEqualizerEnabled(enabled: Boolean) = playerServiceConnection.audioEngine.setEqualizer(enabled)
    fun setEqPreset(preset: String) = playerServiceConnection.audioEngine.setEqualizer(true, preset)
    fun setEqBandLevel(band: Int, level: Short) = playerServiceConnection.audioEngine.setEqBandLevel(band, level)
    fun getEqBands() = playerServiceConnection.audioEngine.getEqBandInfo()
    fun getEqPresets() = playerServiceConnection.audioEngine.getEqPresets()

    // Spatial Audio (Virtualizer)
    val spatialAudioEnabled: StateFlow<Boolean> = playerServiceConnection.audioEngine.audioEngineState
        .map { it.spatialAudioEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val spatialStrength: StateFlow<Int> = playerServiceConnection.audioEngine.audioEngineState
        .map { it.spatialStrength }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.sonicmusic.app.features.player.audio.AudioEngine.MAX_VIRTUALIZER_STRENGTH)

    fun setSpatialAudio(enabled: Boolean, strength: Int = com.sonicmusic.app.features.player.audio.AudioEngine.MAX_VIRTUALIZER_STRENGTH) = playerServiceConnection.audioEngine.setSpatialAudio(enabled, strength)
    
    // Reverb
    fun setReverb(enabled: Boolean, preset: Int) = playerServiceConnection.audioEngine.setReverb(enabled, preset)
    fun getReverbPresets() = playerServiceConnection.audioEngine.getReverbPresets()

    // Sound Check

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
                        // Feed unlike signal into recommendation engine
                        recommendationEngine.onDislike(song)
                        Log.d(TAG, "Unliked: ${song.title}")
                    } else {
                        songRepository.likeSong(song) // Pass full song object
                        // Feed like signal into recommendation engine
                        recommendationEngine.onLike(song)
                        Log.d(TAG, "Liked: ${song.title}")
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
        setInfiniteMode(!_infiniteModeEnabled.value)
    }
    
    /**
     * Enable/disable infinite mode explicitly
     */
    fun setInfiniteMode(enabled: Boolean) {
        _infiniteModeEnabled.value = enabled
        playerServiceConnection.setInfiniteMode(enabled)
        viewModelScope.launch {
            settingsRepository.setAutoQueueSimilar(enabled)
            queueRepository.setInfiniteMode(enabled)
        }
        Log.d(TAG, "Infinite mode set to: $enabled")
    }

    /**
     * Fetch recommendations and add to queue - ViTune Style
     * Uses BATCH LOADING for instant perception of speed
     * Prevents duplicate fetches and handles edge cases
     */
    private fun fetchAndAddRecommendations(currentSongId: String, limit: Int = TARGET_QUEUE_SIZE) {


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
            // ── OPTIMIZATION 9: Mutex guard ──────────────────────────
            // tryLock prevents overlapping fetches; if another coroutine
            // already holds the mutex we skip (the running one will deliver).
            if (!recommendationMutex.tryLock()) {
                return@launch
            }
            
            lastRecommendationSongId = currentSongId
            lastRecommendationRequestAt = now
            
            try {
                Log.d(TAG, "Fetching recommendations for: $currentSongId")

                val primarySongs = queueRepository
                    .getRelatedSongs(currentSongId, limit)
                    .getOrElse { error ->
                        Log.e(TAG, "Failed to fetch related songs", error)
                        emptyList()
                    }

                val candidateSongs = if (primarySongs.isNotEmpty()) {
                    primarySongs
                } else {
                    Log.d(TAG, "Related queue empty, trying mix fallback")
                    queueRepository.getSongMix(currentSongId, limit).getOrDefault(emptyList())
                }

                val blacklist = blacklistedSongIds.value
                val filteredCandidates = candidateSongs.filterNot { blacklist.contains(it.id) }

                if (filteredCandidates.isEmpty()) {
                    Log.d(TAG, "No recommendations found")
                    // Allow retry for the same seed song on the next queue check.
                    lastRecommendationSongId = null
                    lastRecommendationRequestAt = 0L
                    return@launch
                }

                Log.d(TAG, "Got ${filteredCandidates.size} recommendations")

                var totalAdded = 0

                filteredCandidates.chunked(RECOMMENDATION_BATCH_SIZE).forEach { batch ->
                    if (!currentCoroutineContext().isActive) return@forEach

                    // ViTune-style lazy hydration: Push songs to playerServiceConnection immediately so UI updates 
                    // without waiting for stream extraction. Stream extraction runs in background.
                    playerServiceConnection.addSongsToQueueLazy(
                        songs = batch,
                        songRepository = songRepository,
                        isQueueAlreadyPopulated = false
                    )
                    queueRepository.addToQueue(batch)
                    totalAdded += batch.size

                    Log.d(
                        TAG,
                        "Enqueued background fetch for batch of ${batch.size} (total enqueued: $totalAdded)"
                    )

                    // No inter-batch delay — lazy hydration is parallel now
                }

                if (totalAdded == 0) {
                    // URL extraction failed for all candidates; retry later.
                    lastRecommendationSongId = null
                    lastRecommendationRequestAt = 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in recommendation fetch", e)
                lastRecommendationSongId = null
                lastRecommendationRequestAt = 0L
            } finally {
                recommendationMutex.unlock()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════

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
    fun startRadio() = playerServiceConnection.startRadioFromCurrentSong()

    fun refreshRecommendations() {
        playerServiceConnection.forcePrefetchRecommendations()
    }

    suspend fun resolveAlbumId(songId: String): String? {
        return try {
            songRepository.getSongById(songId).getOrNull()?.let { song ->
                song.albumBrowseId ?: song.albumId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving album ID for $songId", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LYRICS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch lyrics for the currently playing song.
     */
    fun fetchLyrics() {
        val song = currentSong.value
        if (song == null) {
            _lyricsState.value = LyricsUiState.Unavailable
            return
        }

        if (lyricsSongId == song.id && (_lyricsState.value is LyricsUiState.Loading ||
                _lyricsState.value is LyricsUiState.LoadedPlain || _lyricsState.value is LyricsUiState.LoadedSynced)
        ) return
        if (lyricsSongId != song.id) _showOriginalScript.value = false
        lyricsSongId = song.id
        lyricsFetchJob?.cancel()
        _lyricsState.value = LyricsUiState.Loading
        lyricsFetchJob = viewModelScope.launch {
            try {
                val result = lyricsRepository.getLyrics(song)
                if (!isActive || currentSong.value?.id != song.id) return@launch
                
                _lyricsState.value = when (result) {
                    is LyricsResult.FoundSynced -> LyricsUiState.LoadedSynced(result.lines, result.source)
                    is LyricsResult.Found -> LyricsUiState.LoadedPlain(result.text, result.originalText, result.source)
                    is LyricsResult.NotFound -> LyricsUiState.Unavailable
                    is LyricsResult.Error -> LyricsUiState.Error(result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentSong.value?.id != song.id) return@launch
                Log.e(TAG, "Error fetching lyrics", e)
                _lyricsState.value = LyricsUiState.Error("An error occurred")
            }
        }
    }

    /**
     * Reset lyrics state (e.g. when hiding overlay or song changes).
     */
    fun clearLyricsState() {
        lyricsFetchJob?.cancel()
        lyricsSongId = null
        _lyricsState.value = LyricsUiState.Idle
        _showOriginalScript.value = false
    }

    /**
     * Toggle between transliterated (Hinglish) and original script (Devanagari) display.
     */
    fun toggleLyricsLanguage() {
        _showOriginalScript.value = !_showOriginalScript.value
    }

    fun downloadCurrentSongOffline() {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            runCatching {
                songRepository.cacheSong(song)

                if (songDownloadManager.isDownloaded(song.id)) {
                    _error.value = "Already downloaded"
                    return@launch
                }

                val downloadQuality = settingsRepository.downloadQuality.first()
                songDownloadManager.downloadSong(song, downloadQuality)
            }.onSuccess {
                _error.value = "Download started"
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
                Log.d(TAG, "Removed download: ${song.title}")
            }
        }
    }

    fun addCurrentSongToPlaylist(playlistId: Long, force: Boolean = false) {
        viewModelScope.launch {
            val song = currentSong.value ?: return@launch
            runCatching {
                songRepository.cacheSong(song)
                playlistRepository.addSongToPlaylist(playlistId, song.id, force).getOrThrow()
            }.onSuccess {
                _error.value = "Added to playlist"
            }.onFailure { error ->
                _error.value = "Failed to add to playlist: ${error.message}"
                Log.e(TAG, "Failed adding current song to playlist", error)
            }
        }
    }

    suspend fun hasSongInPlaylist(playlistId: Long, songId: String): Boolean {
        return playlistRepository.hasSongInPlaylist(playlistId, songId)
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
     * Move a song in the queue
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playerServiceConnection.reorderQueue(fromIndex, toIndex)
        queueRepository.moveSong(fromIndex, toIndex)
        Log.d(TAG, "Moved queue item from $fromIndex to $toIndex")
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
                Log.d(TAG, "Queue state saved")
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
                Log.d(TAG, "Queue state restored successfully")
                // Sync with player service connection
                val songs = queueRepository.queue.value
                val index = queueRepository.currentIndex.value
                if (songs.isNotEmpty()) {
                    Log.d(TAG, "Restoring ${songs.size} songs at index $index to player")
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
                Log.d(TAG, "Saved queue cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear saved queue", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recommendationJob?.cancel()
        // Use NonCancellable so the save completes even though viewModelScope
        // is about to be cancelled when onCleared finishes.
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                queueRepository.saveQueueState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save queue on clear", e)
            }
        }
        // Don't disconnect - service should persist
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYER ACTIONS IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════

    override fun onPlayPause() {
        Log.d(TAG, "onPlayPause() — isPlaying was: ${isPlaying.value}")
        togglePlayPause()
    }

    override fun onSkipNext() {
        Log.d(TAG, "onSkipNext() — index: ${currentQueueIndex.value}, queueSize: ${queue.value.size}")
        skipToNext()
    }

    override fun onSkipPrevious() {
        Log.d(TAG, "onSkipPrevious() — index: ${currentQueueIndex.value}, queueSize: ${queue.value.size}")
        skipToPrevious()
    }

    override fun onSeekTo(positionMs: Long) {
        Log.d(TAG, "onSeekTo($positionMs) — duration: ${duration.value}")
        seekToPosition(positionMs)
        _seekPreviewPosition.value = null
    }

    override fun onSeekPreviewChange(positionMs: Long) {
        _seekPreviewPosition.value = positionMs
    }
}
