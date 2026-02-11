package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentPlaylistId: Long? = null

    fun loadPlaylist(playlistId: Long) {
        if (currentPlaylistId == playlistId) return
        currentPlaylistId = playlistId

        viewModelScope.launch {
            _isLoading.value = true

            // Load playlist details with songs
            playlistRepository.getPlaylistById(playlistId)
                .onSuccess { playlist ->
                    _playlist.value = playlist
                    _songs.value = playlist.songs
                }
                .onFailure {
                    _playlist.value = null
                    _songs.value = emptyList()
                }

            _isLoading.value = false
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
        }
    }

    fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) return

        viewModelScope.launch {
            val firstSong = songs.first()
            songRepository.getStreamUrl(firstSong.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    val urlMap = mutableMapOf<String, String>()
                    urlMap[firstSong.id] = streamUrl

                    // Fetch URLs for remaining songs
                    songs.drop(1).forEach { song ->
                        songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                            .onSuccess { url ->
                                urlMap[song.id] = url
                            }
                    }

                    playerServiceConnection.playWithQueue(songs, urlMap, 0)
                }
        }
    }

    fun shufflePlay(songs: List<Song>) {
        val shuffled = songs.shuffled()
        playAll(shuffled)
    }

    fun removeSongFromPlaylist(songId: String) {
        currentPlaylistId?.let { playlistId ->
            viewModelScope.launch {
                playlistRepository.removeSongFromPlaylist(playlistId, songId)
            }
        }
    }

    fun deletePlaylist() {
        currentPlaylistId?.let { playlistId ->
            viewModelScope.launch {
                playlistRepository.deletePlaylist(playlistId)
            }
        }
    }

    fun addSongToPlaylist(songId: String) {
        currentPlaylistId?.let { playlistId ->
            viewModelScope.launch {
                playlistRepository.addSongToPlaylist(playlistId, songId)
            }
        }
    }
}
