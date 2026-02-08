package com.sonicmusic.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
 * Manages the connection between UI components and PlaybackService.
 * Provides StateFlows for reactive playback state observation.
 */
@Singleton
class PlayerServiceConnection @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    // Playback state flows
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startProgressUpdates()
            } else {
                stopProgressUpdates()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { item ->
                _currentSong.value = item.toSong()
            }
            updateDuration()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> updateDuration()
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    stopProgressUpdates()
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }
    }

    /**
     * Connect to the PlaybackService
     */
    fun connect() {
        if (mediaController != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            mediaController?.addListener(playerListener)
            
            // Sync initial state
            syncCurrentState()
        }, MoreExecutors.directExecutor())
    }

    /**
     * Disconnect from the PlaybackService
     */
    fun disconnect() {
        stopProgressUpdates()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
    }

    /**
     * Play a song immediately
     */
    fun playSong(song: Song, streamUrl: String) {
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(android.net.Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()

        mediaController?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
        
        _currentSong.value = song
    }

    /**
     * Add a song to the queue
     */
    fun addToQueue(song: Song, streamUrl: String) {
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(android.net.Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()

        mediaController?.addMediaItem(mediaItem)
        _queue.value = _queue.value + song
    }

    /**
     * Play/Pause toggle
     */
    fun togglePlayPause() {
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        }
    }

    /**
     * Skip to next track
     */
    fun skipNext() {
        mediaController?.seekToNext()
    }

    /**
     * Skip to previous track
     */
    fun skipPrevious() {
        mediaController?.let { controller ->
            if (controller.currentPosition > 3000) {
                controller.seekTo(0)
            } else {
                controller.seekToPrevious()
            }
        }
    }

    /**
     * Seek to position (0f to 1f)
     */
    fun seekTo(progress: Float) {
        mediaController?.let { controller ->
            val position = (progress * controller.duration).toLong()
            controller.seekTo(position)
        }
    }

    /**
     * Seek to absolute position in ms
     */
    fun seekToPosition(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    /**
     * Toggle repeat mode
     */
    fun toggleRepeatMode() {
        mediaController?.let { controller ->
            val newMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = newMode
        }
    }

    /**
     * Toggle shuffle
     */
    fun toggleShuffle() {
        mediaController?.let { controller ->
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
        }
    }

    /**
     * Clear the queue
     */
    fun clearQueue() {
        mediaController?.clearMediaItems()
        _queue.value = emptyList()
    }

    private fun syncCurrentState() {
        mediaController?.let { controller ->
            _isPlaying.value = controller.isPlaying
            _repeatMode.value = controller.repeatMode
            _shuffleEnabled.value = controller.shuffleModeEnabled
            
            controller.currentMediaItem?.let { item ->
                _currentSong.value = item.toSong()
            }
            
            updateDuration()
            
            if (controller.isPlaying) {
                startProgressUpdates()
            }
        }
    }

    private fun updateDuration() {
        mediaController?.let { controller ->
            if (controller.duration > 0) {
                _duration.value = controller.duration
            }
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    val position = controller.currentPosition
                    val duration = controller.duration
                    _currentPosition.value = position
                    if (duration > 0) {
                        _duration.value = duration
                        _progress.value = position.toFloat() / duration.toFloat()
                    }
                }
                delay(500) // Update every 500ms
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
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
}
