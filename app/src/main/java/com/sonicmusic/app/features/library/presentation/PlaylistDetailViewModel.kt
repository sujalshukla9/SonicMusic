package com.sonicmusic.app.features.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.features.downloads.data.SongDownloadManager
import com.sonicmusic.app.data.local.entity.DownloadedSong
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
    private val songDownloadManager: SongDownloadManager,
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

    val downloadedSongs: StateFlow<List<DownloadedSong>> = songDownloadManager.getDownloadedSongs()
        .map { list -> list.filter { it.status == DownloadStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadPlaylist(playlistId: Long) {
        currentPlaylistId = playlistId

        viewModelScope.launch {
            refreshCurrentPlaylist(showLoading = true)
            
            // Auto sync YTM playlist if cache expired (> 1 hour)
            val current = _playlist.value
            if (current != null && !current.isLocal && !current.isYouTubeAccountPlaylist && current.remoteId != null) {
                val age = System.currentTimeMillis() - current.updatedAt
                val oneHour = 60 * 60 * 1000L
                if (age > oneHour) {
                    syncRemotePlaylist(current.remoteId)
                }
            }
        }
    }

    fun syncPlaylist() {
        val current = _playlist.value ?: return
        if (!current.isLocal && !current.isYouTubeAccountPlaylist && current.remoteId != null) {
            syncRemotePlaylist(current.remoteId)
        } else {
            viewModelScope.launch {
                refreshCurrentPlaylist(showLoading = true)
            }
        }
    }

    private fun syncRemotePlaylist(remoteId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            playlistRepository.fetchRemotePlaylist(remoteId)
                .onSuccess { (playlist, songs) ->
                    playlistRepository.saveRemotePlaylist(playlist, songs)
                        .onSuccess {
                            refreshCurrentPlaylist()
                        }
                        .onFailure { e ->
                            _error.value = "Failed to cache synced playlist: ${e.message}"
                        }
                }
                .onFailure { e ->
                    _error.value = "Failed to sync from YouTube Music: ${e.message}"
                }
            _isLoading.value = false
        }
    }

    fun playSong(song: Song) {
        // Play only the clicked song + fresh radio recommendations (not all playlist songs)
        playerServiceConnection.playSongWithRadio(song)
    }

    fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        playerServiceConnection.playSongsLazy(songs, 0, songRepository)
    }

    fun shufflePlay(songs: List<Song>) {
        if (songs.isEmpty()) return
        playerServiceConnection.playSongsLazy(songs.shuffled(), 0, songRepository)
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

    fun updatePlaylistOrder(songIds: List<String>) {
        currentPlaylistId?.let { playlistId ->
            viewModelScope.launch {
                playlistRepository.updatePlaylistOrder(playlistId, songIds)
                    .onSuccess {
                        refreshCurrentPlaylist()
                    }
                    .onFailure { e ->
                        _error.value = "Failed to update order: ${e.message}"
                    }
            }
        }
    }

    fun updatePlaylistMetadata(name: String, description: String?) {
        currentPlaylistId?.let { playlistId ->
            viewModelScope.launch {
                playlistRepository.updatePlaylist(playlistId, name, description)
                    .onSuccess {
                        refreshCurrentPlaylist()
                    }
                    .onFailure { e ->
                        _error.value = "Failed to update playlist info: ${e.message}"
                    }
            }
        }
    }

    fun updatePlaylistCover(coverArtUrl: String?) {
        val current = _playlist.value ?: return
        viewModelScope.launch {
            playlistRepository.updatePlaylist(current.id, current.name, current.description, coverArtUrl)
                .onSuccess {
                    refreshCurrentPlaylist()
                }
                .onFailure { e ->
                    _error.value = "Failed to update cover art: ${e.message}"
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
