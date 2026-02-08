package com.sonicmusic.app.presentation.viewmodel

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
class LikedSongsViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {

    private val _likedSongs = MutableStateFlow<List<Song>>(emptyList())
    val likedSongs: StateFlow<List<Song>> = _likedSongs.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadLikedSongs()
    }

    private fun loadLikedSongs() {
        viewModelScope.launch {
            _isLoading.value = true
            songRepository.getLikedSongs().collect { songs ->
                _likedSongs.value = songs
                _isLoading.value = false
            }
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            songRepository.getStreamUrl(song.id, StreamQuality.HIGH)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
        }
    }

    fun playAll() {
        val songs = _likedSongs.value
        if (songs.isNotEmpty()) {
            playSong(songs.first())
            // Add rest to queue
            songs.drop(1).forEach { song ->
                viewModelScope.launch {
                    songRepository.getStreamUrl(song.id, StreamQuality.HIGH)
                        .onSuccess { streamUrl ->
                            playerServiceConnection.addToQueue(song, streamUrl)
                        }
                }
            }
        }
    }

    fun shufflePlay() {
        val songs = _likedSongs.value.shuffled()
        if (songs.isNotEmpty()) {
            playSong(songs.first())
            songs.drop(1).forEach { song ->
                viewModelScope.launch {
                    songRepository.getStreamUrl(song.id, StreamQuality.HIGH)
                        .onSuccess { streamUrl ->
                            playerServiceConnection.addToQueue(song, streamUrl)
                        }
                }
            }
        }
    }
}
