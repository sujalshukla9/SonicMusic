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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentPlaylistId: Long? = null

    fun loadPlaylist(playlistId: Long) {
        currentPlaylistId = playlistId

        viewModelScope.launch {
            refreshCurrentPlaylist(showLoading = true)
        }
    }

    /**
     * Tap a single song in the playlist → play it with the full playlist as queue
     * so next/previous continue with other songs in the list.
     */
    fun playSong(song: Song) {
        val allSongs = _songs.value
        val index = allSongs.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0

        viewModelScope.launch {
            songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    val urlMap = mutableMapOf(song.id to streamUrl)

                    // Fetch remaining song URLs in parallel
                    val remaining = allSongs.filterNot { it.id == song.id }
                    val results = withContext(Dispatchers.IO) {
                        remaining.map { s ->
                            async {
                                songRepository.getStreamUrl(s.id, StreamQuality.BEST)
                                    .getOrNull()?.let { url -> s.id to url }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    results.forEach { (id, url) -> urlMap[id] = url }

                    playerServiceConnection.playWithQueue(allSongs, urlMap, index)
                }
                .onFailure { e ->
                    _error.value = "Failed to play: ${e.message}"
                }
        }
    }

    /**
     * Play all songs in the playlist (parallel URL fetching).
     */
    fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) return

        viewModelScope.launch {
            val firstSong = songs.first()
            songRepository.getStreamUrl(firstSong.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    val urlMap = mutableMapOf(firstSong.id to streamUrl)

                    // Fetch URLs in parallel for the rest
                    val results = withContext(Dispatchers.IO) {
                        songs.drop(1).map { song ->
                            async {
                                songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                                    .getOrNull()?.let { url -> song.id to url }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    results.forEach { (id, url) -> urlMap[id] = url }

                    playerServiceConnection.playWithQueue(songs, urlMap, 0)
                }
                .onFailure { e ->
                    _error.value = "Failed to play: ${e.message}"
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
                    .onSuccess {
                        refreshCurrentPlaylist()
                    }
                    .onFailure { e ->
                        _error.value = "Failed to remove: ${e.message}"
                    }
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
                    .onSuccess {
                        refreshCurrentPlaylist()
                    }
                    .onFailure { e ->
                        _error.value = "Failed to add: ${e.message}"
                    }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun refreshCurrentPlaylist(showLoading: Boolean = false) {
        val playlistId = currentPlaylistId ?: return
        if (showLoading) _isLoading.value = true

        playlistRepository.getPlaylistById(playlistId)
            .onSuccess { playlist ->
                _playlist.value = playlist
                _songs.value = playlist.songs
            }
            .onFailure {
                _playlist.value = null
                _songs.value = emptyList()
            }

        if (showLoading) _isLoading.value = false
    }
}
