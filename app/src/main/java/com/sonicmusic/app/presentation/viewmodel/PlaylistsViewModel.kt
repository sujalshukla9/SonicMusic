package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
}
