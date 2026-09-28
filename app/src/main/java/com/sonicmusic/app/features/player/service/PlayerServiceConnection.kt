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
import com.google.common.util.concurrent.MoreExecutors
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.player.service.PlaybackService
import com.sonicmusic.app.features.player.audio.AudioEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.sonicmusic.app.domain.repository.SongCompletedEvent
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val songSourceResolver: com.sonicmusic.app.data.repository.SongSourceResolver,
    private val playbackStateDataStore: com.sonicmusic.app.data.local.datastore.PlaybackStateDataStore,
    private val songRepository: dagger.Lazy<com.sonicmusic.app.domain.repository.SongRepository>,
    private val queueRepository: dagger.Lazy<com.sonicmusic.app.domain.repository.QueueRepository>,
    private val youTubeiService: dagger.Lazy<com.sonicmusic.app.data.remote.source.YouTubeiService>,
    private val settingsRepository: dagger.Lazy<com.sonicmusic.app.data.repository.SettingsRepository>
) {
    companion object {
        private const val TAG = "SonicConnection"
        private const val QUEUE_CHECK_COOLDOWN_MS = 1500L
        private const val PRELOAD_THRESHOLD = 7
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        // Configurable queue recommendation thresholds (Prompt Section 7)
        const val INITIAL_RECOMMENDATION_COUNT = 15 // 1 seed + 14 recommendations = 15 initial
        const val PREFETCH_THRESHOLD = 5            // Trigger background prefetch when remaining songs <= 5
        const val QUEUE_LOW_THRESHOLD = PREFETCH_THRESHOLD
        const val PREFETCH_BATCH_SIZE = 10          // Fetch 10 songs per batch
        const val MAXIMUM_QUEUE_RECOMMENDATIONS = 50 // Cap queue up to 50 songs (no starvation, no 20-song truncation)
        const val MAX_QUEUE_SIZE = MAXIMUM_QUEUE_RECOMMENDATIONS
        const val TARGET_RECOMMENDATIONS = INITIAL_RECOMMENDATION_COUNT - 1
    }

    @Volatile private var activeRadioContinuationToken: String? = null

    // MediaController for communication with PlaybackService
    private var mediaController: MediaController? = null
    fun getMediaController(): MediaController? = mediaController
    private var controllerFuture: ListenableFuture<MediaController>? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    private var queueCheckJob: Job? = null
    private var lazyPlaybackJob: Job? = null
    private var radioJob: Job? = null
    private var streamInfoResolutionJob: Job? = null
    private var lastRadioSeedSongId: String? = null
    private val radioMutex = kotlinx.coroutines.sync.Mutex()
    private val replenishmentMutex = kotlinx.coroutines.sync.Mutex()
    private var unshuffledQueueSnapshot: List<Song>? = null

    init {
        // Immediately restore previously played song and queue on startup
        scope.launch {
            restoreSavedPlaybackState()
        }

        // Keep infinite queue mode aligned with user settings
        scope.launch {
            runCatching {
                settingsRepository.get().autoQueueSimilar.collect { enabled ->
                    _infiniteModeEnabled.value = enabled
                }
            }
        }
    }

    private suspend fun restoreSavedPlaybackState() {
        if (_currentSong.value != null) return
        val saved = playbackStateDataStore.getSavedPlaybackStateOnce() ?: return
        val song = saved.song ?: return

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

    private fun persistCurrentState(song: Song?, queue: List<Song>, queueIndex: Int, positionMs: Long) {
        if (song == null) return
        scope.launch(Dispatchers.IO) {
            playbackStateDataStore.savePlaybackState(
                song = song,
                positionMs = positionMs,
                queue = queue,
                queueIndex = queueIndex,
                shuffleEnabled = _shuffleEnabled.value,
                repeatMode = _repeatMode.value
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK STATE FLOWS
    // ═══════════════════════════════════════════════════════════════
    
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

    // Apple Music-style: current stream quality info
    private val _currentStreamInfo = MutableStateFlow<AudioStreamInfo?>(null)
    val currentStreamInfo: StateFlow<AudioStreamInfo?> = _currentStreamInfo.asStateFlow()

    private val _songCompletedEvents = MutableSharedFlow<SongCompletedEvent>(extraBufferCapacity = 64)
    val songCompletedEvents: SharedFlow<SongCompletedEvent> = _songCompletedEvents.asSharedFlow()

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═══════════════════════════════════════════════════════════════
    
    // Cache of stream URLs by song ID
    // Keep cache small — YouTube URLs expire after ~6h, no point hoarding stale ones.
    private val streamUrlCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 50
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

    // Track pending seek target to handle MediaController client-side stale timeline race conditions
    private var pendingSeekIndex: Int? = null

    // Guard queue refill while lazy queue hydration is still adding known songs.
    private val lazyQueueHydrationJobs = AtomicInteger(0)

    // Monotonic session id to ignore stale async queue work after new playback starts.
    private val queueSessionId = AtomicInteger(0)

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
                    val pos = c.currentPosition
                    if (pos >= 0) {
                        scope.launch {
                            playbackStateDataStore.updatePosition(pos)
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
                startPositionUpdates()
                _playbackError.value = null
                // Check queue when playback resumes
                checkQueueAndRequestMore()
            } else if (!activeState) {
                stopPositionUpdates()
                controller?.let { c ->
                    val pos = c.currentPosition
                    if (pos >= 0) {
                        scope.launch {
                            playbackStateDataStore.updatePosition(pos)
                        }
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    updateDuration()
                    _playbackError.value = null
                }
                Player.STATE_ENDED -> {
                    _isBuffering.value = false
                    _isPlaying.value = false
                    // Emit song completed event for the last song in the queue
                    lastSongId?.let { videoId ->
                        scope.launch {
                            _songCompletedEvents.emit(SongCompletedEvent(videoId, System.currentTimeMillis()))
                        }
                    }
                    lastSongId = null

                    // 1. Advance through controller if it already has next preloaded items
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
                    if (curIdx < songQueue.size - 1) {
                        Log.d(TAG, "STATE_ENDED: Advancing songQueue to index ${curIdx + 1} (${songQueue.size} songs total)")
                        skipToQueueItem(curIdx + 1)
                        return
                    } else if (songQueue.isNotEmpty() && _repeatMode.value == Player.REPEAT_MODE_ALL) {
                        Log.d(TAG, "STATE_ENDED: Repeat All active, looping to start")
                        skipToQueueItem(0)
                        return
                    } else if (_infiniteModeEnabled.value) {
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
                    scope.launch {
                        _songCompletedEvents.emit(SongCompletedEvent(prevSongId, System.currentTimeMillis()))
                    }
                }
                lastSongId = newSongId

                var foundIndex = -1
                synchronized(songQueue) {
                    var foundSong: Song? = null
                    for ((i, s) in songQueue.withIndex()) {
                        if (s.id == newSongId) {
                            foundSong = s
                            foundIndex = i
                            break
                        }
                    }
                    val current = foundSong ?: mediaItem.toSong()
                    _currentSong.value = current
                    _currentQueueIndex.value = foundIndex
                    persistCurrentState(current, songQueue.toList(), foundIndex, 0L)
                }
                
                // Check if we need more songs after transition
                // This is key for YouTube Music continuous playback
                checkQueueAndRequestMore()

                // Pre-resolve upcoming tracks for zero-gap playback
                if (foundIndex >= 0) {
                    preResolveUpcomingTracks(foundIndex)
                }

                // Resolve and apply stream metadata (Dolby Atmos, bitrate, codec) for new track
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
            _shuffleEnabled.value = shuffleModeEnabled
            _currentSong.value?.let { current ->
                persistCurrentState(current, _queue.value, _currentQueueIndex.value, _currentPosition.value)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}, code: ${error.errorCode}")
            _playbackError.value = "Playback error: ${error.errorCodeName}"
            _isBuffering.value = false
            
            // Check if this is an HTTP error (likely expired URL)
            if (isHttpError(error)) {
                Log.d(TAG, "HTTP error detected, requesting URL refresh...")
                _currentSong.value?.let { song ->
                    onStreamUrlExpired?.invoke(song.id)
                }
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

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val controller = mediaController ?: return
            val pendingIndex = pendingSeekIndex
            if (pendingIndex != null) {
                if (pendingIndex < controller.mediaItemCount) {
                    pendingSeekIndex = null
                    isWaitingForNextTrack = false
                    Log.d(TAG, "Executed pending seek to index $pendingIndex after timeline update. New count: ${controller.mediaItemCount}")
                    controller.seekTo(pendingIndex, 0L)
                    controller.prepare()
                    controller.play()
                } else {
                    Log.d(TAG, "Timeline updated, but index $pendingIndex is still >= count ${controller.mediaItemCount}")
                }
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
        if (mediaController != null) {
            Log.d(TAG, "Already connected")
            return
        }

        Log.d(TAG, "Connecting to PlaybackService...")
        
        val sessionToken = SessionToken(
            context, 
            ComponentName(context, PlaybackService::class.java)
        )
        
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)
                syncState()
                Log.d(TAG, "Connected to PlaybackService")
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Disconnect from PlaybackService
     */
    fun disconnect() {
        stopPositionUpdates()
        stopQueueCheck()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
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
     * Asynchronously resolve stream info (including Dolby Atmos indicators)
     * and update the AudioEngine state for the active song.
     */
    fun resolveAndApplyStreamInfo(songId: String) {
        streamInfoResolutionJob?.cancel()
        streamInfoResolutionJob = scope.launch(Dispatchers.IO) {
            try {
                val result = songRepository.get().getStreamWithInfo(songId, com.sonicmusic.app.domain.model.StreamQuality.BEST)
                if (result.isSuccess) {
                    val (_, info) = result.getOrThrow()
                    // Verify the active song hasn't changed while resolving
                    if (_currentSong.value?.id == songId) {
                        withContext(Dispatchers.Main) {
                            audioEngine.updateStreamInfo(info)
                        }
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
        
        // Immediately reset previous song's stream & Dolby Atmos state
        audioEngine.clearStreamInfo()

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
        Log.d(TAG, "Playing: ${song.title}")

        lazyPlaybackJob?.cancel()
        queueSessionId.incrementAndGet()
        isWaitingForNextTrack = false
        pendingSeekIndex = null
        
        scope.launch {
            val source = songSourceResolver.getSongStreamSource(song.id)
            val finalUrl = when (source) {
                is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                    _playbackError.value = "Song not available offline"
                    _isBuffering.value = false
                    return@launch
                }
            }

            // Cache the URL
            streamUrlCache[song.id] = finalUrl
            
            // Clear and set new queue
            songQueue.clear()
            songQueue.add(song)
            _queue.value = songQueue.toList()
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
            
            // Resolve and apply stream metadata (Dolby Atmos, bitrate, codec)
            resolveAndApplyStreamInfo(song.id)

            // ViTune: Immediately request more songs for infinite queue
            checkQueueAndRequestMore()
        }
    }

    /**
     * Replace the current media item's stream URL in-place and resume playback.
     * This fixes the bug where refreshing an expired stream URL cleared the queue.
     */
    fun playSongInPlace(song: Song, streamUrl: String) {
        Log.d(TAG, "Replacing stream URL in-place: ${song.title}")

        lazyPlaybackJob?.cancel()
        
        scope.launch {
            val source = songSourceResolver.getSongStreamSource(song.id)
            val finalUrl = when (source) {
                is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                    _playbackError.value = "Song not available offline"
                    return@launch
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
                    resolveAndApplyStreamInfo(song.id)
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

        if (invalidatePendingLazyLoads) {
            lazyPlaybackJob?.cancel()
            queueSessionId.incrementAndGet()
        }
        isWaitingForNextTrack = false
        pendingSeekIndex = null

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
        songQueue.clear()
        songQueue.addAll(playableEntries.map { it.second })
        _queue.value = songQueue.toList()
        reachedEndOfQueue = false

        val mediaItems = playableEntries.map { (_, song, url) ->
            createMediaItem(song, url)
        }

        mediaController?.apply {
            setMediaItems(mediaItems, startPlayableIndex, 0)
            prepare()
            play()
        }

        _currentSong.value = songQueue.getOrNull(startPlayableIndex)
        _currentQueueIndex.value = startPlayableIndex

        val skippedCount = songs.size - songQueue.size
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
        songRepository: com.sonicmusic.app.domain.repository.SongRepository = this.songRepository.get(),
        playWhenReady: Boolean = true,
        initialPositionMs: Long = 0L
    ) {
        if (songs.isEmpty()) return

        if (mediaController == null) {
            Log.e(TAG, "Cannot play: Service not connected")
            _playbackError.value = "Service not connected" 
            connect() // Try to reconnect
            return
        }

        lazyPlaybackJob?.cancel()
        val sessionId = queueSessionId.incrementAndGet()
        isWaitingForNextTrack = false
        pendingSeekIndex = null

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
                val source = songSourceResolver.getSongStreamSource(targetSong.id)
                if (sessionId != queueSessionId.get()) {
                    Log.d(TAG, "Ignoring stale lazy play success for old session")
                    return@launch
                }

                val targetUrl = when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                        _playbackError.value = "Song not available offline"
                        _isBuffering.value = false
                        return@launch
                    }
                }

                streamUrlCache[targetSong.id] = targetUrl

                // 3. Play the target song on MediaController
                val targetMediaItem = createMediaItem(targetSong, targetUrl)
                mediaController?.apply {
                    setMediaItems(listOf(targetMediaItem), 0, initialPositionMs)
                    prepare()
                    if (playWhenReady) {
                        play()
                    }
                }

                _isBuffering.value = false

                // 4. Resolve stream info with confirmed Dolby Atmos detection for target song
                resolveAndApplyStreamInfo(targetSong.id)

                // 5. Pre-resolve immediate next tracks for zero-gap playback
                preResolveUpcomingTracks(safeStartIndex)

                // 6. Background gentle hydration: fetch remaining songs in queue with delay to preserve foreground bandwidth
                if (songs.size > 1) {
                    hydrateQueueRemaining(
                        songs = songs,
                        startIndex = safeStartIndex,
                        songRepository = songRepository,
                        expectedSessionId = sessionId
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in lazy playback", e)
                _playbackError.value = "Error: ${e.message}"
                _isBuffering.value = false
            }
        }
    }

    /**
     * Play a single song and dynamically generate fresh YouTube Music recommendations
     * based on that song, loading INITIAL_RECOMMENDATION_COUNT (15 songs total) into the queue.
     */
    fun playSongWithRadio(song: Song) {
        radioJob?.cancel()
        lastRadioSeedSongId = song.id
        activeRadioContinuationToken = null

        // 1. Immediate UI feedback
        preparePlayback(song)

        // 2. Clear old queue and set initial single song queue
        synchronized(songQueue) {
            songQueue.clear()
            songQueue.add(song)
        }
        _queue.value = listOf(song)
        _currentSong.value = song
        _currentQueueIndex.value = 0

        // 3. Start lazy playback of the seed song
        playSongsLazy(
            songs = listOf(song),
            startIndex = 0,
            songRepository = songRepository.get()
        )

        // 4. Asynchronously fetch fresh recommendations based on the seed song
        radioJob = scope.launch(Dispatchers.IO) {
            try {
                val qRepo = queueRepository.get()
                qRepo.clearQueue()
                if (qRepo is QueueRepositoryImpl) {
                    qRepo.markSongAsQueued(song.id)
                }

                Log.d(TAG, "Fetching fresh radio recommendations for ${song.title} (${song.id})")

                // Primary source: YouTube Music native Radio Mix (RDAMVM)
                val radioResult = youTubeiService.get().getRadioMix(song.id).getOrNull()
                val candidateList = if (radioResult != null && radioResult.first.isNotEmpty()) {
                    activeRadioContinuationToken = radioResult.second
                    radioResult.first
                } else {
                    val upNextResult = youTubeiService.get().getUpNext(song.id).getOrNull()
                    if (upNextResult != null && upNextResult.first.isNotEmpty()) {
                        activeRadioContinuationToken = upNextResult.second
                        upNextResult.first
                    } else {
                        qRepo.getRelatedSongs(song.id, TARGET_RECOMMENDATIONS).getOrNull().orEmpty()
                    }
                }

                val finalCandidates = candidateList
                    .asSequence()
                    .filter { it.id.isNotBlank() && it.id != song.id }
                    .distinctBy { it.id }
                    .take(TARGET_RECOMMENDATIONS)
                    .toList()

                if (finalCandidates.isNotEmpty() && lastRadioSeedSongId == song.id && isActive) {
                    val currentSessionId = queueSessionId.get()
                    withContext(Dispatchers.Main) {
                        if (lastRadioSeedSongId != song.id) return@withContext
                        synchronized(songQueue) {
                            val current = _currentSong.value ?: song
                            songQueue.clear()
                            songQueue.add(current)
                            finalCandidates.forEach { rec ->
                                if (songQueue.none { it.id == rec.id }) {
                                    songQueue.add(rec)
                                }
                            }
                            while (songQueue.size > MAXIMUM_QUEUE_RECOMMENDATIONS) {
                                songQueue.removeAt(songQueue.lastIndex)
                            }
                        }
                        _queue.value = songQueue.toList()
                        _currentQueueIndex.value = 0
                        persistCurrentState(_currentSong.value, songQueue.toList(), 0, _currentPosition.value)
                    }

                    if (qRepo is QueueRepositoryImpl) {
                        finalCandidates.forEach { qRepo.markSongAsQueued(it.id) }
                    }

                    // Background hydration of upcoming stream URLs
                    preResolveUpcomingTracks(0)
                    hydrateQueueRemaining(
                        songs = songQueue.toList(),
                        startIndex = 0,
                        songRepository = songRepository.get(),
                        expectedSessionId = currentSessionId
                    )
                    Log.d(TAG, "Radio queue initialized with ${songQueue.size} songs for ${song.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching radio recommendations for ${song.title}", e)
            }
        }
    }

    /**
     * Synchronizes ExoPlayer's upcoming media items to mirror songQueue starting
     * from currentQueueIndex + 1.
     * Never interrupts the currently playing media item.
     */
    fun syncUpcomingMediaItems() {
        val controller = mediaController ?: return
        val currentIdx = _currentQueueIndex.value
        if (currentIdx !in songQueue.indices) return

        val currentPlayingCtrlIdx = controller.currentMediaItemIndex
        val upcomingQueueSongs = songQueue.drop(currentIdx + 1).take(5)

        // Remove all unplayed media items following currentPlayingCtrlIdx
        while (controller.mediaItemCount > currentPlayingCtrlIdx + 1) {
            controller.removeMediaItem(currentPlayingCtrlIdx + 1)
        }

        // Add upcoming items that have resolved stream URLs in order
        val itemsToAdd = upcomingQueueSongs.mapNotNull { song ->
            val url = streamUrlCache[song.id]
            if (url != null) createMediaItem(song, url) else null
        }

        if (itemsToAdd.isNotEmpty()) {
            controller.addMediaItems(itemsToAdd)
        }
    }

    /**
     * Hydrate remaining songs in background.
     * Pre-resolves upcoming tracks and syncs forward playlist into ExoPlayer.
     * Preceding history tracks are cached in streamUrlCache only and never queued ahead of playing song.
     */
    private fun hydrateQueueRemaining(
        songs: List<Song>,
        startIndex: Int,
        songRepository: com.sonicmusic.app.domain.repository.SongRepository,
        expectedSessionId: Int
    ) {
        if (songs.size <= 1) return
        if (expectedSessionId != queueSessionId.get()) return
        lazyQueueHydrationJobs.incrementAndGet()

        scope.launch(Dispatchers.IO) {
            try {
                val upcomingSongs = songs.drop(startIndex + 1)
                val precedingSongs = songs.take(startIndex)

                // 1. Upcoming tracks hydration (high priority)
                for (batch in upcomingSongs.chunked(3)) {
                    if (!isActive || expectedSessionId != queueSessionId.get()) break
                    delay(150)

                    val urlMap = mutableMapOf<String, String>()
                    coroutineScope {
                        batch.map { song ->
                            async {
                                try {
                                    val source = songSourceResolver.getSongStreamSource(song.id)
                                    val url = when (source) {
                                        is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                                        is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                                        is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                                    }
                                    if (url != null) {
                                        synchronized(urlMap) { urlMap[song.id] = url }
                                    }
                                    Unit
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to get URL for ${song.id}")
                                }
                            }
                        }.awaitAll()
                    }

                    if (urlMap.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (expectedSessionId != queueSessionId.get()) return@withContext
                            updateStreamUrls(urlMap)
                            syncUpcomingMediaItems()
                        }
                    }
                }

                // 2. Preceding history tracks hydration (cached for instant back navigation, NEVER added to player)
                for (batch in precedingSongs.chunked(4)) {
                    if (!isActive || expectedSessionId != queueSessionId.get()) break
                    val urlMap = mutableMapOf<String, String>()
                    coroutineScope {
                        batch.map { song ->
                            async {
                                try {
                                    val source = songSourceResolver.getSongStreamSource(song.id)
                                    val url = when (source) {
                                        is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                                        is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                                        is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                                    }
                                    if (url != null) {
                                        synchronized(urlMap) { urlMap[song.id] = url }
                                    }
                                    Unit
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to cache history URL for ${song.id}")
                                }
                            }
                        }.awaitAll()
                    }

                    if (urlMap.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (expectedSessionId != queueSessionId.get()) return@withContext
                            updateStreamUrls(urlMap)
                        }
                    }
                }

                Log.d(TAG, "Finished background queue hydration for session $expectedSessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Error in queue hydration", e)
            } finally {
                val remaining = lazyQueueHydrationJobs.decrementAndGet()
                if (remaining <= 0) {
                    lazyQueueHydrationJobs.set(0)
                    withContext(Dispatchers.Main) {
                        checkQueueAndRequestMore()
                    }
                }
            }
        }
    }

    /**
     * Add songs to queue with background URL fetching
     * 
     * @param songs Songs to add
     * @param songRepository Repository to fetch URLs
     * @param isQueueAlreadyPopulated True only when these songs are already present in local queue state.
     */
    fun addSongsToQueueLazy(
        songs: List<Song>, 
        songRepository: com.sonicmusic.app.domain.repository.SongRepository,
        isQueueAlreadyPopulated: Boolean = false,
        expectedSessionId: Int? = null
    ) {
        if (songs.isEmpty()) return
        val targetSessionId = expectedSessionId ?: queueSessionId.get()
        if (targetSessionId != queueSessionId.get()) return
        lazyQueueHydrationJobs.incrementAndGet()
        
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Background fetching URLs for ${songs.size} songs...")
                
                for (batch in songs.chunked(8)) {
                    if (!isActive) break
                    if (targetSessionId != queueSessionId.get()) {
                        Log.d(TAG, "Stopping stale lazy hydration for old session")
                        break
                    }
                    
                    val urlMap = mutableMapOf<String, String>()
                    val validSongs = mutableListOf<Song>()
                    
                    // Parallel URL fetching within each batch
                    coroutineScope {
                        batch.map { song ->
                            async {
                                try {
                                    val source = songSourceResolver.getSongStreamSource(song.id)
                                    val url = when (source) {
                                        is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                                        is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                                        is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                                    }
                                    if (url != null) {
                                        synchronized(urlMap) {
                                            urlMap[song.id] = url
                                            validSongs.add(song)
                                        }
                                    }
                                    Unit
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to get URL for ${song.id}")
                                }
                            }
                        }.awaitAll()
                    }
                    
                    if (validSongs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (targetSessionId != queueSessionId.get()) return@withContext

                            if (isQueueAlreadyPopulated) {
                                // Keep cache and local queue synced even when caller pre-populated queue metadata.
                                updateStreamUrls(urlMap)

                                val existingLocalIds = songQueue.asSequence().map { it.id }.toMutableSet()
                                val controller = mediaController
                                val existingControllerIds = mutableSetOf<String>()
                                if (controller != null) {
                                    repeat(controller.mediaItemCount) { index ->
                                        existingControllerIds.add(controller.getMediaItemAt(index).mediaId)
                                    }
                                }

                                var localQueueChanged = false
                                validSongs.forEach validSongLoop@{ song ->
                                    val streamUrl = urlMap[song.id] ?: return@validSongLoop

                                    if (!existingLocalIds.contains(song.id)) {
                                        songQueue.add(song)
                                        existingLocalIds.add(song.id)
                                        localQueueChanged = true
                                    }

                                    if (song.id != _currentSong.value?.id && !existingControllerIds.contains(song.id)) {
                                        controller?.addMediaItem(createMediaItem(song, streamUrl))
                                        existingControllerIds.add(song.id)
                                    }
                                }

                                if (localQueueChanged) {
                                    _queue.value = songQueue.toList()
                                }

                                if (controller != null && (controller.playbackState == Player.STATE_ENDED || isWaitingForNextTrack)) {
                                    val nextIndex = controller.currentMediaItemIndex + 1
                                    if (nextIndex < controller.mediaItemCount) {
                                        isWaitingForNextTrack = false
                                        pendingSeekIndex = null
                                        Log.d(TAG, "Lazy-load resume at index $nextIndex (count=${controller.mediaItemCount})")
                                        controller.seekTo(nextIndex, 0L)
                                        controller.prepare()
                                        controller.play()
                                    } else {
                                        pendingSeekIndex = nextIndex
                                        Log.d(TAG, "Stale count in lazy load. Deferred seek to $nextIndex.")
                                        scope.launch {
                                            delay(200)
                                            val pending = pendingSeekIndex
                                            if (pending != null && pending < controller.mediaItemCount) {
                                                pendingSeekIndex = null
                                                isWaitingForNextTrack = false
                                                Log.d(TAG, "Delayed lazy-load resume at index $pending (count=${controller.mediaItemCount})")
                                                controller.seekTo(pending, 0L)
                                                controller.prepare()
                                                controller.play()
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Normal add to queue (append)
                                addToQueue(validSongs, urlMap)
                            }
                        }
                    }
                    
                    // No inter-batch delay — push songs to player as fast as possible
                }
                 Log.d(TAG, "Finished background URL fetching")
            } catch (e: Exception) {
                Log.e(TAG, "Error in background fetch", e)
            } finally {
                val remainingJobs = lazyQueueHydrationJobs.decrementAndGet()
                if (remainingJobs <= 0) {
                    lazyQueueHydrationJobs.set(0)
                    withContext(Dispatchers.Main) {
                        // Re-evaluate recommendations once hydration has finished.
                        checkQueueAndRequestMore()
                    }
                }
            }
        }
    }

    /**
     * Add songs to the end of the queue
     * 
     * @param songs Songs to add
     * @param streamUrls Map of song IDs to stream URLs
     */
    fun addToQueue(songs: List<Song>, streamUrls: Map<String, String>) {
        if (songs.isEmpty()) return
        
        Log.d(TAG, "Adding ${songs.size} songs to queue")

        val existingIds = songQueue.asSequence().map { it.id }.toMutableSet()
        val newSongs = mutableListOf<Song>()
        // ── OPTIMIZATION 7: Batched MediaController IPC ──────────
        // Build all MediaItems first, then add in one batch call.
        val newMediaItems = mutableListOf<MediaItem>()

        songs.forEach { song ->
            if (existingIds.contains(song.id)) return@forEach

            val streamUrl = streamUrls[song.id] ?: return@forEach
            streamUrlCache[song.id] = streamUrl
            songQueue.add(song)
            newSongs.add(song)
            newMediaItems.add(createMediaItem(song, streamUrl))
            existingIds.add(song.id)
        }

        if (newSongs.isEmpty()) {
            Log.d(TAG, "No playable songs were appended to queue")
            return
        }

        // Single IPC call instead of N individual addMediaItem() calls
        mediaController?.let { controller ->
            val countBefore = controller.mediaItemCount
            val nextIndex = controller.currentMediaItemIndex + 1
            controller.addMediaItems(newMediaItems)

            if (controller.playbackState == Player.STATE_ENDED || isWaitingForNextTrack) {
                // Try immediate resume first
                if (nextIndex < controller.mediaItemCount) {
                    isWaitingForNextTrack = false
                    pendingSeekIndex = null
                    Log.d(TAG, "Resuming playback at index $nextIndex (count=${controller.mediaItemCount})")
                    controller.seekTo(nextIndex, 0L)
                    controller.prepare()
                    controller.play()
                } else {
                    // MediaController IPC may not reflect the new items yet.
                    // Set pending seek AND schedule a delayed retry as safety net
                    // in case onTimelineChanged fires before we set pendingSeekIndex.
                    pendingSeekIndex = nextIndex
                    Log.d(TAG, "MediaController count stale ($countBefore + ${newMediaItems.size} items added). Deferred seek to $nextIndex.")
                    scope.launch {
                        delay(200) // Give IPC time to sync
                        val pending = pendingSeekIndex
                        if (pending != null && pending < controller.mediaItemCount) {
                            pendingSeekIndex = null
                            isWaitingForNextTrack = false
                            Log.d(TAG, "Delayed resume at index $pending (count=${controller.mediaItemCount})")
                            controller.seekTo(pending, 0L)
                            controller.prepare()
                            controller.play()
                        }
                    }
                }
            }
        }

        // ── OPTIMIZATION 5: Deduplicated queue emission ───────────
        emitQueueIfChanged()
        reachedEndOfQueue = false
        
        Log.d(TAG, "Added ${newSongs.size} songs to queue, total: ${songQueue.size}")
    }


    /**
     * Update stream URLs for existing songs (used for background loading)
     */
    fun updateStreamUrls(newUrls: Map<String, String>) {
        newUrls.forEach { (id, url) ->
            streamUrlCache[id] = url
        }
    }

    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        val controller = mediaController
        if (controller == null) {
            connect()
            return
        }

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
            val pos = controller.currentPosition
            if (pos >= 0) {
                scope.launch {
                    playbackStateDataStore.updatePosition(pos)
                }
            }
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
        val controller = mediaController ?: return
        val currentIndex = _currentQueueIndex.value
        val totalQueueSize = songQueue.size

        if (currentIndex < totalQueueSize - 1) {
            // Next song exists in queue
            skipToQueueItem(currentIndex + 1)
        } else if (totalQueueSize > 0 && controller.repeatMode == Player.REPEAT_MODE_ALL) {
            // Loop back to start of queue in repeat all mode
            skipToQueueItem(0)
        } else if (_infiniteModeEnabled.value) {
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
        val controller = mediaController ?: return
        val currentIndex = _currentQueueIndex.value

        if (controller.currentPosition > 3000) {
            controller.seekTo(0)
        } else if (currentIndex > 0) {
            skipToQueueItem(currentIndex - 1)
        } else if (songQueue.isNotEmpty() && controller.repeatMode == Player.REPEAT_MODE_ALL) {
            skipToQueueItem(songQueue.size - 1)
        } else {
            controller.seekTo(0)
        }
    }

    /**
     * Force skip to previous track ignoring restart threshold
     */
    fun forceSkipToPrevious() {
        val controller = mediaController ?: return
        val currentIndex = _currentQueueIndex.value

        if (currentIndex > 0) {
            skipToQueueItem(currentIndex - 1)
        } else if (songQueue.isNotEmpty() && controller.repeatMode == Player.REPEAT_MODE_ALL) {
            skipToQueueItem(songQueue.size - 1)
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
        if (index !in songQueue.indices) return
        val targetSong = songQueue[index]
        val controller = mediaController ?: return

        pendingSeekIndex = null
        isWaitingForNextTrack = false

        Log.d(TAG, "Skipping to queue item: $index (${targetSong.title})")
        _currentSong.value = targetSong
        _currentQueueIndex.value = index
        persistCurrentState(targetSong, songQueue.toList(), index, 0L)
        audioEngine.clearStreamInfo()
        resolveAndApplyStreamInfo(targetSong.id)

        val cachedUrl = streamUrlCache[targetSong.id]
        if (cachedUrl != null) {
            val mediaItem = createMediaItem(targetSong, cachedUrl)
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
            _isPlaying.value = true
            _isBuffering.value = false
            syncUpcomingMediaItems()
            preResolveUpcomingTracks(index)
        } else {
            // Track is in songQueue but stream URL not cached yet — resolve on demand
            Log.d(TAG, "Queue item $index (${targetSong.title}) not in cache — resolving on demand")
            _isBuffering.value = true

            scope.launch {
                try {
                    val source = songSourceResolver.getSongStreamSource(targetSong.id)
                    val url = when (source) {
                        is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                        is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                        is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                    }

                    if (url != null) {
                        streamUrlCache[targetSong.id] = url
                        if (_currentQueueIndex.value == index) {
                            val mediaItem = createMediaItem(targetSong, url)
                            controller.setMediaItem(mediaItem)
                            controller.prepare()
                            controller.play()
                            _isPlaying.value = true
                            _isBuffering.value = false
                            syncUpcomingMediaItems()
                            preResolveUpcomingTracks(index)
                        }
                    } else {
                        _playbackError.value = "Failed to load track"
                        _isBuffering.value = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving track on demand for skipToQueueItem", e)
                    _playbackError.value = "Error: ${e.message}"
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
        val currentIdx = _currentQueueIndex.value
        val safeTargetIndex = (currentIdx + 1).coerceIn(0, songQueue.size)

        // Check if song already exists in queue
        val existingIndex = songQueue.indexOfFirst { it.id == song.id }
        if (existingIndex != -1) {
            if (existingIndex == currentIdx) {
                // Already currently playing
                return
            }
            songQueue.removeAt(existingIndex)
        }

        val finalInsertIndex = if (existingIndex != -1 && existingIndex < safeTargetIndex) {
            currentIdx.coerceIn(0, songQueue.size)
        } else {
            (currentIdx + 1).coerceIn(0, songQueue.size)
        }

        synchronized(songQueue) {
            songQueue.add(finalInsertIndex, song)
        }
        _queue.value = songQueue.toList()
        persistCurrentState(_currentSong.value, songQueue.toList(), _currentQueueIndex.value, _currentPosition.value)

        Log.d(TAG, "Inserted play next at index $finalInsertIndex: ${song.title}")

        // Preload stream URL in background and sync with player
        scope.launch(Dispatchers.IO) {
            try {
                val source = songSourceResolver.getSongStreamSource(song.id)
                val url = when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                }
                if (url != null) {
                    streamUrlCache[song.id] = url
                    withContext(Dispatchers.Main) {
                        syncUpcomingMediaItems()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve stream for play next (${song.title})", e)
            }
        }
    }

    fun insertPlayNext(song: Song, streamUrl: String) {
        streamUrlCache[song.id] = streamUrl
        insertPlayNext(song)
    }

    fun insertPlayNextLazy(song: Song, songRepository: com.sonicmusic.app.domain.repository.SongRepository) {
        insertPlayNext(song)
    }

    /**
     * Add single song to the end of the queue.
     * Instant UI update + background stream preloading.
     */
    fun addSongToQueue(song: Song) {
        if (songQueue.isEmpty() && _currentSong.value == null) {
            playSongWithRadio(song)
            return
        }

        synchronized(songQueue) {
            songQueue.add(song)
        }
        _queue.value = songQueue.toList()
        persistCurrentState(_currentSong.value, songQueue.toList(), _currentQueueIndex.value, _currentPosition.value)

        Log.d(TAG, "Added song to end of queue: ${song.title} (size: ${songQueue.size})")

        scope.launch(Dispatchers.IO) {
            try {
                val source = songSourceResolver.getSongStreamSource(song.id)
                val url = when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                }
                if (url != null) {
                    streamUrlCache[song.id] = url
                    withContext(Dispatchers.Main) {
                        syncUpcomingMediaItems()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve stream for added song (${song.title})", e)
            }
        }
    }

    fun addToQueue(song: Song, streamUrl: String) {
        streamUrlCache[song.id] = streamUrl
        addSongToQueue(song)
    }

    /**
     * Pre-resolve upcoming tracks ahead of time for zero-gap playback
     */
    private fun preResolveUpcomingTracks(currentIndex: Int) {
        if (currentIndex < 0) return
        val upcoming = listOfNotNull(
            songQueue.getOrNull(currentIndex + 1),
            songQueue.getOrNull(currentIndex + 2),
            songQueue.getOrNull(currentIndex + 3)
        )
        if (upcoming.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            upcoming.forEach { song ->
                if (!streamUrlCache.containsKey(song.id)) {
                    try {
                        val source = songSourceResolver.getSongStreamSource(song.id)
                        val url = when (source) {
                            is com.sonicmusic.app.data.repository.SongSource.Local -> source.filePath
                            is com.sonicmusic.app.data.repository.SongSource.Remote -> source.streamUrl
                            is com.sonicmusic.app.data.repository.SongSource.Unavailable -> null
                        }
                        if (url != null) {
                            streamUrlCache[song.id] = url
                            withContext(Dispatchers.Main) {
                                syncUpcomingMediaItems()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pre-resolve failed for ${song.id}: ${e.message}")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        syncUpcomingMediaItems()
                    }
                }
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
            scope.launch {
                playbackStateDataStore.updatePosition(position)
            }
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
        val pos = positionMs.coerceAtLeast(0)
        _currentPosition.value = pos
        val dur = _duration.value
        if (dur > 0) {
            _progress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > 0) {
            controller.seekTo(pos)
        }
        scope.launch {
            playbackStateDataStore.updatePosition(pos)
        }
    }

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
        _shuffleEnabled.value = newShuffle
        mediaController?.shuffleModeEnabled = newShuffle

        val currentIdx = _currentQueueIndex.value
        if (songQueue.isEmpty()) return

        if (newShuffle) {
            // Save original queue snapshot to restore later
            unshuffledQueueSnapshot = songQueue.toList()

            // Keep played/current songs, shuffle upcoming songs
            val head = songQueue.take(currentIdx + 1)
            val upcoming = songQueue.drop(currentIdx + 1).shuffled()
            synchronized(songQueue) {
                songQueue.clear()
                songQueue.addAll(head + upcoming)
            }
            _queue.value = songQueue.toList()
            persistCurrentState(_currentSong.value, songQueue.toList(), currentIdx, _currentPosition.value)
            syncUpcomingMediaItems()
        } else {
            // Restore from snapshot if available
            val snapshot = unshuffledQueueSnapshot
            if (snapshot != null) {
                val currentSongId = _currentSong.value?.id
                val originalCurrentIdx = snapshot.indexOfFirst { it.id == currentSongId }
                if (originalCurrentIdx != -1) {
                    val head = songQueue.take(currentIdx + 1)
                    val playedIds = head.map { it.id }.toSet()
                    val unplayedFromOriginal = snapshot.drop(originalCurrentIdx + 1).filterNot { playedIds.contains(it.id) }
                    synchronized(songQueue) {
                        songQueue.clear()
                        songQueue.addAll(head + unplayedFromOriginal)
                    }
                    _queue.value = songQueue.toList()
                    persistCurrentState(_currentSong.value, songQueue.toList(), currentIdx, _currentPosition.value)
                    syncUpcomingMediaItems()
                }
                unshuffledQueueSnapshot = null
            }
        }
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
        queueSessionId.incrementAndGet()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        songQueue.clear()
        streamUrlCache.clear()
        lazyQueueHydrationJobs.set(0)
        unshuffledQueueSnapshot = null
        _queue.value = emptyList()
        _currentQueueIndex.value = -1
        _currentSong.value = null
        reachedEndOfQueue = false
        isWaitingForNextTrack = false
        pendingSeekIndex = null
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
        if (fromIndex !in songQueue.indices || toIndex !in songQueue.indices) return

        val currentSongId = _currentSong.value?.id
        synchronized(songQueue) {
            val item = songQueue.removeAt(fromIndex)
            songQueue.add(toIndex, item)
        }
        _queue.value = songQueue.toList()

        // Update current index if current song was moved or affected
        if (currentSongId != null) {
            val newIndex = songQueue.indexOfFirst { it.id == currentSongId }
            if (newIndex != -1) {
                _currentQueueIndex.value = newIndex
            }
        }

        persistCurrentState(_currentSong.value, songQueue.toList(), _currentQueueIndex.value, _currentPosition.value)
        syncUpcomingMediaItems()
    }

    /**
     * Remove item from queue
     * Handles removing currently playing track or history/upcoming tracks smoothly.
     */
    fun removeFromQueue(index: Int) {
        if (index !in songQueue.indices) return

        val currentIdx = _currentQueueIndex.value
        val removedSong: Song

        synchronized(songQueue) {
            removedSong = songQueue.removeAt(index)
        }
        streamUrlCache.remove(removedSong.id)

        when {
            // Case A: Removing the currently playing song
            index == currentIdx -> {
                if (songQueue.isEmpty()) {
                    clearQueue()
                } else {
                    val targetIndex = index.coerceAtMost(songQueue.size - 1)
                    _queue.value = songQueue.toList()
                    skipToQueueItem(targetIndex)
                    checkQueueAndRequestMore()
                }
            }
            // Case B: Removing a preceding / history song
            index < currentIdx -> {
                _currentQueueIndex.value = currentIdx - 1
                _queue.value = songQueue.toList()
                persistCurrentState(_currentSong.value, songQueue.toList(), _currentQueueIndex.value, _currentPosition.value)
                checkQueueAndRequestMore()
            }
            // Case C: Removing an upcoming song
            else -> {
                _queue.value = songQueue.toList()
                persistCurrentState(_currentSong.value, songQueue.toList(), currentIdx, _currentPosition.value)
                syncUpcomingMediaItems()
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
    
    /**
     * Check if queue needs more songs and prefetch them before the queue runs dry.
     * Uses songQueue size instead of MediaController count to prevent false depletion triggers.
     */
    private fun checkQueueAndRequestMore() {
        if (!_infiniteModeEnabled.value) return
        val currentSong = _currentSong.value ?: return

        if (lazyQueueHydrationJobs.get() > 0) {
            return
        }

        // Debounce: only check once every 1.5 seconds
        val now = System.currentTimeMillis()
        if (now - lastQueueCheckTime < QUEUE_CHECK_COOLDOWN_MS) {
            return
        }

        val currentIndex = _currentQueueIndex.value
        val totalItems = songQueue.size
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
     * Appends PREFETCH_BATCH_SIZE (10) songs at a time up to MAXIMUM_QUEUE_RECOMMENDATIONS (50).
     *
     * Never prunes previous songs from index 0. Playback is never blocked.
     */
    private fun prefetchQueueRecommendations(currentSongId: String, playImmediatelyWhenReady: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (!replenishmentMutex.tryLock()) {
                Log.d(TAG, "Queue prefetch already running, skipping duplicate trigger")
                return@launch
            }
            _isLoadingMore.value = true
            try {
                val currentIndex = _currentQueueIndex.value
                val totalItems = songQueue.size
                val remainingAhead = (totalItems - currentIndex - 1).coerceAtLeast(0)

                if (remainingAhead > PREFETCH_THRESHOLD && !reachedEndOfQueue && !playImmediatelyWhenReady) {
                    return@launch
                }

                if (totalItems >= MAXIMUM_QUEUE_RECOMMENDATIONS && !playImmediatelyWhenReady) {
                    Log.d(TAG, "Queue already at maximum recommendations ($totalItems >= $MAXIMUM_QUEUE_RECOMMENDATIONS)")
                    return@launch
                }

                val currentSessionId = queueSessionId.get()
                Log.d(TAG, "Prefetching recommendations (remaining: $remainingAhead, contToken=${!activeRadioContinuationToken.isNullOrBlank()})...")

                // 1. Fetch fresh recommendations batch from InnerTube continuation or RadioMix
                var fetchedSongs = emptyList<Song>()
                val token = activeRadioContinuationToken
                if (!token.isNullOrBlank()) {
                    val continuationResult = youTubeiService.get().getQueueContinuation(token).getOrNull()
                    if (continuationResult != null && continuationResult.first.isNotEmpty()) {
                        fetchedSongs = continuationResult.first
                        activeRadioContinuationToken = continuationResult.second
                        Log.d(TAG, "Fetched ${fetchedSongs.size} continuation tracks (hasNext=${!continuationResult.second.isNullOrBlank()})")
                    }
                }

                if (fetchedSongs.isEmpty()) {
                    val seedId = lastRadioSeedSongId ?: currentSongId
                    val radioResult = youTubeiService.get().getRadioMix(seedId).getOrNull()
                    if (radioResult != null && radioResult.first.isNotEmpty()) {
                        fetchedSongs = radioResult.first
                        activeRadioContinuationToken = radioResult.second
                    } else {
                        val upNextResult = youTubeiService.get().getUpNext(seedId).getOrNull()
                        if (upNextResult != null && upNextResult.first.isNotEmpty()) {
                            fetchedSongs = upNextResult.first
                            activeRadioContinuationToken = upNextResult.second
                        }
                    }
                }

                // 2. Strict Deduplication & Filtering
                val existingIds = synchronized(songQueue) { songQueue.map { it.id }.toSet() }
                val newCandidates = fetchedSongs
                    .asSequence()
                    .filter { it.id.isNotBlank() && it.id != currentSongId && !existingIds.contains(it.id) }
                    .distinctBy { it.id }
                    .take(PREFETCH_BATCH_SIZE)
                    .toList()

                if (newCandidates.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (currentSessionId != queueSessionId.get()) {
                            Log.d(TAG, "Playback session changed during prefetch, discarding batch")
                            return@withContext
                        }

                        // Append to songQueue without pruning previous songs!
                        synchronized(songQueue) {
                            newCandidates.forEach { candidate ->
                                if (songQueue.none { it.id == candidate.id }) {
                                    songQueue.add(candidate)
                                }
                            }
                            while (songQueue.size > MAXIMUM_QUEUE_RECOMMENDATIONS) {
                                songQueue.removeAt(songQueue.lastIndex)
                            }
                        }
                        _queue.value = songQueue.toList()
                        persistCurrentState(_currentSong.value, songQueue.toList(), _currentQueueIndex.value, _currentPosition.value)

                        val qRepo = queueRepository.get()
                        if (qRepo is QueueRepositoryImpl) {
                            newCandidates.forEach { qRepo.markSongAsQueued(it.id) }
                        }

                        // Background hydration of upcoming stream URLs
                        hydrateQueueRemaining(
                            songs = songQueue.toList(),
                            startIndex = _currentQueueIndex.value,
                            songRepository = songRepository.get(),
                            expectedSessionId = currentSessionId
                        )
                        Log.d(TAG, "Prefetched and appended ${newCandidates.size} songs. Total queue: ${songQueue.size}")

                        if (playImmediatelyWhenReady) {
                            val nextIndex = _currentQueueIndex.value + 1
                            if (nextIndex in songQueue.indices) {
                                Log.d(TAG, "playImmediatelyWhenReady=true: Advancing to index $nextIndex")
                                skipToQueueItem(nextIndex)
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "No fresh unique recommendations returned")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in queue prefetch (playback continues uninterrupted)", e)
            } finally {
                _isLoadingMore.value = false
                replenishmentMutex.unlock()
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
        val newSize = songQueue.size
        // Fast-path: if size or boundary elements differ, always emit
        if (current.size != newSize ||
            current.firstOrNull()?.id != songQueue.firstOrNull()?.id ||
            current.lastOrNull()?.id != songQueue.lastOrNull()?.id
        ) {
            _queue.value = songQueue.toList()
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
                            if (newProgress != _progress.value) _progress.value = newProgress
                        }

                        // Periodically save playback position every 5s while playing
                        val now = System.currentTimeMillis()
                        if (now - lastSavedTime >= 5000L && pos > 0) {
                            lastSavedTime = now
                            playbackStateDataStore.updatePosition(pos)
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
            runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<com.sonicmusic.app.domain.model.ArtistRef>>(artistsJson)
            }.getOrDefault(emptyList())
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
    fun getQueue(): List<Song> = songQueue.toList()
    
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
