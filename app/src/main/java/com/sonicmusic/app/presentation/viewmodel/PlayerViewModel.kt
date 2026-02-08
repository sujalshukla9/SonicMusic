package com.sonicmusic.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository
) : ViewModel() {

    // Expose state from PlayerServiceConnection
    val currentSong: StateFlow<Song?> = playerServiceConnection.currentSong
    val isPlaying: StateFlow<Boolean> = playerServiceConnection.isPlaying
    val progress: StateFlow<Float> = playerServiceConnection.progress
    val currentPosition: StateFlow<Long> = playerServiceConnection.currentPosition
    val duration: StateFlow<Long> = playerServiceConnection.duration
    val queue: StateFlow<List<Song>> = playerServiceConnection.queue
    val repeatMode: StateFlow<Int> = playerServiceConnection.repeatMode
    val shuffleEnabled: StateFlow<Boolean> = playerServiceConnection.shuffleEnabled

    // Local state for like status
    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    // Loading state for stream URL fetching
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Connect to PlaybackService when ViewModel is created
        playerServiceConnection.connect()
        
        // Observe current song to update like status
        viewModelScope.launch {
            currentSong.collect { song ->
                song?.let {
                    _isLiked.value = songRepository.isLiked(it.id)
                }
            }
        }
    }

    /**
     * Play a song - fetches stream URL and starts playback
     */
    fun playSong(song: Song, startPlaying: Boolean = true) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                // Get stream URL
                val streamUrlResult = songRepository.getStreamUrl(song.id, StreamQuality.HIGH)
                
                streamUrlResult.onSuccess { streamUrl ->
                    if (startPlaying) {
                        playerServiceConnection.playSong(song, streamUrl)
                    }
                    // Update like status
                    _isLiked.value = songRepository.isLiked(song.id)
                }.onFailure { exception ->
                    Log.e("PlayerViewModel", "Failed to get stream URL", exception)
                    _error.value = "Failed to play song: ${exception.message}"
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error playing song", e)
                _error.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Add song to queue
     */
    fun addToQueue(song: Song) {
        viewModelScope.launch {
            songRepository.getStreamUrl(song.id, StreamQuality.HIGH)
                .onSuccess { streamUrl ->
                    playerServiceConnection.addToQueue(song, streamUrl)
                }
                .onFailure { exception ->
                    _error.value = "Failed to add to queue: ${exception.message}"
                }
        }
    }

    /**
     * Play/Pause toggle
     */
    fun togglePlayPause() {
        playerServiceConnection.togglePlayPause()
    }

    /**
     * Seek to position (0f to 1f)
     */
    fun seekTo(position: Float) {
        playerServiceConnection.seekTo(position)
    }

    /**
     * Skip to next track
     */
    fun skipToNext() {
        playerServiceConnection.skipNext()
    }

    /**
     * Skip to previous track
     */
    fun skipToPrevious() {
        playerServiceConnection.skipPrevious()
    }

    /**
     * Toggle like status
     */
    fun toggleLike() {
        viewModelScope.launch {
            currentSong.value?.let { song ->
                if (_isLiked.value) {
                    songRepository.unlikeSong(song.id)
                    _isLiked.value = false
                } else {
                    songRepository.likeSong(song.id)
                    _isLiked.value = true
                }
            }
        }
    }

    /**
     * Toggle repeat mode (Off -> All -> One -> Off)
     */
    fun toggleRepeatMode() {
        playerServiceConnection.toggleRepeatMode()
    }

    /**
     * Toggle shuffle
     */
    fun toggleShuffle() {
        playerServiceConnection.toggleShuffle()
    }

    /**
     * Set queue and optionally start playing
     */
    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        viewModelScope.launch {
            playerServiceConnection.clearQueue()
            
            if (songs.isNotEmpty() && startIndex < songs.size) {
                // Play the first song at startIndex
                playSong(songs[startIndex])
                
                // Add remaining songs to queue
                songs.drop(startIndex + 1).forEach { song ->
                    addToQueue(song)
                }
            }
        }
    }

    /**
     * Clear error
     */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Note: Don't disconnect here as service should persist
    }
}