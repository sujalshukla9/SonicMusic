package com.sonicmusic.app.features.player.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import com.sonicmusic.app.core.network.NetworkMonitor
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.deviceLocalSongId
import com.sonicmusic.app.data.repository.SongSource
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.features.player.service.PlaybackService
import com.sonicmusic.app.features.player.audio.AudioEngine
import com.sonicmusic.app.features.player.audio.PlaybackDiagnostics
import com.sonicmusic.app.features.player.audio.PlaybackStatus
import com.sonicmusic.app.features.player.audio.AudioQualityUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import com.sonicmusic.app.domain.repository.SongCompletedEvent
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sonicmusic.app.features.queue.data.QueueRepositoryImpl

/**
 * Player Service Connection - ViTune Style
 * 
 * Manages connection between UI and PlaybackService.
 * Provides reactive StateFlows for all playback state.
 * 
 * ViTune-Style Features:
 * - Aggressive queue low detection (triggers early)
 * - Smart queue preloading
 * - Proper state sync with QueueRepository
 * - Callbacks for infinite queue support
 */
@Singleton
class PlayerServiceConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    val audioEngine: AudioEngine,
    val playbackDiagnostics: PlaybackDiagnostics,
    private val songSourceResolver: com.sonicmusic.app.data.repository.SongSourceResolver,
    private val playbackStateDataStore: com.sonicmusic.app.data.local.datastore.PlaybackStateDataStore,
    private val songRepository: dagger.Lazy<com.sonicmusic.app.domain.repository.SongRepository>,
    private val queueRepository: dagger.Lazy<com.sonicmusic.app.domain.repository.QueueRepository>,
    private val youTubeiService: dagger.Lazy<com.sonicmusic.app.data.remote.source.YouTubeiService>,
    private val settingsRepository: dagger.Lazy<com.sonicmusic.app.data.repository.SettingsRepository>,
    private val intelligentSourceResolver: dagger.Lazy<com.sonicmusic.app.data.repository.IntelligentSourceResolver>,
    private val playbackSessionStore: YouTubePlaybackSessionStore,
    private val networkMonitor: NetworkMonitor,
) {
    companion object {
        private const val TAG = "SonicConnection"
        private const val QUEUE_CHECK_COOLDOWN_MS = 1500L
        private const val PRELOAD_THRESHOLD = 7
        private const val POSITION_UPDATE_INTERVAL_MS = 500L
        private const val LYRICS_POSITION_INTERVAL_MS = 80L
        private const val QUEUE_PRELOAD_COUNT = 3

        // Configurable queue recommendation thresholds (Prompt Section 7)
        const val INITIAL_RECOMMENDATION_COUNT = 15 // 1 seed + 14 recommendations = 15 initial
        const val PREFETCH_THRESHOLD = 5            // Trigger background prefetch when remaining songs <= 5
        const val QUEUE_LOW_THRESHOLD = PREFETCH_THRESHOLD
        const val PREFETCH_BATCH_SIZE = 10          // Fetch 10 songs per batch
        const val MAXIMUM_QUEUE_RECOMMENDATIONS = 50 // Cap queue up to 50 songs (no starvation, no 20-song truncation)
        const val MAX_QUEUE_SIZE = MAXIMUM_QUEUE_RECOMMENDATIONS
        const val TARGET_RECOMMENDATIONS = INITIAL_RECOMMENDATION_COUNT - 1
    }

    private var activeRadioContinuationToken: String? = null
    private var activeRadioPlaylistId: String? = null
    private var pendingRadioTracks = emptyList<Song>()
    private val radioSeenIds = hashSetOf<String>()

    // MediaController for communication with PlaybackService
    private var mediaController: MediaController? = null
    fun getMediaController(): MediaController? = mediaController
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingPlayback: (() -> Unit)? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    private var queueCheckJob: Job? = null
    private var lazyPlaybackJob: Job? = null
    private var queueSelectionJob: Job? = null
    private var radioJob: Job? = null
    private var streamInfoResolutionJob: Job? = null
    private var queuePreloadJob: Job? = null
    private var preloadingIds = emptyList<String>()
    private var persistJob: Job? = null
    private var lastRadioSeedSongId: String? = null
    private val radioMutex = kotlinx.coroutines.sync.Mutex()
    private var unshuffledQueueSnapshot: List<Song>? = null
    private val artistJsonCache = android.util.LruCache<String, List<com.sonicmusic.app.domain.model.ArtistRef>>(128)

    init {
        // Immediately restore previously played song and queue on startup
        scope.launch {
            restoreSavedPlaybackState()
        }
    }

    private suspend fun restoreSavedPlaybackState() {
        if (_currentSong.value != null) return
        val saved = playbackStateDataStore.getSavedPlaybackStateOnce() ?: return
        val song = saved.song ?: return
        // A live selection may have arrived while DataStore was suspended.
        if (_currentSong.value != null || mediaController?.currentMediaItem != null) return

        Log.d(TAG, "Restoring saved playback state: ${song.title} at ${saved.positionMs}ms")
        _currentSong.value = song
        _currentPosition.value = saved.positionMs
        val dur = (song.duration * 1000L).takeIf { it > 0 } ?: 0L
        _duration.value = dur
        if (dur > 0) {
            _progress.value = (saved.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }
        val restoredQueue = if (saved.queue.isNotEmpty()) saved.queue else listOf(song)
        synchronized(songQueue) {
            if (songQueue.isEmpty()) {
                songQueue.clear()
                songQueue.addAll(restoredQueue)
            }
        }
        _queue.value = restoredQueue
        _currentQueueIndex.value = saved.queueIndex.coerceIn(0, restoredQueue.lastIndex)
        _repeatMode.value = saved.repeatMode
        _shuffleEnabled.value = saved.shuffleEnabled
    }

    private fun persistCurrentState(
        song: Song?,
        queue: List<Song>,
        queueIndex: Int,
        positionMs: Long,
        saveImmediately: Boolean = false
    ) {
        if (song == null) return
        val currentShuffle = _shuffleEnabled.value
        val currentRepeat = _repeatMode.value
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            if (!saveImmediately) delay(500)
            playbackStateDataStore.savePlaybackState(
                song = song,
                positionMs = positionMs,
                queue = queue,
                queueIndex = queueIndex,
                shuffleEnabled = currentShuffle,
                repeatMode = currentRepeat
            )
        }
    }

    private fun persistCurrentPosition(songId: String?, positionMs: Long) {
        val song = _currentSong.value ?: return
        if (songId != song.id || positionMs < 0) return
        if (persistJob?.isActive == true) {
            persistCurrentState(song, _queue.value, _currentQueueIndex.value, positionMs)
        } else {
            scope.launch { playbackStateDataStore.updatePosition(song.id, positionMs) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK STATE FLOWS
    // ═══════════════════════════════════════════════════════════════
    
    val audioQualityUiState: StateFlow<AudioQualityUiState?> = playbackDiagnostics.uiState

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(-1)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    
    // Track if queue needs more songs (for UI indication)
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _infiniteModeEnabled = MutableStateFlow(true)
    val infiniteModeEnabled: StateFlow<Boolean> = _infiniteModeEnabled.asStateFlow()

    fun setInfiniteMode(enabled: Boolean) {
        _infiniteModeEnabled.value = enabled
        Log.d(TAG, "PlayerServiceConnection infiniteMode set to: $enabled")
        if (enabled) {
            checkQueueAndRequestMore()
        }
    }

    // Current stream quality info directly from AudioEngine
    val currentStreamInfo: StateFlow<AudioStreamInfo?> = audioEngine.currentStreamInfo

    private val _songCompletedEvents = MutableSharedFlow<SongCompletedEvent>(extraBufferCapacity = 64)
    val songCompletedEvents: SharedFlow<SongCompletedEvent> = _songCompletedEvents.asSharedFlow()

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═══════════════════════════════════════════════════════════════
    
    // Cache of stream URLs by song ID
    // Keep cache small — YouTube URLs expire after ~6h, no point hoarding stale ones.
    private val streamUrlCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 50
            }
        }
    )

    private fun usableQueueStreamUrl(songId: String): String? = synchronized(streamUrlCache) {
        val url = streamUrlCache[songId] ?: return@synchronized null
        val expiresAt = AudioStreamCandidate.extractExpiryFromUrl(url)
        if (expiresAt != null && expiresAt <= System.currentTimeMillis() + 60_000L) {
            streamUrlCache.remove(songId)
            null
        } else {
            url
        }
    }
    
    // Queue of songs (mirrors player queue)
    private val songQueue: MutableList<Song> = Collections.synchronizedList(mutableListOf<Song>())
    
    // Callback for when queue needs more songs
    private var lastQueueCheckTime = 0L
    private var onQueueNeedsMoreSongs: ((String) -> Unit)? = null
    
    // Callback for when stream URL expires/fails (needs re-extraction)
    private var onStreamUrlExpired: ((String) -> Unit)? = null
    
    // Track if we're at end of queue
    private var reachedEndOfQueue = false

    // Track if we are waiting for the next track to load after clicking Next at the end of the queue
    private var isWaitingForNextTrack = false

    // Monotonic session id to ignore stale async queue work after new playback starts.
    private val queueSessionId = AtomicInteger(0)
    private var downloadedRadioSession: Int? = null

    init {
        scope.launch {
            playbackSessionStore.revisions.drop(1).collect {
                queuePreloadJob?.cancel()
                streamUrlCache.clear()
                syncUpcomingMediaItems()
                preResolveUpcomingTracks(_currentQueueIndex.value)
            }
        }
        scope.launch {
            networkMonitor.isOnlineState.drop(1).collect { online ->
                if (!online) {
                    radioJob?.cancel()
                    radioJob = null
                    _isLoadingMore.value = false
                    if (isWaitingForNextTrack) {
                        isWaitingForNextTrack = false
                        _isBuffering.value = false
                    }
                } else if (downloadedRadioSession == queueSessionId.get() &&
                    mediaController?.currentMediaItem?.mediaId?.let { it == _currentSong.value?.id } == true
                ) {
                    forceQueueCheck()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYER LISTENER - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    private val playerListener = object : Player.Listener {
        private var lastSongId: String? = null

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val controller = mediaController
            val activeState = playWhenReady && controller?.playbackState != Player.STATE_ENDED
            _isPlaying.value = activeState
            if (activeState) {
                _playbackError.value = null
            } else {
                stopPositionUpdates()
                controller?.let { c ->
                    persistCurrentPosition(c.currentMediaItem?.mediaId, c.currentPosition)
                }
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            for (group in tracks.groups) {
                if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val format = group.getTrackFormat(i)
                            playbackDiagnostics.onPlayingFormatChanged(format)
                            audioEngine.updateFromExoPlayerFormat(format)
                            break
                        }
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val controller = mediaController
            val activeState = isPlaying || (controller?.playWhenReady == true && controller.playbackState != Player.STATE_ENDED)
            _isPlaying.value = activeState
            if (isPlaying) {
                playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.PLAYING)
                startPositionUpdates()
                _playbackError.value = null
                // Check queue when playback resumes
                checkQueueAndRequestMore()
            } else {
                stopPositionUpdates()
                if (!activeState) playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _isBuffering.value = true
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.BUFFERING)
                }
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    updateDuration()
                    _playbackError.value = null
                    if (mediaController?.isPlaying == true) {
                        playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.PLAYING)
                    } else {
                        playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
                    }
                }
                Player.STATE_ENDED -> {
                    _isBuffering.value = false
                    _isPlaying.value = false
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
                    // Emit song completed event for the last song in the queue
                    lastSongId?.let { videoId ->
                        scope.launch {
                            _songCompletedEvents.emit(SongCompletedEvent(videoId, System.currentTimeMillis()))
                        }
                    }
                    lastSongId = null

                    // 1. Advance through controller if it already has next preloaded items
                    if (queueSelectionJob?.isActive == true) {
                        _isBuffering.value = true
                        return
                    }
                    val ctrl = mediaController
                    if (ctrl != null) {
                        val nextCtrlIdx = ctrl.currentMediaItemIndex + 1
                        if (nextCtrlIdx < ctrl.mediaItemCount) {
                            Log.d(TAG, "STATE_ENDED: Auto-advancing in controller to index $nextCtrlIdx (count=${ctrl.mediaItemCount})")
                            ctrl.seekTo(nextCtrlIdx, 0L)
                            ctrl.prepare()
                            ctrl.play()
                            return
                        }
                    }

                    // 2. Advance through songQueue if next songs exist
                    val curIdx = _currentQueueIndex.value
                    val qSize = synchronized(songQueue) { songQueue.size }
                    if (curIdx < qSize - 1) {
                        Log.d(TAG, "STATE_ENDED: Advancing songQueue to index ${curIdx + 1} ($qSize songs total)")
                        skipToQueueItem(curIdx + 1)
                        return
                    } else if (qSize > 0 && _repeatMode.value == Player.REPEAT_MODE_ALL) {
                        Log.d(TAG, "STATE_ENDED: Repeat All active, looping to start")
                        skipToQueueItem(0)
                        return
                    } else if (canAutoQueue()) {
                        Log.d(TAG, "STATE_ENDED: End of queue reached with infinite mode ON, loading fresh recommendations")
                        reachedEndOfQueue = true
                        isWaitingForNextTrack = true
                        _isBuffering.value = true
                        _currentSong.value?.let { song ->
                            prefetchQueueRecommendations(song.id, playImmediatelyWhenReady = true)
                        }
                        return
                    }

                    reachedEndOfQueue = true
                }
                Player.STATE_IDLE -> {
                    _isBuffering.value = false
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            audioEngine.clearStreamInfo()
            if (mediaItem != null) {
                val prevSongId = lastSongId
                val newSongId = mediaItem.mediaId
                if (prevSongId != null && prevSongId != newSongId) {
                    intelligentSourceResolver.get().cancelResolution(prevSongId)
                    scope.launch {
                        _songCompletedEvents.emit(SongCompletedEvent(prevSongId, System.currentTimeMillis()))
                    }
                }
                lastSongId = newSongId

                var foundIndex = -1
                synchronized(songQueue) {
                    val controller = mediaController
                    val index = controller?.currentMediaItemIndex ?: -1
                    foundIndex = when {
                        controller?.mediaItemCount == songQueue.size && songQueue.getOrNull(index)?.id == newSongId -> index
                        songQueue.getOrNull(_currentQueueIndex.value)?.id == newSongId -> _currentQueueIndex.value
                        else -> songQueue.indexOfFirst { it.id == newSongId }
                    }
                    val current = songQueue.getOrNull(foundIndex) ?: mediaItem.toSong()
                    _currentSong.value = current
                    _currentQueueIndex.value = foundIndex
                    val position = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    val duration = (current.duration * 1_000L).coerceAtLeast(0L)
                    _currentPosition.value = position
                    _duration.value = duration
                    _progress.value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    playbackDiagnostics.onSongChanged(current.id)
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.PREPARING)
                    persistCurrentState(current, songQueue.toList(), foundIndex, position)
                }
                
                // Check if we need more songs after transition
                // This is key for YouTube Music continuous playback
                checkQueueAndRequestMore()

                // Pre-resolve upcoming tracks for zero-gap playback
                if (foundIndex >= 0) {
                    preResolveUpcomingTracks(foundIndex)
                }

                // Resolve and apply stream metadata (bitrate, codec) for new track
                resolveAndApplyStreamInfo(newSongId)
            } else {
                val prevSongId = lastSongId
                if (prevSongId != null) {
                    scope.launch {
                        _songCompletedEvents.emit(SongCompletedEvent(prevSongId, System.currentTimeMillis()))
                    }
                }
                lastSongId = null
            }
            updateDuration()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
            _currentSong.value?.let { current ->
                persistCurrentState(current, _queue.value, _currentQueueIndex.value, _currentPosition.value)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (_shuffleEnabled.value != shuffleModeEnabled) applyQueueShuffle(shuffleModeEnabled)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}, code: ${error.errorCode}")
            
            // Check if this is an HTTP error (likely expired URL)
            if (isHttpError(error)) {
                Log.d(TAG, "HTTP error detected, requesting URL refresh...")
                _isBuffering.value = true
                _playbackError.value = null // Don't flash playback error banner during transparent recovery
                playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.RECOVERING, "Refreshing stream...")
                _currentSong.value?.let { song ->
                    onStreamUrlExpired?.invoke(song.id)
                }
            } else {
                _playbackError.value = "Playback error: ${error.errorCodeName}"
                _isBuffering.value = false
                playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.ERROR, error.message)
            }
        }
        
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Check queue after seeking or track change
            if (reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                checkQueueAndRequestMore()
            }
        }


    }
    
    /**
     * Check if error is HTTP-related (403 Forbidden, 410 Gone, etc.)
     */
    private fun isHttpError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> true
            else -> false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONNECTION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Connect to PlaybackService
     */
    fun connect() {
        if (controllerFuture != null) return

        Log.d(TAG, "Connecting to PlaybackService...")
        
        val sessionToken = SessionToken(
            context, 
            ComponentName(context, PlaybackService::class.java)
        )
        
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            try {
                mediaController = future.get().also { it.addListener(playerListener) }
                syncState()
                Log.d(TAG, "Connected to PlaybackService")
            } catch (e: Exception) {
                controllerFuture = null
                mediaController = null
                MediaController.releaseFuture(future)
                if (pendingPlayback != null) {
                    pendingPlayback = null
                    _isBuffering.value = false
                    _playbackError.value = "Could not connect to playback. Try again."
                }
                Log.e(TAG, "Connection failed", e)
                return@addListener
            }
            val action = pendingPlayback
            pendingPlayback = null
            action?.invoke()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun deferPlaybackUntilConnected(action: () -> Unit): Boolean {
        if (mediaController != null) return false
        pendingPlayback = action
        connect()
        return true
    }

    /**
     * Disconnect from PlaybackService
     */
    fun disconnect() {
        queueSessionId.incrementAndGet()
        isWaitingForNextTrack = false
        stopPositionUpdates()
        stopQueueCheck()
        lazyPlaybackJob?.cancel()
        queueSelectionJob?.cancel()
        radioJob?.cancel()
        streamInfoResolutionJob?.cancel()
        queuePreloadJob?.cancel()
        mediaController?.let { controller ->
            val song = _currentSong.value
            if (song != null && controller.currentMediaItem?.mediaId == song.id) {
                val position = controller.currentPosition.coerceAtLeast(0L)
                _currentPosition.value = position
                persistCurrentState(song, _queue.value, _currentQueueIndex.value, position, saveImmediately = true)
            }
            controller.removeListener(playerListener)
        }
        val future = controllerFuture
        mediaController = null
        controllerFuture = null
        pendingPlayback = null
        future?.let { MediaController.releaseFuture(it) }
        _isLoadingMore.value = false
        _isPlaying.value = false
        _isBuffering.value = false
        playbackDiagnostics.clear()
        Log.d(TAG, "Disconnected from PlaybackService")
    }

    /**
     * Set callback for when queue needs more songs
     */
    fun setOnQueueNeedsMoreSongs(callback: (String) -> Unit) {
        onQueueNeedsMoreSongs = callback
    }

    /**
     * Set callback for when stream URL expires and needs refresh
     */
    fun setOnStreamUrlExpired(callback: (String) -> Unit) {
        onStreamUrlExpired = callback
    }

    /**
     * Asynchronously resolve stream info
     * and update the AudioEngine state for the active song.
     */
    fun resolveAndApplyStreamInfo(songId: String) {
        streamInfoResolutionJob?.cancel()
        val url = mediaController?.currentMediaItem?.takeIf { it.mediaId == songId }
            ?.localConfiguration?.uri?.toString()
        val resolved = url?.let { playbackDiagnostics.resolvedStreamInfo(songId, it) }
        if (resolved != null && _currentSong.value?.id == songId) {
            audioEngine.updateStreamInfo(resolved, songId)
            playbackDiagnostics.onCandidateSelected(songId, resolved)
            return
        }
        streamInfoResolutionJob = scope.launch(Dispatchers.IO) {
            try {
                val info = if (songId.deviceLocalSongId() != null) {
                    (songSourceResolver.getSongStreamSource(songId) as? SongSource.Local)?.info
                } else {
                    songRepository.get().getStreamWithInfo(songId, audioEngine.getOptimalQuality())
                        .getOrNull()?.second
                } ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_currentSong.value?.id == songId) {
                        audioEngine.updateStreamInfo(info, songId)
                        playbackDiagnostics.onCandidateSelected(songId, info)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve stream info for $songId: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Prepare for playback (immediate UI feedback)
     * Sets the current song and loading state BEFORE fetching the URL
     */
    fun preparePlayback(song: Song) {
        Log.d(TAG, "Preparing playback for: ${song.title}")
        
        // Immediately reset previous song's stream state
        audioEngine.clearStreamInfo()
        playbackDiagnostics.onSongChanged(song.id)
        playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.PREPARING)

        // Pause current playback to prevent state mismatch
        mediaController?.pause()
        
        // Update state immediately
        _currentSong.value = song
        _isBuffering.value = true
        _playbackError.value = null
        _currentPosition.value = 0
        _progress.value = 0f
        _duration.value = 0
        
        // Reset queue index if we're starting fresh
        _currentQueueIndex.value = 0
    }
    fun playSong(song: Song, streamUrl: String) {
        if (deferPlaybackUntilConnected { playSong(song, streamUrl) }) return
        Log.d(TAG, "Playing: ${song.title}")

        lazyPlaybackJob?.cancel()
        queueSelectionJob?.cancel()
        resetRadioContext()
        queuePreloadJob?.cancel()
        val sessionId = queueSessionId.incrementAndGet()
        isWaitingForNextTrack = false
        
        lazyPlaybackJob = scope.launch {
            var resolvedInfo: AudioStreamInfo? = null
            val finalUrl = if (streamUrl.isNotBlank()) {
                streamUrl
            } else {
                val source = songSourceResolver.getSongStreamSource(song) { superiorCandidate, superiorInfo ->
                    val controller = mediaController
                    if (controller != null && controller.isPlaying && _currentSong.value?.id == song.id) {
                        val currentPos = controller.currentPosition
                        if (currentPos in 0..6000) {
                            Log.d(TAG, "Safe in-place upgrade triggered for '${song.title}' to verified lossless: ${superiorCandidate.codec} at ${currentPos}ms")
                            playSongInPlace(song, superiorCandidate.playbackUri, superiorInfo, superiorCandidate)
                        } else {
                            Log.d(TAG, "Upgrade discovery succeeded but playback position is ${currentPos}ms (> 6s); skipping mid-song interruption.")
                        }
                    }
                }
                when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> {
                        resolvedInfo = source.info
                        source.filePath
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> {
                        resolvedInfo = source.info
                        source.streamUrl
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                        _playbackError.value = "Song not available offline"
                        _isBuffering.value = false
                        return@launch
                    }
                }
            }

            if (sessionId != queueSessionId.get()) return@launch

            if (resolvedInfo != null) {
                withContext(Dispatchers.Main) {
                    audioEngine.updateStreamInfo(resolvedInfo, song.id)
                    playbackDiagnostics.onCandidateSelected(song.id, resolvedInfo)
                }
            }

            // Cache the URL
            streamUrlCache[song.id] = finalUrl
            
            // Clear and set new queue
            val queueSnapshot: List<Song>
            synchronized(songQueue) {
                songQueue.clear()
                songQueue.add(song)
                queueSnapshot = songQueue.toList()
            }
            _queue.value = queueSnapshot
            reachedEndOfQueue = false
            
            // Create and play media item
            val mediaItem = createMediaItem(song, finalUrl)
            
            mediaController?.apply {
                setMediaItem(mediaItem)
                prepare()
                play()
            }
            
            _currentSong.value = song
            _currentQueueIndex.value = 0
            
            // Resolve and apply stream metadata if not already available
            if (resolvedInfo == null) {
                resolveAndApplyStreamInfo(song.id)
            }

            // ViTune: Immediately request more songs for infinite queue
            checkQueueAndRequestMore()
        }
    }

    /**
     * Replace the current media item's stream URL in-place and resume playback.
     * This fixes the bug where refreshing an expired stream URL cleared the queue.
     */
    fun playSongInPlace(
        song: Song,
        streamUrl: String,
        info: AudioStreamInfo? = null,
        candidate: com.sonicmusic.app.domain.model.AudioStreamCandidate? = null
    ) {
        Log.d(TAG, "Replacing stream URL in-place: ${song.title} (${candidate?.sourceType ?: "provided"})")

        lazyPlaybackJob?.cancel()
        
        scope.launch {
            var resolvedInfo: AudioStreamInfo? = info
            val finalUrl = if (streamUrl.isNotBlank()) {
                streamUrl
            } else {
                val source = songSourceResolver.getSongStreamSource(song) { superiorCandidate, superiorInfo ->
                    val controller = mediaController
                    if (controller != null && controller.isPlaying && _currentSong.value?.id == song.id) {
                        val currentPos = controller.currentPosition
                        if (currentPos in 0..6000) {
                            Log.d(TAG, "Safe in-place upgrade triggered for '${song.title}' to verified lossless: ${superiorCandidate.codec} at ${currentPos}ms")
                            playSongInPlace(song, superiorCandidate.playbackUri, superiorInfo, superiorCandidate)
                        } else {
                            Log.d(TAG, "Upgrade discovery succeeded but playback position is ${currentPos}ms (> 6s); skipping mid-song interruption.")
                        }
                    }
                }
                when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> {
                        resolvedInfo = source.info
                        source.filePath
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> {
                        resolvedInfo = source.info
                        source.streamUrl
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                        _playbackError.value = "Song not available offline"
                        return@launch
                    }
                }
            }

            if (resolvedInfo != null) {
                withContext(Dispatchers.Main) {
                    audioEngine.updateStreamInfo(resolvedInfo, song.id)
                    if (candidate != null) {
                        playbackDiagnostics.onCandidateSelected(song.id, candidate)
                    } else {
                        playbackDiagnostics.onCandidateSelected(song.id, resolvedInfo)
                    }
                }
                if (candidate != null) {
                    songRepository.get().updateStreamCache(song.id, candidate, resolvedInfo)
                }
            }

            // Cache the URL
            streamUrlCache[song.id] = finalUrl
            
            val controller = mediaController ?: return@launch
            val count = controller.mediaItemCount
            var updated = false
            for (i in 0 until count) {
                val item = controller.getMediaItemAt(i)
                if (item.mediaId == song.id) {
                    val newMediaItem = createMediaItem(song, finalUrl)
                    if (i == controller.currentMediaItemIndex) {
                        val currentPosition = controller.currentPosition
                        controller.replaceMediaItem(i, newMediaItem)
                        controller.seekTo(i, currentPosition)
                        controller.prepare()
                        controller.play()
                        _currentSong.value = song
                    } else {
                        controller.replaceMediaItem(i, newMediaItem)
                    }
                    Log.d(TAG, "Replaced stream URL in-place for ${song.title} at index $i")
                    updated = true
                    if (resolvedInfo == null) {
                        resolveAndApplyStreamInfo(song.id)
                    }
                    break
                }
            }
            
            if (!updated) {
                // Fallback if index matching fails
                playSong(song, finalUrl)
            }
        }
    }

    /**
     * Play with a queue of songs - ViTune Style
     * 
     * @param songs List of songs to play
     * @param streamUrls Map of song IDs to stream URLs
     * @param startIndex Index to start playing from
     */
    fun playWithQueue(
        songs: List<Song>,
        streamUrls: Map<String, String>,
        startIndex: Int = 0,
        invalidatePendingLazyLoads: Boolean = true
    ) {
        if (songs.isEmpty()) return
        if (deferPlaybackUntilConnected { playWithQueue(songs, streamUrls, startIndex, invalidatePendingLazyLoads) }) return

        if (invalidatePendingLazyLoads) {
            lazyPlaybackJob?.cancel()
            queueSelectionJob?.cancel()
            resetRadioContext()
            queuePreloadJob?.cancel()
            queueSessionId.incrementAndGet()
        }
        isWaitingForNextTrack = false

        val safeRequestedIndex = startIndex.coerceIn(0, songs.lastIndex)
        Log.d(TAG, "Playing queue: ${songs.size} songs, starting at $safeRequestedIndex")

        val playableEntries = songs.mapIndexedNotNull { originalIndex, song ->
            streamUrls[song.id]?.let { url -> Triple(originalIndex, song, url) }
        }

        if (playableEntries.isEmpty()) {
            Log.e(TAG, "No valid media items")
            return
        }

        val exactStartPlayableIndex = playableEntries.indexOfFirst { it.first == safeRequestedIndex }
        val startPlayableIndex = if (exactStartPlayableIndex >= 0) {
            exactStartPlayableIndex
        } else {
            val nextPlayableIndex = playableEntries.indexOfFirst { it.first > safeRequestedIndex }
            if (nextPlayableIndex >= 0) nextPlayableIndex else 0
        }

        // Cache only playable URLs.
        playableEntries.forEach { (_, song, url) ->
            streamUrlCache[song.id] = url
        }

        // Keep local queue and player queue aligned 1:1.
        val queueSnapshot: List<Song>
        synchronized(songQueue) {
            songQueue.clear()
            songQueue.addAll(playableEntries.map { it.second })
            queueSnapshot = songQueue.toList()
        }
        _queue.value = queueSnapshot
        reachedEndOfQueue = false

        val mediaItems = playableEntries.map { (_, song, url) ->
            createMediaItem(song, url)
        }

        mediaController?.apply {
            setMediaItems(mediaItems, startPlayableIndex, 0)
            prepare()
            play()
        }

        _currentSong.value = queueSnapshot.getOrNull(startPlayableIndex)
        _currentQueueIndex.value = startPlayableIndex

        val skippedCount = songs.size - queueSnapshot.size
        if (skippedCount > 0) {
            Log.w(TAG, "Skipped $skippedCount songs without playable stream URLs")
        }

        // ViTune: Check if we need more songs immediately
        checkQueueAndRequestMore()
    }

    /**
     * Play a list of songs lazily (fetches URLs in background) - YouTube Music Style
     * 
     * Preserves natural queue order: playing track at startIndex starts immediately,
     * while the entire playlist (including previous and upcoming tracks) is preserved
     * in its original order in the queue.
     * 
     * @param songs List of songs to play
     * @param startIndex Index to start playing from
     * @param songRepository Repository to fetch URLs from
     */
    fun playSongsLazy(
        songs: List<Song>, 
        startIndex: Int = 0, 
        @Suppress("UNUSED_PARAMETER") songRepository: com.sonicmusic.app.domain.repository.SongRepository = this.songRepository.get(),
        playWhenReady: Boolean = true,
        initialPositionMs: Long = 0L
    ) {
        if (songs.isEmpty()) return

        if (deferPlaybackUntilConnected { playSongsLazy(songs, startIndex, songRepository, playWhenReady, initialPositionMs) }) return

        lazyPlaybackJob?.cancel()
        queueSelectionJob?.cancel()
        resetRadioContext()
        queuePreloadJob?.cancel()
        val sessionId = queueSessionId.incrementAndGet()
        isWaitingForNextTrack = false

        lazyPlaybackJob = scope.launch {
            val safeStartIndex = startIndex.coerceIn(0, songs.lastIndex)
            val targetSong = songs[safeStartIndex]
            Log.d(
                TAG,
                "Lazy playing queue: ${songs.size} songs, starting at index $safeStartIndex (${targetSong.title}), playWhenReady: $playWhenReady"
            )

            try {
                // 1. Immediately expose the full queue state in natural order so UI/QueueSheet is fully accurate
                audioEngine.clearStreamInfo()
                synchronized(songQueue) {
                    songQueue.clear()
                    songQueue.addAll(songs)
                }
                _queue.value = songs
                _currentSong.value = targetSong
                _currentQueueIndex.value = safeStartIndex
                _isBuffering.value = true
                _playbackError.value = null
                reachedEndOfQueue = false
                persistCurrentState(targetSong, songs, safeStartIndex, initialPositionMs)

                // 2. Fetch the target song's stream URL immediately for instant playback
                val source = songSourceResolver.getSongStreamSource(targetSong) { superiorCandidate, superiorInfo ->
                    val controller = mediaController
                    if (controller != null && controller.isPlaying && _currentSong.value?.id == targetSong.id) {
                        val currentPos = controller.currentPosition
                        if (currentPos in 0..6000) {
                            Log.d(TAG, "Safe in-place upgrade triggered for '${targetSong.title}' to verified lossless: ${superiorCandidate.codec} at ${currentPos}ms")
                            playSongInPlace(targetSong, superiorCandidate.playbackUri, superiorInfo, superiorCandidate)
                        } else {
                            Log.d(TAG, "Upgrade discovery succeeded but playback position is ${currentPos}ms (> 6s); skipping mid-song interruption.")
                        }
                    }
                }
                if (sessionId != queueSessionId.get()) {
                    Log.d(TAG, "Ignoring stale lazy play success for old session")
                    return@launch
                }

                val (targetUrl, resolvedInfo) = when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> {
                        source.filePath to source.info
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> {
                        source.streamUrl to source.info
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                        _playbackError.value = "Song not available offline"
                        _isBuffering.value = false
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    audioEngine.updateStreamInfo(resolvedInfo, targetSong.id)
                    playbackDiagnostics.onCandidateSelected(targetSong.id, resolvedInfo)
                }

                streamUrlCache[targetSong.id] = targetUrl

                // 3. Play the target song on MediaController
                val targetMediaItem = createMediaItem(targetSong, targetUrl)
                mediaController?.apply {
                    setMediaItems(listOf(targetMediaItem), 0, initialPositionMs)
                    prepare()
                    if (playWhenReady) {
                        play()
                    } else {
                        pause()
                    }
                }

                _isBuffering.value = false

                // 5. Pre-resolve immediate next tracks for zero-gap playback
                syncUpcomingMediaItems()
                preResolveUpcomingTracks(safeStartIndex)


            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionId != queueSessionId.get()) return@launch
                Log.e(TAG, "Error in lazy playback", e)
                _playbackError.value = "Error: ${e.message}"
                _isBuffering.value = false
            }
        }
    }

    fun playDownloadedSong(song: Song) {
        if (deferPlaybackUntilConnected { playDownloadedSong(song) }) return
        playSongsLazy(listOf(song))
        downloadedRadioSession = queueSessionId.get()
    }

    /**
     * Play a single song and dynamically generate fresh YouTube Music recommendations
     * based on that song, loading INITIAL_RECOMMENDATION_COUNT (15 songs total) into the queue.
     */
    fun playSongWithRadio(song: Song, playlistId: String? = null) {
        if (deferPlaybackUntilConnected { playSongWithRadio(song, playlistId) }) return
        preparePlayback(song)
        playSongsLazy(listOf(song), songRepository = songRepository.get())
        lastRadioSeedSongId = song.id
        activeRadioPlaylistId = playlistId
        prefetchQueueRecommendations(song.id, initial = true)
    }

    fun startRadioFromCurrentSong() {
        val seed = _currentSong.value ?: return
        val index = _currentQueueIndex.value
        if (mediaController?.currentMediaItem?.mediaId != seed.id || index < 0) {
            playSongWithRadio(seed)
            return
        }
        resetRadioContext()
        queuePreloadJob?.cancel()
        queueSessionId.incrementAndGet()
        val snapshot = synchronized(songQueue) {
            val keep = (index + 1).coerceAtMost(songQueue.size)
            songQueue.subList(keep, songQueue.size).clear()
            songQueue.toList()
        }
        _queue.value = snapshot
        persistCurrentState(seed, snapshot, index, _currentPosition.value)
        syncUpcomingMediaItems()
        lastRadioSeedSongId = seed.id
        prefetchQueueRecommendations(seed.id, initial = true)
    }

    private fun resetRadioContext() {
        radioJob?.cancel()
        radioJob = null
        _isLoadingMore.value = false
        lastRadioSeedSongId = null
        activeRadioPlaylistId = null
        activeRadioContinuationToken = null
        pendingRadioTracks = emptyList()
        radioSeenIds.clear()
    }

    /**
     * Synchronizes ExoPlayer's upcoming media items to mirror songQueue starting
     * from currentQueueIndex + 1.
     * Never interrupts the currently playing media item.
     */
    fun syncUpcomingMediaItems() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) { syncUpcomingMediaItems() }
            return
        }
        val controller = mediaController ?: return
        val currentIdx = _currentQueueIndex.value
        val (currentId, upcomingQueueSongs) = synchronized(songQueue) {
            val currentId = songQueue.getOrNull(currentIdx)?.id ?: return
            currentId to upcomingPreloadWindow(songQueue, currentIdx, QUEUE_PRELOAD_COUNT)
        }

        val currentPlayingCtrlIdx = controller.currentMediaItemIndex
        if (currentPlayingCtrlIdx < 0 ||
            controller.currentMediaItem?.mediaId != currentId
        ) return

        val fromIndex = currentPlayingCtrlIdx + 1
        val upcomingItems = resolveReadyQueuePrefix(upcomingQueueSongs) { song ->
            usableQueueStreamUrl(song.id)?.let { createMediaItem(song, it) }
        }
        val itemCount = controller.mediaItemCount
        var retained = 0
        while (retained < upcomingItems.size && fromIndex + retained < itemCount) {
            val old = controller.getMediaItemAt(fromIndex + retained)
            val new = upcomingItems[retained]
            if (old.mediaId != new.mediaId || old.localConfiguration?.uri != new.localConfiguration?.uri) break
            retained++
        }
        if (retained < upcomingItems.size || fromIndex + retained < itemCount) {
            controller.replaceMediaItems(fromIndex + retained, itemCount, upcomingItems.drop(retained))
        }
    }

    fun addSongsToQueueLazy(
        songs: List<Song>,
        @Suppress("UNUSED_PARAMETER") songRepository: com.sonicmusic.app.domain.repository.SongRepository = this.songRepository.get(),
        isQueueAlreadyPopulated: Boolean = false,
        expectedSessionId: Int? = null,
    ) {
        if (songs.isEmpty() || (expectedSessionId != null && expectedSessionId != queueSessionId.get())) return
        if (!isQueueAlreadyPopulated) {
            synchronized(songQueue) {
                val existingIds = songQueue.mapTo(hashSetOf()) { it.id }
                songQueue.addAll(songs.filter { existingIds.add(it.id) })
            }
            emitQueueIfChanged()
            persistCurrentState(_currentSong.value, _queue.value, _currentQueueIndex.value, _currentPosition.value)
            reachedEndOfQueue = false
        }
        preResolveUpcomingTracks(_currentQueueIndex.value)
        resumeQueuedPlaybackIfWaiting()
    }

    private fun resumeQueuedPlaybackIfWaiting() {
        val controller = mediaController ?: return
        if (queueSelectionJob?.isActive == true) return
        if (controller.playbackState != Player.STATE_ENDED && !isWaitingForNextTrack) return
        skipToQueueItem(_currentQueueIndex.value + 1)
    }


    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        if (pendingPlayback != null && mediaController == null) {
            pendingPlayback = null
            _isBuffering.value = false
            return
        }
        if (deferPlaybackUntilConnected(::togglePlayPause)) return
        val controller = mediaController ?: return

        // If lazy playback is actively loading a new song/queue, user tapping pause should cancel it
        if (lazyPlaybackJob?.isActive == true && _isBuffering.value) {
            lazyPlaybackJob?.cancel()
            _isBuffering.value = false
            controller.pause()
            _isPlaying.value = false
            return
        }

        // Check if controller has no active media item (e.g. fresh app launch with restored state)
        if (controller.currentMediaItem == null || controller.mediaItemCount == 0) {
            val song = _currentSong.value ?: return
            val currentQueue = _queue.value.ifEmpty { listOf(song) }
            val startIndex = _currentQueueIndex.value.coerceIn(0, currentQueue.lastIndex)
            val initialPositionMs = _currentPosition.value
            Log.d(TAG, "Resuming restored song: ${song.title} from ${initialPositionMs}ms")
            playSongsLazy(
                songs = currentQueue,
                startIndex = startIndex,
                songRepository = songRepository.get(),
                playWhenReady = true,
                initialPositionMs = initialPositionMs
            )
            return
        }

        val shouldPause = controller.isPlaying || (controller.playWhenReady && controller.playbackState != Player.STATE_ENDED)
        if (shouldPause) {
            controller.pause()
            _isPlaying.value = false
        } else {
            if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(0)
            }
            controller.play()
            _isPlaying.value = true
        }
    }

    /**
     * Skip to next track - YouTube Music Style
     * 
     * If next song exists in queue, advances directly to it (resolving URL immediately if not yet in player).
     * If at the end of queue:
     * - In REPEAT_MODE_ALL: loops back to the start of the queue.
     * - If infiniteModeEnabled: pre-fetches next recommendations and plays immediately.
     */
    fun skipToNext() {
        if (_currentSong.value != null && deferPlaybackUntilConnected(::skipToNext)) return
        val controller = mediaController ?: return
        val currentIndex = _currentQueueIndex.value
        val totalQueueSize = synchronized(songQueue) { songQueue.size }

        if (currentIndex < totalQueueSize - 1) {
            // Next song exists in queue
            skipToQueueItem(currentIndex + 1)
        } else if (totalQueueSize > 0 && controller.repeatMode == Player.REPEAT_MODE_ALL) {
            // Loop back to start of queue in repeat all mode
            skipToQueueItem(0)
        } else if (canAutoQueue()) {
            // End of queue reached with infinite mode ON — request radio recommendations and start playing immediately
            Log.d(TAG, "skipToNext: End of queue reached with infinite mode ON, loading recommendations")
            isWaitingForNextTrack = true
            _isBuffering.value = true
            _currentSong.value?.let { song ->
                prefetchQueueRecommendations(song.id, playImmediatelyWhenReady = true)
            }
        }
    }

    /**
     * Skip to previous track or restart current - YouTube Music Style
     * 
     * If played > 3 seconds: restarts current track to 0.
     * If played <= 3 seconds:
     * - If currentIndex > 0: skips to previous track in queue.
     * - If currentIndex == 0 & REPEAT_MODE_ALL: wraps to the last track in queue.
     * - Otherwise: restarts current track to 0.
     */
    fun skipToPrevious() {
        if (_currentSong.value != null && deferPlaybackUntilConnected(::skipToPrevious)) return
        val controller = mediaController ?: return
        val currentIndex = _currentQueueIndex.value
        val qSize = synchronized(songQueue) { songQueue.size }

        if (controller.currentPosition > 3000) {
            controller.seekTo(0)
        } else if (currentIndex > 0) {
            skipToQueueItem(currentIndex - 1)
        } else if (qSize > 0 && controller.repeatMode == Player.REPEAT_MODE_ALL) {
            skipToQueueItem(qSize - 1)
        } else {
            controller.seekTo(0)
        }
    }

    /**
     * Force skip to previous track ignoring restart threshold
     */
    fun forceSkipToPrevious() {
        if (_currentSong.value != null && deferPlaybackUntilConnected(::forceSkipToPrevious)) return
        val controller = mediaController ?: return
        val currentIndex = _currentQueueIndex.value
        val qSize = synchronized(songQueue) { songQueue.size }

        if (currentIndex > 0) {
            skipToQueueItem(currentIndex - 1)
        } else if (qSize > 0 && controller.repeatMode == Player.REPEAT_MODE_ALL) {
            skipToQueueItem(qSize - 1)
        } else {
            controller.seekTo(0)
        }
    }

    /**
     * Skip to a specific item in the queue - YouTube Music Style
     * 
     * Works reliably even when background hydration is still processing remaining songs.
     * Sets the target song as the active media item in MediaController, plays immediately,
     * and syncs the upcoming queue items.
     * 
     * @param index The index in songQueue to skip to
     */
    fun skipToQueueItem(index: Int) {
        val targetSong = synchronized(songQueue) { songQueue.getOrNull(index) } ?: return
        val controller = mediaController ?: return
        val session = queueSessionId.get()
        queueSelectionJob?.cancel()
        lazyPlaybackJob?.cancel()

        isWaitingForNextTrack = false

        fun playResolved(url: String) {
            if (queueSessionId.get() != session || mediaController !== controller) return
            val snapshot = synchronized(songQueue) { songQueue.toList() }
            val targetIndex = if (snapshot.getOrNull(index)?.id == targetSong.id) index
                else snapshot.indexOfFirst { it.id == targetSong.id }
            if (targetIndex < 0) {
                _isBuffering.value = false
                return
            }
            _currentSong.value = targetSong
            _currentQueueIndex.value = targetIndex
            persistCurrentState(targetSong, snapshot, targetIndex, 0L)
            streamUrlCache[targetSong.id] = url
            audioEngine.clearStreamInfo()
            controller.setMediaItem(createMediaItem(targetSong, url))
            controller.prepare()
            controller.play()
            _isPlaying.value = true
            _isBuffering.value = false
            syncUpcomingMediaItems()
            preResolveUpcomingTracks(targetIndex)
        }

        usableQueueStreamUrl(targetSong.id)?.let { url ->
            playResolved(url)
            return
        }

        queuePreloadJob?.cancel()
        _isBuffering.value = true
        queueSelectionJob = scope.launch {
            val source = try {
                songSourceResolver.getSongStreamSource(targetSong)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve queue track ${targetSong.id}", e)
                SongSource.Unavailable
            }
            if (!isActive || queueSessionId.get() != session) return@launch
            when (source) {
                is SongSource.Local -> playResolved(source.filePath)
                is SongSource.Remote -> playResolved(source.streamUrl)
                SongSource.Unavailable -> {
                    _playbackError.value = "Failed to load track"
                    _isBuffering.value = false
                }
            }
        }
    }

    /**
     * Insert song to play next (immediately after currently playing song)
     * Instant UI update + background stream preloading.
     */
    fun insertPlayNext(song: Song) {
        val currentIndex = _currentQueueIndex.value
        val (snapshot, updatedIndex) = synchronized(songQueue) {
            val existingIndex = songQueue.indexOfFirst { it.id == song.id }
            if (existingIndex >= 0 && existingIndex == currentIndex) return
            if (existingIndex >= 0) songQueue.removeAt(existingIndex)
            val nextIndex = currentIndexAfterRemoval(currentIndex, existingIndex)
            val insertion = (nextIndex + 1).coerceIn(0, songQueue.size)
            songQueue.add(insertion, song)
            songQueue.toList() to nextIndex
        }
        _currentQueueIndex.value = updatedIndex
        _queue.value = snapshot
        persistCurrentState(_currentSong.value, snapshot, updatedIndex, _currentPosition.value)
        preResolveUpcomingTracks(updatedIndex)
        resumeQueuedPlaybackIfWaiting()
    }

    fun insertPlayNext(song: Song, streamUrl: String) {
        streamUrlCache[song.id] = streamUrl
        insertPlayNext(song)
    }

    fun insertPlayNextLazy(
        song: Song,
        @Suppress("UNUSED_PARAMETER") songRepository: com.sonicmusic.app.domain.repository.SongRepository = this.songRepository.get()
    ) {
        insertPlayNext(song)
    }

    /**
     * Add single song to the end of the queue.
     * Instant UI update + background stream preloading.
     */
    fun addSongToQueue(song: Song) {
        val wasEmptyAndNoCurrentSong = synchronized(songQueue) { songQueue.isEmpty() } && _currentSong.value == null
        if (wasEmptyAndNoCurrentSong) {
            playSongWithRadio(song)
            return
        }

        val queueSnapshot = synchronized(songQueue) {
            songQueue.add(song)
            songQueue.toList()
        }
        _queue.value = queueSnapshot
        persistCurrentState(_currentSong.value, queueSnapshot, _currentQueueIndex.value, _currentPosition.value)

        Log.d(TAG, "Added song to end of queue: ${song.title} (size: ${queueSnapshot.size})")

        preResolveUpcomingTracks(_currentQueueIndex.value)
        resumeQueuedPlaybackIfWaiting()
    }

    fun addToQueue(song: Song, streamUrl: String) {
        streamUrlCache[song.id] = streamUrl
        addSongToQueue(song)
    }

    /**
     * Pre-resolve upcoming tracks ahead of time for zero-gap playback
     */
    private fun preResolveUpcomingTracks(currentIndex: Int) {
        val upcoming = synchronized(songQueue) { upcomingPreloadWindow(songQueue, currentIndex, QUEUE_PRELOAD_COUNT) }
        val ids = upcoming.map { it.id }
        if (queuePreloadJob?.isActive == true && ids == preloadingIds) return
        queuePreloadJob?.cancel()
        preloadingIds = ids
        syncUpcomingMediaItems()
        if (upcoming.isEmpty()) return

        val queueGeneration = queueSessionId.get()
        val sessionRevision = playbackSessionStore.revision
        queuePreloadJob = scope.launch {
            for (song in upcoming) {
                if (!isActive || queueSessionId.get() != queueGeneration || playbackSessionStore.revision != sessionRevision) break
                if (usableQueueStreamUrl(song.id) != null) continue
                val source = try {
                    songSourceResolver.getSongStreamSource(song)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not preload queue track ${song.id}", e)
                    SongSource.Unavailable
                }
                if (!isActive || queueSessionId.get() != queueGeneration || playbackSessionStore.revision != sessionRevision) break
                val url = when (source) {
                    is SongSource.Local -> source.filePath
                    is SongSource.Remote -> source.streamUrl
                    SongSource.Unavailable -> break
                }
                streamUrlCache[song.id] = url
                syncUpcomingMediaItems()
            }
        }
    }

    /**
     * Seek to position (0.0 to 1.0)
     * Uses cached duration for more reliable seeking
     */
    // Job that resets isSeeking — cancelled on each new seek to prevent premature reset
    private var seekResetJob: Job? = null

    fun seekTo(progress: Float) {
        val cachedDuration = _duration.value
        val controller = mediaController
        val controllerDuration = controller?.duration ?: 0L
        val durationToUse = if (cachedDuration > 0) cachedDuration else controllerDuration
        
        if (durationToUse > 0) {
            val position = (progress * durationToUse).toLong().coerceIn(0, durationToUse)
            
            // Set seeking flag to pause updates
            isSeeking = true
            
            // Immediately update UI state
            _progress.value = progress.coerceIn(0f, 1f)
            _currentPosition.value = position
            
            if (controller != null && controller.mediaItemCount > 0) {
                controller.seekTo(position)
            }
            persistCurrentPosition(_currentSong.value?.id, position)
            Log.d(TAG, "Seeking to ${position}ms (${(progress * 100).toInt()}%)")
            
            // Cancel previous reset job to prevent premature isSeeking clear on rapid seeks
            seekResetJob?.cancel()
            seekResetJob = scope.launch {
                delay(500) // 500ms grace period
                isSeeking = false
            }
        }
    }

    /**
     * Seek to absolute position in milliseconds
     */
    fun seekToPosition(positionMs: Long) {
        val dur = _duration.value
        val pos = positionMs.coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
        _currentPosition.value = pos
        if (dur > 0) {
            _progress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > 0) {
            controller.seekTo(pos)
        }
        persistCurrentPosition(_currentSong.value?.id, pos)
    }

    fun observeLyricsPosition() = flow {
        while (currentCoroutineContext().isActive) {
            emit(mediaController?.currentPosition ?: _currentPosition.value)
            delay(LYRICS_POSITION_INTERVAL_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)

    /**
     * Toggle repeat mode: OFF -> ALL -> ONE -> OFF
     */
    fun toggleRepeatMode() {
        mediaController?.let { controller ->
            controller.repeatMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /**
     * Toggle shuffle mode
     * Genuinely shuffles upcoming songs in the queue and updates UI,
     * preserving original playlist order snapshot to restore when toggled off.
     */
    fun toggleShuffle() {
        val newShuffle = !_shuffleEnabled.value
        applyQueueShuffle(newShuffle)
        mediaController?.shuffleModeEnabled = newShuffle
    }

    private fun applyQueueShuffle(enabled: Boolean) {
        _shuffleEnabled.value = enabled
        val currentIdx = _currentQueueIndex.value
        val newQueue = synchronized(songQueue) {
            val boundary = (currentIdx + 1).coerceIn(0, songQueue.size)
            val upcoming = songQueue.drop(boundary)
            val reordered = if (enabled) {
                unshuffledQueueSnapshot = songQueue.toList()
                upcoming.shuffled()
            } else {
                val originalOrder = unshuffledQueueSnapshot.orEmpty()
                unshuffledQueueSnapshot = null
                restoreUpcomingQueueOrder(upcoming, originalOrder)
            }
            songQueue.subList(boundary, songQueue.size).clear()
            songQueue.addAll(reordered)
            songQueue.toList()
        }
        _queue.value = newQueue
        persistCurrentState(_currentSong.value, newQueue, currentIdx, _currentPosition.value)
        syncUpcomingMediaItems()
        preResolveUpcomingTracks(currentIdx)
    }

    /**
     * Set playback speed (0.5x to 2.0x)
     */
    fun setPlaybackSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        mediaController?.let { controller ->
            controller.setPlaybackSpeed(clampedSpeed)
            _playbackSpeed.value = clampedSpeed
        }
    }

    /**
     * Clear the queue
     */
    fun clearQueue() {
        lazyPlaybackJob?.cancel()
        queueSelectionJob?.cancel()
        queuePreloadJob?.cancel()
        resetRadioContext()
        persistJob?.cancel()
        queueSessionId.incrementAndGet()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        synchronized(songQueue) { songQueue.clear() }
        streamUrlCache.clear()
        unshuffledQueueSnapshot = null
        _queue.value = emptyList()
        _currentQueueIndex.value = -1
        _currentSong.value = null
        _isBuffering.value = false
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _progress.value = 0f
        audioEngine.clearStreamInfo()
        reachedEndOfQueue = false
        isWaitingForNextTrack = false
        scope.launch {
            playbackStateDataStore.clearPlaybackState()
        }
    }

    /**
     * Reorder item in queue
     * Rearranges songQueue and seamlessly syncs upcoming media items to ExoPlayer.
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return

        val currentIndex = _currentQueueIndex.value
        val queueSnapshot: List<Song>
        val newIndex: Int
        synchronized(songQueue) {
            if (fromIndex !in songQueue.indices || toIndex !in songQueue.indices) return
            newIndex = currentIndexAfterMove(currentIndex, fromIndex, toIndex)
            val item = songQueue.removeAt(fromIndex)
            songQueue.add(toIndex, item)
            queueSnapshot = songQueue.toList()
        }
        _currentQueueIndex.value = newIndex
        _queue.value = queueSnapshot

        persistCurrentState(_currentSong.value, queueSnapshot, _currentQueueIndex.value, _currentPosition.value)
        syncUpcomingMediaItems()
        preResolveUpcomingTracks(_currentQueueIndex.value)
    }

    /**
     * Remove item from queue
     * Handles removing currently playing track or history/upcoming tracks smoothly.
     */
    fun removeFromQueue(index: Int) {
        val currentIdx = _currentQueueIndex.value
        val removedSong: Song
        val queueSnapshot: List<Song>
        val isQueueEmpty: Boolean

        synchronized(songQueue) {
            if (index !in songQueue.indices) return
            removedSong = songQueue.removeAt(index)
            queueSnapshot = songQueue.toList()
            isQueueEmpty = songQueue.isEmpty()
        }
        streamUrlCache.remove(removedSong.id)

        when {
            // Case A: Removing the currently playing song
            index == currentIdx -> {
                if (isQueueEmpty) {
                    clearQueue()
                } else {
                    val targetIndex = index.coerceAtMost(queueSnapshot.size - 1)
                    _queue.value = queueSnapshot
                    skipToQueueItem(targetIndex)
                    checkQueueAndRequestMore()
                }
            }
            // Case B: Removing a preceding / history song
            index < currentIdx -> {
                _currentQueueIndex.value = currentIdx - 1
                _queue.value = queueSnapshot
                persistCurrentState(_currentSong.value, queueSnapshot, _currentQueueIndex.value, _currentPosition.value)
                checkQueueAndRequestMore()
            }
            // Case C: Removing an upcoming song
            else -> {
                _queue.value = queueSnapshot
                persistCurrentState(_currentSong.value, queueSnapshot, currentIdx, _currentPosition.value)
                syncUpcomingMediaItems()
                preResolveUpcomingTracks(currentIdx)
                checkQueueAndRequestMore()
            }
        }
    }

    /**
     * Stop playback
     */
    fun stop() {
        mediaController?.stop()
    }

    // ═══════════════════════════════════════════════════════════════
    // QUEUE MANAGEMENT - ViTune Style
    // ═══════════════════════════════════════════════════════════════
    
    private fun canAutoQueue(): Boolean =
        networkMonitor.isOnlineState.value && _infiniteModeEnabled.value &&
            _currentSong.value?.id?.deviceLocalSongId() == null

    /**
     * Check if queue needs more songs and prefetch them before the queue runs dry.
     * Uses songQueue size instead of MediaController count to prevent false depletion triggers.
     */
    private fun checkQueueAndRequestMore() {
        if (!canAutoQueue()) return
        val currentSong = _currentSong.value ?: return

        if (radioJob?.isActive == true) {
            return
        }

        // Debounce: only check once every 1.5 seconds
        val now = System.currentTimeMillis()
        if (now - lastQueueCheckTime < QUEUE_CHECK_COOLDOWN_MS) {
            return
        }

        val currentIndex = _currentQueueIndex.value
        val totalItems = synchronized(songQueue) { songQueue.size }
        val remaining = (totalItems - currentIndex - 1).coerceAtLeast(0)

        if (remaining <= PREFETCH_THRESHOLD || reachedEndOfQueue) {
            lastQueueCheckTime = now
            reachedEndOfQueue = false

            Log.d(TAG, "Queue low ($remaining ahead in songQueue) or ended, prefetching recommendations for ${currentSong.title}...")
            prefetchQueueRecommendations(currentSong.id)
        }
    }

    /**
     * Manually trigger recommendation prefetch (e.g. from user menu action).
     */
    fun forcePrefetchRecommendations() {
        val currentSong = _currentSong.value ?: return
        prefetchQueueRecommendations(currentSong.id)
    }

    /**
     * Prefetch fresh recommendations in background using InnerTube continuation or RadioMix.
     * Appends the station's unplayed tracks in order and keeps the remainder for the next refill.
     */
    private fun prefetchQueueRecommendations(
        currentSongId: String,
        playImmediatelyWhenReady: Boolean = false,
        initial: Boolean = false,
    ) {
        if (!networkMonitor.isOnlineState.value || radioJob?.isActive == true) return
        val sessionId = queueSessionId.get()
        val remaining = synchronized(songQueue) { songQueue.size - _currentQueueIndex.value - 1 }
        if (!initial && !playImmediatelyWhenReady && remaining > PREFETCH_THRESHOLD && !reachedEndOfQueue) return
        val seedId = lastRadioSeedSongId ?: currentSongId
        lastRadioSeedSongId = seedId

        radioJob = scope.launch {
            _isLoadingMore.value = true
            try {
                val knownIds = synchronized(songQueue) { songQueue.mapTo(hashSetOf()) { it.id } }
                knownIds.addAll(radioSeenIds)
                knownIds.add(currentSongId)
                val limit = if (initial) TARGET_RECOMMENDATIONS else PREFETCH_BATCH_SIZE
                val available = linkedMapOf<String, Song>()
                available.addNewRadioTracks(pendingRadioTracks, knownIds)
                var token = activeRadioContinuationToken
                val stationId = activeRadioPlaylistId
                val visited = hashSetOf<String>()
                val source = youTubeiService.get()

                if (available.isEmpty() && token == null) {
                    val first = source.getRadioMix(seedId, stationId).getOrNull()
                        ?.takeIf { it.first.any { song -> song.id !in knownIds } || it.second != null }
                        ?: source.getUpNext(seedId).getOrNull()
                    available.addNewRadioTracks(first?.first.orEmpty(), knownIds)
                    token = first?.second
                }
                while (available.size < limit && token != null &&
                    visited.size < 3 && visited.add(token)
                ) {
                    val page = source.getQueueContinuation(token).getOrNull() ?: break
                    available.addNewRadioTracks(page.first, knownIds)
                    token = page.second?.takeUnless { it in visited }
                }
                if (available.isEmpty()) {
                    available.addNewRadioTracks(
                        queueRepository.get().getRelatedSongs(currentSongId, limit).getOrNull().orEmpty(),
                        knownIds
                    )
                }
                if (sessionId != queueSessionId.get() || !isActive || !networkMonitor.isOnlineState.value) return@launch

                val added: List<Song>
                val snapshot = synchronized(songQueue) {
                    val currentIds = songQueue.mapTo(hashSetOf()) { it.id }
                    val distinct = available.values.filter { it.id !in currentIds }
                    added = distinct.take(limit)
                    pendingRadioTracks = distinct.drop(limit)
                    songQueue.addAll(added)
                    songQueue.toList()
                }
                activeRadioContinuationToken = token
                added.mapTo(radioSeenIds) { it.id }
                if (added.isNotEmpty()) {
                    _queue.value = snapshot
                    persistCurrentState(_currentSong.value, snapshot, _currentQueueIndex.value, _currentPosition.value)
                    val qRepo = queueRepository.get()
                    if (qRepo is QueueRepositoryImpl) added.forEach { qRepo.markSongAsQueued(it.id) }
                    val index = _currentQueueIndex.value
                    preResolveUpcomingTracks(index)
                }
                if (playImmediatelyWhenReady || isWaitingForNextTrack) {
                    val nextIndex = _currentQueueIndex.value + 1
                    if (nextIndex in snapshot.indices) {
                        skipToQueueItem(nextIndex)
                    } else {
                        isWaitingForNextTrack = false
                        _isBuffering.value = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not load radio tracks", e)
                if (sessionId == queueSessionId.get() && isWaitingForNextTrack) {
                    isWaitingForNextTrack = false
                    _isBuffering.value = false
                }
            } finally {
                if (sessionId == queueSessionId.get() && radioJob === currentCoroutineContext()[Job]) {
                    _isLoadingMore.value = false
                }
            }
        }
    }

    private fun stopQueueCheck() {
        queueCheckJob?.cancel()
        queueCheckJob = null
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    // ── OPTIMIZATION 5: Only emit queue if content actually changed ──
    private fun emitQueueIfChanged() {
        val current = _queue.value
        val snapshot = synchronized(songQueue) {
            val newSize = songQueue.size
            if (current.size != newSize ||
                current.firstOrNull()?.id != songQueue.firstOrNull()?.id ||
                current.lastOrNull()?.id != songQueue.lastOrNull()?.id
            ) {
                songQueue.toList()
            } else null
        }
        if (snapshot != null) {
            _queue.value = snapshot
        }
    }
    
    private fun createMediaItem(song: Song, streamUrl: String): MediaItem {
        val sourceUri = normalizeSourceUri(streamUrl)
        val extras = android.os.Bundle().apply {
            putString("albumBrowseId", song.albumBrowseId)
            putString("albumName", song.albumName ?: song.album)
            putString("artistId", song.artistId)
            val artistsToSerialize = song.artists.ifEmpty { song.resolvedArtists }
            if (artistsToSerialize.isNotEmpty()) {
                val json = runCatching {
                    kotlinx.serialization.json.Json.encodeToString(artistsToSerialize)
                }.getOrNull()
                if (json != null) {
                    putString("artistsJson", json)
                }
            }
        }
        return MediaItem.Builder()
            .setUri(sourceUri)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album ?: song.albumName ?: "")
                    .setArtworkUri(Uri.parse(song.thumbnailUrl))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun normalizeSourceUri(source: String): Uri {
        val parsed = Uri.parse(source)
        return if (parsed.scheme.isNullOrBlank() && source.startsWith("/")) {
            // ANDROID 8 FIX: Use FileProvider content:// URI instead of file:// URI
            try {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    File(source)
                )
            } catch (e: IllegalArgumentException) {
                // Fallback for paths outside FileProvider-configured directories
                Uri.fromFile(File(source))
            }
        } else {
            parsed
        }
    }

    private fun syncState() {
        mediaController?.let { controller ->
            val hasActiveMedia = controller.currentMediaItem != null
            if (hasActiveMedia) {
                _isPlaying.value = controller.isPlaying || (controller.playWhenReady && controller.playbackState != Player.STATE_ENDED)
                _isBuffering.value = controller.playbackState == Player.STATE_BUFFERING
                _repeatMode.value = controller.repeatMode
                _shuffleEnabled.value = controller.shuffleModeEnabled
                
                synchronized(songQueue) {
                    val item = controller.currentMediaItem ?: return@let
                    val song = songQueue.find { it.id == item.mediaId } ?: item.toSong()
                    _currentSong.value = song
                }
                
                updateDuration()
                
                if (controller.isPlaying) {
                    startPositionUpdates()
                }
            } else {
                if (_currentSong.value == null) {
                    scope.launch {
                        restoreSavedPlaybackState()
                    }
                }
            }
        }
    }

    private fun updateDuration() {
        mediaController?.let { controller ->
            val dur = controller.duration
            if (dur > 0) {
                _duration.value = dur
            }
        }
    }

    // Track seeking state to prevent UI snap-back
    private var isSeeking = false

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = scope.launch {
            var lastSavedTime = System.currentTimeMillis()
            while (isActive) {
                if (!isSeeking) {
                    mediaController?.let { controller ->
                        val pos = controller.currentPosition
                        val dur = controller.duration
                        
                        // Guard: only emit when values actually changed to avoid
                        // unnecessary recompositions downstream.
                        if (pos != _currentPosition.value) {
                            _currentPosition.value = pos
                        }
                        if (dur > 0) {
                            if (dur != _duration.value) _duration.value = dur
                            val newProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                            if (kotlin.math.abs(newProgress - _progress.value) >= 0.002f || newProgress == 1f || newProgress == 0f) {
                                _progress.value = newProgress
                            }
                        }

                        // Periodically save playback position every 5s while playing (offloaded to Dispatchers.IO)
                        val now = System.currentTimeMillis()
                        if (now - lastSavedTime >= 5000L && pos > 0) {
                            lastSavedTime = now
                            persistCurrentPosition(controller.currentMediaItem?.mediaId, pos)
                        }
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS) // Smooth position updates
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun MediaItem.toSong(): Song {
        val extras = mediaMetadata.extras
        val albumBrowseId = extras?.getString("albumBrowseId")
        val albumName = extras?.getString("albumName")
        val artistId = extras?.getString("artistId")
        val artistsJson = extras?.getString("artistsJson")
        val decodedArtists: List<com.sonicmusic.app.domain.model.ArtistRef> = if (!artistsJson.isNullOrBlank()) {
            artistJsonCache.get(artistsJson) ?: run {
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<List<com.sonicmusic.app.domain.model.ArtistRef>>(artistsJson)
                }.getOrDefault(emptyList()).also { artistJsonCache.put(artistsJson, it) }
            }
        } else emptyList()

        val rawArtist = mediaMetadata.artist?.toString() ?: "Unknown Artist"
        val song = Song(
            id = mediaId,
            title = mediaMetadata.title?.toString() ?: "Unknown",
            artist = rawArtist,
            artistId = artistId,
            album = albumName ?: mediaMetadata.albumTitle?.toString(),
            duration = 0,
            thumbnailUrl = mediaMetadata.artworkUri?.toString() ?: "",
            albumBrowseId = albumBrowseId,
            albumName = albumName ?: mediaMetadata.albumTitle?.toString(),
            artists = decodedArtists
        )
        return if (decodedArtists.isEmpty() && (rawArtist.isNotBlank() || !artistId.isNullOrBlank())) {
            song.copy(artists = song.resolvedArtists)
        } else {
            song
        }
    }

    /**
     * Get remaining songs in queue
     */
    fun getRemainingQueueSize(): Int {
        val controller = mediaController ?: return 0
        return (controller.mediaItemCount - controller.currentMediaItemIndex - 1).coerceAtLeast(0)
    }
    
    /**
     * Get full queue for external sync
     */
    fun getQueue(): List<Song> = synchronized(songQueue) { songQueue.toList() }
    
    /**
     * Force a queue check (useful for external triggers)
     */
    fun forceQueueCheck() {
        lastQueueCheckTime = 0
        checkQueueAndRequestMore()
    }

    /**
     * Clear playback error
     */
    fun clearError() {
        _playbackError.value = null
    }
}
