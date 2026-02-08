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
import com.sonicmusic.app.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Player Service Connection
 * 
 * Manages connection between UI and PlaybackService.
 * Provides reactive StateFlows for all playback state.
 * 
 * Features:
 * - Automatic connection management
 * - Queue management with stream URL caching
 * - Position tracking
 * - Error handling with callbacks
 * - Infinite queue support via callback
 */
@Singleton
class PlayerServiceConnection @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PlayerServiceConnection"
        private const val QUEUE_LOW_THRESHOLD = 2 // Trigger fetch when this many songs left
        private const val QUEUE_CHECK_COOLDOWN_MS = 30000L // 30 seconds cooldown
    }

    // MediaController for communication with PlaybackService
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null

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

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═══════════════════════════════════════════════════════════════
    
    // Cache of stream URLs by song ID
    private val streamUrlCache = mutableMapOf<String, String>()
    
    // Queue of songs (mirrors player queue)
    private val songQueue = mutableListOf<Song>()
    
    // Callback for when queue needs more songs
    private var lastQueueCheckTime = 0L
    private var onQueueNeedsMoreSongs: ((String) -> Unit)? = null
    
    // Callback for when stream URL expires/fails (needs re-extraction)
    private var onStreamUrlExpired: ((String) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════
    // PLAYER LISTENER
    // ═══════════════════════════════════════════════════════════════
    
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startPositionUpdates()
                _playbackError.value = null
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "📊 Playback state: $playbackState")
            when (playbackState) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    updateDuration()
                }
                Player.STATE_ENDED -> {
                    _isBuffering.value = false
                    _isPlaying.value = false
                    // Check if we need more songs
                    checkQueueAndRequestMore()
                }
                Player.STATE_IDLE -> {
                    _isBuffering.value = false
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "🎵 Media transition: ${mediaItem?.mediaId}, reason: $reason")
            mediaItem?.let { item ->
                // Update current song from our queue
                val song = songQueue.find { it.id == item.mediaId }
                _currentSong.value = song ?: item.toSong()
                
                // Update queue index
                val index = songQueue.indexOfFirst { it.id == item.mediaId }
                _currentQueueIndex.value = index
                
                // DON'T check queue here - only check when playback ends
                // This prevents aggressive triggering on every track change
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
    }
    
    /**
     * Check if error is HTTP-related (403 Forbidden, 410 Gone, etc.)
     */
    private fun isHttpError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> true
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
     * Play a single song
     * 
     * @param song The song to play
     * @param streamUrl The streaming URL for the song
     */
    fun playSong(song: Song, streamUrl: String) {
        Log.d(TAG, "▶️ Playing: ${song.title}")
        
        // Cache the URL
        streamUrlCache[song.id] = streamUrl
        
        // Clear and set new queue
        songQueue.clear()
        songQueue.add(song)
        _queue.value = songQueue.toList()
        
        // Create and play media item
        val mediaItem = createMediaItem(song, streamUrl)
        
        mediaController?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
        
        _currentSong.value = song
        _currentQueueIndex.value = 0
    }

    /**
     * Play with a queue of songs
     * 
     * @param songs List of songs to play
     * @param streamUrls Map of song IDs to stream URLs
     * @param startIndex Index to start playing from
     */
    fun playWithQueue(songs: List<Song>, streamUrls: Map<String, String>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        Log.d(TAG, "▶️ Playing queue: ${songs.size} songs, starting at $startIndex")
        
        // Cache URLs
        streamUrls.forEach { (id, url) -> streamUrlCache[id] = url }
        
        // Build queue
        songQueue.clear()
        songQueue.addAll(songs)
        _queue.value = songQueue.toList()
        
        // Create media items
        val mediaItems = songs.mapNotNull { song ->
            streamUrls[song.id]?.let { url -> createMediaItem(song, url) }
        }
        
        if (mediaItems.isEmpty()) {
            Log.e(TAG, "❌ No valid media items")
            return
        }
        
        mediaController?.apply {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
            play()
        }
        
        _currentSong.value = songs.getOrNull(startIndex)
        _currentQueueIndex.value = startIndex
    }

    /**
     * Add songs to the end of the queue
     * 
     * @param songs Songs to add
     * @param streamUrls Map of song IDs to stream URLs
     */
    fun addToQueue(songs: List<Song>, streamUrls: Map<String, String>) {
        Log.d(TAG, "➕ Adding ${songs.size} songs to queue")
        
        songs.forEach { song ->
            streamUrls[song.id]?.let { url ->
                // Cache URL
                streamUrlCache[song.id] = url
                
                // Add to our queue
                songQueue.add(song)
                
                // Add to player
                mediaController?.addMediaItem(createMediaItem(song, url))
            }
        }
        
        _queue.value = songQueue.toList()
    }

    /**
     * Add a single song to the queue
     */
    fun addToQueue(song: Song, streamUrl: String) {
        addToQueue(listOf(song), mapOf(song.id to streamUrl))
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
                controller.seekToNext()
            } else {
                // Request more songs if at end
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
            if (controller.currentPosition > 3000) {
                controller.seekTo(0)
            } else if (controller.hasPreviousMediaItem()) {
                controller.seekToPrevious()
            }
        }
    }

    /**
     * Seek to position (0.0 to 1.0)
     */
    fun seekTo(progress: Float) {
        mediaController?.let { controller ->
            val position = (progress * controller.duration).toLong()
            controller.seekTo(position.coerceAtLeast(0))
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
        mediaController?.clearMediaItems()
        songQueue.clear()
        streamUrlCache.clear()
        _queue.value = emptyList()
        _currentQueueIndex.value = -1
        _currentSong.value = null
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
                val item = controller.getMediaItemAt(index)
                
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
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════
    
    private fun createMediaItem(song: Song, streamUrl: String): MediaItem {
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album ?: "")
                    .setArtworkUri(Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()
    }

    private fun syncState() {
        mediaController?.let { controller ->
            _isPlaying.value = controller.isPlaying
            _isBuffering.value = controller.playbackState == Player.STATE_BUFFERING
            _repeatMode.value = controller.repeatMode
            _shuffleEnabled.value = controller.shuffleModeEnabled
            
            controller.currentMediaItem?.let { item ->
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

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    val pos = controller.currentPosition
                    val dur = controller.duration
                    _currentPosition.value = pos
                    if (dur > 0) {
                        _duration.value = dur
                        _progress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun checkQueueAndRequestMore() {
        val controller = mediaController ?: return
        
        // Debounce: only check once every 30 seconds
        val now = System.currentTimeMillis()
        if (now - lastQueueCheckTime < QUEUE_CHECK_COOLDOWN_MS) {
            Log.d(TAG, "📊 Queue check skipped (cooldown)")
            return
        }
        
        val currentIndex = controller.currentMediaItemIndex
        val totalItems = controller.mediaItemCount
        val remaining = totalItems - currentIndex - 1
        
        Log.d(TAG, "📊 Queue check: $remaining songs remaining")
        
        if (remaining < QUEUE_LOW_THRESHOLD) {
            lastQueueCheckTime = now
            _currentSong.value?.let { song ->
                Log.d(TAG, "🔄 Queue low, requesting more songs...")
                onQueueNeedsMoreSongs?.invoke(song.id)
            }
        }
    }

    private fun MediaItem.toSong(): Song {
        return Song(
            id = mediaId,
            title = mediaMetadata.title?.toString() ?: "Unknown",
            artist = mediaMetadata.artist?.toString() ?: "Unknown Artist",
            album = mediaMetadata.albumTitle?.toString(),
            duration = 0,
            thumbnailUrl = mediaMetadata.artworkUri?.toString() ?: ""
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
     * Clear playback error
     */
    fun clearError() {
        _playbackError.value = null
    }
}
