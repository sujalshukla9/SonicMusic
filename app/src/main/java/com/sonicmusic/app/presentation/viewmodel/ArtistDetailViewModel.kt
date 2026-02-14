package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {

    private val _artistName = MutableStateFlow("")
    val artistName: StateFlow<String> = _artistName.asStateFlow()

    private val _songs = MutableStateFlow<List<PlaybackHistory>>(emptyList())
    val songs: StateFlow<List<PlaybackHistory>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loaded = false

    fun loadArtist(artist: String) {
        if (loaded && _artistName.value == artist) return
        loaded = true
        _artistName.value = artist

        viewModelScope.launch {
            _isLoading.value = true
            historyRepository.getSongsByArtist(artist).collect { history ->
                _songs.value = history
                _isLoading.value = false
            }
        }
    }

    fun playSong(history: PlaybackHistory) {
        val song = Song(
            id = history.songId,
            title = history.title,
            artist = history.artist,
            duration = 0,
            thumbnailUrl = history.thumbnailUrl
        )
        viewModelScope.launch {
            songRepository.getStreamUrl(history.songId, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
        }
    }

    fun playAll() {
        val allSongs = _songs.value
        if (allSongs.isEmpty()) return

        val first = allSongs.first()
        playSong(first)
        allSongs.drop(1).forEach { history ->
            val song = Song(
                id = history.songId,
                title = history.title,
                artist = history.artist,
                duration = 0,
                thumbnailUrl = history.thumbnailUrl
            )
            viewModelScope.launch {
                songRepository.getStreamUrl(history.songId, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        playerServiceConnection.addToQueue(song, streamUrl)
                    }
            }
        }
    }

    fun shufflePlay() {
        val shuffled = _songs.value.shuffled()
        if (shuffled.isEmpty()) return

        playSong(shuffled.first())
        shuffled.drop(1).forEach { history ->
            val song = Song(
                id = history.songId,
                title = history.title,
                artist = history.artist,
                duration = 0,
                thumbnailUrl = history.thumbnailUrl
            )
            viewModelScope.launch {
                songRepository.getStreamUrl(history.songId, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        playerServiceConnection.addToQueue(song, streamUrl)
                    }
            }
        }
    }
}
