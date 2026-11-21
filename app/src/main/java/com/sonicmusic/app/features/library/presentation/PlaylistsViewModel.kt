package com.sonicmusic.app.features.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.features.library.data.MusicLibraryPage
import com.sonicmusic.app.features.library.data.MusicLibrarySection
import com.sonicmusic.app.features.library.data.YouTubeMusicLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val accountLibrary: YouTubeMusicLibrary
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    val localPlaylists: StateFlow<List<Playlist>> = _playlists
        .map { list -> list.filter { it.isLocal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val remotePlaylists: StateFlow<List<Playlist>> = _playlists
        .map { list -> list.filter { !it.isLocal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accountPlaylists = accountLibrary.observe(MusicLibrarySection.Playlists)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), MusicLibraryPage())

    fun loadMoreAccountPlaylists() {
        viewModelScope.launch { accountLibrary.loadMore(MusicLibrarySection.Playlists) }
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadPlaylists()
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            playlistRepository.getAllPlaylists().collect { playlists ->
                _playlists.value = playlists
                _isLoading.value = false
            }
        }
    }

    fun createPlaylist(name: String, description: String? = null) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name, description)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
        }
    }

    fun importRemotePlaylist(playlistIdString: String, onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            val playlistId = extractPlaylistId(playlistIdString)
            playlistRepository.fetchRemotePlaylist(playlistId)
                .onSuccess { (playlist, songs) ->
                    playlistRepository.saveRemotePlaylist(playlist, songs)
                        .onSuccess {
                            _isLoading.value = false
                            onSuccess()
                        }
                        .onFailure { e ->
                            _isLoading.value = false
                            onFailure("Failed to save playlist: ${e.message}")
                        }
                }
                .onFailure { e ->
                    _isLoading.value = false
                    onFailure("Failed to fetch playlist: ${e.message}")
                }
        }
    }

    fun syncAllRemotePlaylists(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val remotes = _playlists.value.filter { !it.isLocal && !it.isYouTubeAccountPlaylist && it.remoteId != null }
            _isRefreshing.value = true
            try {
                accountLibrary.refreshPlaylists()
                remotes.forEach { playlist ->
                    val rId = playlist.remoteId ?: return@forEach
                    playlistRepository.fetchRemotePlaylist(rId)
                        .onSuccess { (p, songs) -> playlistRepository.saveRemotePlaylist(p, songs) }
                }
            } finally {
                _isRefreshing.value = false
            }
            onComplete()
        }
    }

    private fun extractPlaylistId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.contains("list=")) {
            return trimmed.substringAfter("list=").substringBefore("&")
        }
        return trimmed
    }
}
