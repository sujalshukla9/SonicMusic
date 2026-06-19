package com.sonicmusic.app.service

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
import com.sonicmusic.app.player.playback.PlaybackService
import com.sonicmusic.app.player.audio.AudioEngine
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
    private val songSourceResolver: com.sonicmusic.app.data.repository.SongSourceResolver
) {
    companion object {
        private const val TAG = "SonicConnection"
        private const val QUEUE_LOW_THRESHOLD = 19 // Trigger fetch earlier to maintain 20 songs
        private const val QUEUE_CHECK_COOLDOWN_MS = 1000L // Fast queue replenishment
        private const val PRELOAD_THRESHOLD = 7 // Start preloading earlier
        private const val POSITION_UPDATE_INTERVAL_MS = 250L // ~4fps — visually smooth for a progress bar, 3x fewer recompositions
    }

    // MediaController for communication with PlaybackService
    private var mediaController: MediaController? = null
    fun getMediaController(): MediaController? = mediaController
    private var controllerFuture: ListenableFuture<MediaController>? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null
    private var queueCheckJob: Job? = null
    private var lazyPlaybackJob: Job? = null

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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startPositionUpdates()
                _playbackError.value = null
                // Check queue when playback resumes
                checkQueueAndRequestMore()
            } else {
                stopPositionUpdates()
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

                    // ── AUTO-ADVANCE FIX ─────────────────────────────
                    // ExoPlayer enters STATE_ENDED when the last item in
                    // its playlist finishes.  Songs may have been lazily
                    // appended to the controller *after* the playlist was
                    // originally set (background URL resolution).  Try to
                    // advance to the next item immediately before falling
                    // back to requesting more recommendations.
                    val ctrl = mediaController
                    if (ctrl != null) {
                        val nextIdx = ctrl.currentMediaItemIndex + 1
                        if (nextIdx < ctrl.mediaItemCount) {
                            Log.d(TAG, "▶️ STATE_ENDED: Auto-advancing to index $nextIdx (count=${ctrl.mediaItemCount})")
                            ctrl.seekTo(nextIdx, 0L)
                            ctrl.prepare()
                            ctrl.play()
                            return // Successfully advanced — skip queue refill
                        }
                    }
                    // ─────────────────────────────────────────────────

                    reachedEndOfQueue = true
                    // Check if we need more songs
                    checkQueueAndRequestMore()
                }
                Player.STATE_IDLE -> {
                    _isBuffering.value = false
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                val prevSongId = lastSongId
                val newSongId = mediaItem.mediaId
                if (prevSongId != null && prevSongId != newSongId) {
                    scope.launch {
                        _songCompletedEvents.emit(SongCompletedEvent(prevSongId, System.currentTimeMillis()))
                    }
                }
                lastSongId = newSongId

                // ── OPTIMIZATION 6: Single-pass find + index ────────────
                // Combined find() + indexOfFirst() into one loop.
                synchronized(songQueue) {
                    var foundSong: Song? = null
                    var foundIndex = -1
                    for ((i, s) in songQueue.withIndex()) {
                        if (s.id == newSongId) {
                            foundSong = s
                            foundIndex = i
                            break
                        }
                    }
                    _currentSong.value = foundSong ?: mediaItem.toSong()
                    _currentQueueIndex.value = foundIndex
                }
                
                // Check if we need more songs after transition
                // This is key for ViTune-style continuous playback
                checkQueueAndRequestMore()
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
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "❌ Player error: ${error.message}, code: ${error.errorCode}")
            _playbackError.value = "Playback error: ${error.errorCodeName}"
            _isBuffering.value = false
            
            // Check if this is an HTTP error (likely expired URL)
            if (isHttpError(error)) {
                Log.d(TAG, "🔄 HTTP error detected, requesting URL refresh...")
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
                    Log.d(TAG, "🎯 Executed pending seek to index $pendingIndex after timeline update. New count: ${controller.mediaItemCount}")
                    controller.seekTo(pendingIndex, 0L)
                    controller.prepare()
                    controller.play()
                } else {
                    Log.d(TAG, "⏳ Timeline updated, but index $pendingIndex is still >= count ${controller.mediaItemCount}")
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

        Log.d(TAG, "🔌 Connecting to PlaybackService...")
        
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
                Log.d(TAG, "✅ Connected to PlaybackService")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection failed", e)
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
        Log.d(TAG, "🔌 Disconnected from PlaybackService")
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

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Prepare for playback (immediate UI feedback)
     * Sets the current song and loading state BEFORE fetching the URL
     */
    fun preparePlayback(song: Song) {
        Log.d(TAG, "⏳ Preparing playback for: ${song.title}")
        
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
        Log.d(TAG, "▶️ Playing: ${song.title}")

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
            
            // ViTune: Immediately request more songs for infinite queue
            checkQueueAndRequestMore()
        }
    }

    /**
     * Replace the current media item's stream URL in-place and resume playback.
     * This fixes the bug where refreshing an expired stream URL cleared the queue.
     */
    fun playSongInPlace(song: Song, streamUrl: String) {
        Log.d(TAG, "🔄 Replacing stream URL in-place: ${song.title}")

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
                    Log.d(TAG, "🔄 Replaced stream URL in-place for ${song.title} at index $i")
                    updated = true
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
        Log.d(TAG, "▶️ Playing queue: ${songs.size} songs, starting at $safeRequestedIndex")

        val playableEntries = songs.mapIndexedNotNull { originalIndex, song ->
            streamUrls[song.id]?.let { url -> Triple(originalIndex, song, url) }
        }

        if (playableEntries.isEmpty()) {
            Log.e(TAG, "❌ No valid media items")
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
            Log.w(TAG, "⚠️ Skipped $skippedCount songs without playable stream URLs")
        }

        // ViTune: Check if we need more songs immediately
        checkQueueAndRequestMore()
    }

    /**
     * Play a list of songs lazily (fetches URLs in background) - ViTune Style
     * 
     * @param songs List of songs to play
     * @param startIndex Index to start playing from
     * @param songRepository Repository to fetch URLs from
     */
    fun playSongsLazy(
        songs: List<Song>, 
        startIndex: Int = 0, 
        songRepository: com.sonicmusic.app.domain.repository.SongRepository
    ) {
        if (songs.isEmpty()) return

        if (mediaController == null) {
            Log.e(TAG, "❌ Cannot play: Service not connected")
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
            val orderedQueue = buildList {
                add(targetSong)
                addAll(songs.drop(safeStartIndex + 1))
                addAll(songs.take(safeStartIndex))
            }
            Log.d(
                TAG,
                "▶️ Lazy playing queue: ${orderedQueue.size} songs, starting with ${targetSong.title}"
            )

            // 1. Fetch only the first song immediately for instant playback
            try {
                // Expose the initial song immediately; remaining songs are appended as URLs resolve.
                songQueue.clear()
                songQueue.add(targetSong)
                _queue.value = songQueue.toList()
                _currentSong.value = targetSong
                _currentQueueIndex.value = 0
                _isBuffering.value = true // Show loading
                
                // Fetch first song
                val source = songSourceResolver.getSongStreamSource(targetSong.id)
                if (sessionId != queueSessionId.get()) {
                    Log.d(TAG, "⏹️ Ignoring stale lazy play success for old session")
                    return@launch
                }

                when (source) {
                    is com.sonicmusic.app.data.repository.SongSource.Local -> {
                        playWithQueue(
                            songs = orderedQueue,
                            streamUrls = mapOf(targetSong.id to source.filePath),
                            startIndex = 0,
                            invalidatePendingLazyLoads = false
                        )
                        
                        // 2. Fetch remaining in background
                        val remainingSongs = orderedQueue.drop(1)
                        if (remainingSongs.isNotEmpty()) {
                            addSongsToQueueLazy(
                                songs = remainingSongs,
                                songRepository = songRepository,
                                isQueueAlreadyPopulated = false,
                                expectedSessionId = sessionId
                            )
                        }
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Remote -> {
                        playWithQueue(
                            songs = orderedQueue,
                            streamUrls = mapOf(targetSong.id to source.streamUrl),
                            startIndex = 0,
                            invalidatePendingLazyLoads = false
                        )
                        
                        // 2. Fetch remaining in background
                        val remainingSongs = orderedQueue.drop(1)
                        if (remainingSongs.isNotEmpty()) {
                            addSongsToQueueLazy(
                                songs = remainingSongs,
                                songRepository = songRepository,
                                isQueueAlreadyPopulated = false,
                                expectedSessionId = sessionId
                            )
                        }
                    }
                    is com.sonicmusic.app.data.repository.SongSource.Unavailable -> {
                        _playbackError.value = "Song not available offline"
                        _isBuffering.value = false
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in lazy Playback", e)
                _playbackError.value = "Error: ${e.message}"
                _isBuffering.value = false
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
                Log.d(TAG, "⏳ Background fetching URLs for ${songs.size} songs...")
                
                for (batch in songs.chunked(8)) {
                    if (!isActive) break
                    if (targetSessionId != queueSessionId.get()) {
                        Log.d(TAG, "⏹️ Stopping stale lazy hydration for old session")
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
                                    Log.w(TAG, "⚠️ Failed to get URL for ${song.id}")
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
                                        Log.d(TAG, "▶️ Lazy-load resume at index $nextIndex (count=${controller.mediaItemCount})")
                                        controller.seekTo(nextIndex, 0L)
                                        controller.prepare()
                                        controller.play()
                                    } else {
                                        pendingSeekIndex = nextIndex
                                        Log.d(TAG, "⏳ Stale count in lazy load. Deferred seek to $nextIndex.")
                                        scope.launch {
                                            delay(200)
                                            val pending = pendingSeekIndex
                                            if (pending != null && pending < controller.mediaItemCount) {
                                                pendingSeekIndex = null
                                                isWaitingForNextTrack = false
                                                Log.d(TAG, "▶️ Delayed lazy-load resume at index $pending (count=${controller.mediaItemCount})")
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
                 Log.d(TAG, "✅ Finished background URL fetching")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in background fetch", e)
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
        
        Log.d(TAG, "➕ Adding ${songs.size} songs to queue")

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
            Log.d(TAG, "⚠️ No playable songs were appended to queue")
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
                    Log.d(TAG, "▶️ Resuming playback at index $nextIndex (count=${controller.mediaItemCount})")
                    controller.seekTo(nextIndex, 0L)
                    controller.prepare()
                    controller.play()
                } else {
                    // MediaController IPC may not reflect the new items yet.
                    // Set pending seek AND schedule a delayed retry as safety net
                    // in case onTimelineChanged fires before we set pendingSeekIndex.
                    pendingSeekIndex = nextIndex
                    Log.d(TAG, "⏳ MediaController count stale ($countBefore + ${newMediaItems.size} items added). Deferred seek to $nextIndex.")
                    scope.launch {
                        delay(200) // Give IPC time to sync
                        val pending = pendingSeekIndex
                        if (pending != null && pending < controller.mediaItemCount) {
                            pendingSeekIndex = null
                            isWaitingForNextTrack = false
                            Log.d(TAG, "▶️ Delayed resume at index $pending (count=${controller.mediaItemCount})")
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
        
        Log.d(TAG, "✅ Added ${newSongs.size} songs to queue, total: ${songQueue.size}")
    }

    /**
     * Add a single song to the queue
     */
    fun addToQueue(song: Song, streamUrl: String) {
        addToQueue(listOf(song), mapOf(song.id to streamUrl))
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
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_ENDED) {
                    controller.seekTo(0)
                }
                controller.play()
            }
        }
    }

    /**
     * Skip to next track
     */
    fun skipToNext() {
        mediaController?.let { controller ->
            if (controller.hasNextMediaItem()) {
                pendingSeekIndex = null
                isWaitingForNextTrack = false
                controller.seekToNext()
            } else {
                // Request more songs if at end
                isWaitingForNextTrack = true
                _currentSong.value?.let { song ->
                    onQueueNeedsMoreSongs?.invoke(song.id)
                }
            }
        }
    }

    /**
     * Skip to previous track or restart current
     */
    fun skipToPrevious() {
        mediaController?.let { controller ->
            pendingSeekIndex = null
            isWaitingForNextTrack = false
            if (controller.currentPosition > 3000) {
                controller.seekTo(0)
            } else if (controller.hasPreviousMediaItem()) {
                controller.seekToPrevious()
            }
        }
    }

    /**
     * Force skip to previous track ignoring restart threshold
     */
    fun forceSkipToPrevious() {
        mediaController?.let { controller ->
            pendingSeekIndex = null
            isWaitingForNextTrack = false
            if (controller.hasPreviousMediaItem()) {
                controller.seekToPrevious()
            } else {
                controller.seekTo(0)
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
        mediaController?.let { controller ->
            val cachedDuration = _duration.value
            val controllerDuration = controller.duration
            val durationToUse = if (cachedDuration > 0) cachedDuration else controllerDuration
            
            if (durationToUse > 0) {
                val position = (progress * durationToUse).toLong().coerceIn(0, durationToUse)
                
                // Set seeking flag to pause updates
                isSeeking = true
                
                // Immediately update UI state
                _progress.value = progress.coerceIn(0f, 1f)
                _currentPosition.value = position
                
                controller.seekTo(position)
                Log.d(TAG, "Seeking to ${position}ms (${(progress * 100).toInt()}%)")
                
                // Cancel previous reset job to prevent premature isSeeking clear on rapid seeks
                seekResetJob?.cancel()
                seekResetJob = scope.launch {
                    delay(500) // 500ms grace period
                    isSeeking = false
                }
            }
        }
    }

    /**
     * Seek to absolute position in milliseconds
     */
    fun seekToPosition(positionMs: Long) {
        mediaController?.seekTo(positionMs.coerceAtLeast(0))
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
     */
    fun toggleShuffle() {
        mediaController?.let { controller ->
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
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
        mediaController?.clearMediaItems()
        songQueue.clear()
        streamUrlCache.clear()
        lazyQueueHydrationJobs.set(0)
        _queue.value = emptyList()
        _currentQueueIndex.value = -1
        _currentSong.value = null
        reachedEndOfQueue = false
        isWaitingForNextTrack = false
        pendingSeekIndex = null
    }

    /**
     * Reorder item in queue
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        
        mediaController?.let { controller ->
            // Move in player
            controller.moveMediaItem(fromIndex, toIndex)
            
            // Move in local queue
            if (fromIndex in songQueue.indices && toIndex in songQueue.indices) {
                val item = songQueue.removeAt(fromIndex)
                songQueue.add(toIndex, item)
                _queue.value = songQueue.toList()
                
                // Update current index if needed
                val currentId = _currentSong.value?.id
                if (currentId != null) {
                    val newIndex = songQueue.indexOfFirst { it.id == currentId }
                    if (newIndex != -1) {
                        _currentQueueIndex.value = newIndex
                    }
                }
            }
        }
    }

    /**
     * Remove item from queue
     */
    fun removeFromQueue(index: Int) {
        mediaController?.let { controller ->
            if (index in 0 until controller.mediaItemCount) {
                // If removing current song, player handles skipping automatically
                controller.removeMediaItem(index)
                
                // Remove from local queue
                if (index in songQueue.indices) {
                    val removedSong = songQueue.removeAt(index)
                    streamUrlCache.remove(removedSong.id)
                    _queue.value = songQueue.toList()
                    
                    // Update current index
                    val currentId = _currentSong.value?.id
                    if (currentId != null) {
                        val newIndex = songQueue.indexOfFirst { it.id == currentId }
                        if (newIndex != -1) {
                            _currentQueueIndex.value = newIndex
                        }
                    }
                    
                    // Check if we need more songs after removal
                    checkQueueAndRequestMore()
                }
            }
        }
    }

    /**
     * Skip to a specific item in the queue
     * 
     * @param index The index to skip to
     */
    fun skipToQueueItem(index: Int) {
        mediaController?.let { controller ->
            if (index in 0 until controller.mediaItemCount) {
                Log.d(TAG, "⏭️ Skipping to queue item: $index")
                pendingSeekIndex = null
                isWaitingForNextTrack = false
                controller.seekTo(index, 0)
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
     * Check if queue needs more songs and request them
     * ViTune-style: Aggressive preloading
     */
    private fun checkQueueAndRequestMore() {
        val controller = mediaController ?: return
        val callback = onQueueNeedsMoreSongs ?: return
        val currentSong = _currentSong.value ?: return

        if (lazyQueueHydrationJobs.get() > 0) {
            return
        }
        
        // Debounce: only check once every few seconds
        val now = System.currentTimeMillis()
        if (now - lastQueueCheckTime < QUEUE_CHECK_COOLDOWN_MS) {
            return
        }
        
        val currentIndex = controller.currentMediaItemIndex
        val totalItems = controller.mediaItemCount
        val remaining = totalItems - currentIndex - 1
        

        
        // ViTune-style: Request more songs early for seamless playback
        if (remaining <= QUEUE_LOW_THRESHOLD || reachedEndOfQueue) {
            lastQueueCheckTime = now
            reachedEndOfQueue = false
            
            Log.d(TAG, "🔄 Queue low or ended, requesting more songs...")
            _isLoadingMore.value = true
            callback(currentSong.id)
            
            // Reset loading state after a delay
            queueCheckJob?.cancel()
            queueCheckJob = scope.launch {
                delay(5000)
                _isLoadingMore.value = false
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
            _isPlaying.value = controller.isPlaying
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
        return Song(
            id = mediaId,
            title = mediaMetadata.title?.toString() ?: "Unknown",
            artist = mediaMetadata.artist?.toString() ?: "Unknown Artist",
            artistId = artistId,
            album = albumName ?: mediaMetadata.albumTitle?.toString(),
            duration = 0,
            thumbnailUrl = mediaMetadata.artworkUri?.toString() ?: "",
            albumBrowseId = albumBrowseId,
            albumName = albumName ?: mediaMetadata.albumTitle?.toString()
        )
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
