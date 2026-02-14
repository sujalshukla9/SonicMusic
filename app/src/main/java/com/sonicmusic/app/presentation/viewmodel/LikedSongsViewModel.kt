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
        // Find index of this song in the list
        val index = _likedSongs.value.indexOfFirst { it.id == song.id }
        if (index != -1) {
            playerServiceConnection.playSongsLazy(_likedSongs.value, index, songRepository)
        }
    }

    fun playAll() {
        val songs = _likedSongs.value
        if (songs.isNotEmpty()) {
            playerServiceConnection.playSongsLazy(songs, 0, songRepository)
        }
    }

    fun shufflePlay() {
        val songs = _likedSongs.value
        if (songs.isNotEmpty()) {
            val shuffledSongs = songs.shuffled()
            playerServiceConnection.playSongsLazy(shuffledSongs, 0, songRepository)
        }
    }
}
