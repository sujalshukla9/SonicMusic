package com.sonicmusic.app.features.albums.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.AlbumDetails
import com.sonicmusic.app.domain.model.AlbumTrack
import com.sonicmusic.app.domain.model.AlbumVideo
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.toSong
import com.sonicmusic.app.domain.repository.AlbumRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.shared.state.UiState
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import com.sonicmusic.app.features.library.data.ACCOUNT_PLAYLIST_ROUTE
import com.sonicmusic.app.features.library.data.YouTubeMusicLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection,
    private val accountLibrary: YouTubeMusicLibrary
) : ViewModel() {

    companion object {
        private const val TAG = "AlbumDetailViewModel"
    }

    private val _uiState = MutableStateFlow<UiState<AlbumDetails>>(UiState.Loading)
    val uiState: StateFlow<UiState<AlbumDetails>> = _uiState.asStateFlow()

    private var currentAlbumId: String? = null
    private var albumJob: Job? = null
    private var accountJob: Job? = null
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()
    private val _paginationError = MutableStateFlow(false)
    val paginationError = _paginationError.asStateFlow()

    fun loadAlbum(albumId: String, fallbackArtwork: String? = null, forceRefresh: Boolean = false, fallbackTitle: String = "") {
        val normalizedAlbumId = albumId.trim()
        Log.d(TAG, "loadAlbum called with albumId='$albumId', normalized='$normalizedAlbumId', forceRefresh=$forceRefresh")
        if (normalizedAlbumId.isEmpty()) {
            albumJob?.cancel()
            currentAlbumId = null
            Log.e(TAG, "Album browse ID is empty!")
            _uiState.value = UiState.Error("Album is unavailable")
            return
        }

        if (!forceRefresh && currentAlbumId == normalizedAlbumId &&
            (_uiState.value is UiState.Success || albumJob?.isActive == true)
        ) {
            Log.d(TAG, "Album already loaded for ID: $normalizedAlbumId")
            return
        }

        albumJob?.cancel()
        accountJob?.cancel()
        _loadingMore.value = false
        _paginationError.value = false
        currentAlbumId = normalizedAlbumId
        if (normalizedAlbumId.startsWith(ACCOUNT_PLAYLIST_ROUTE)) {
            accountJob = viewModelScope.launch {
                accountLibrary.playlistVisible(normalizedAlbumId).collect { visible ->
                    if (!visible) {
                        albumJob?.cancel()
                        _loadingMore.value = false
                        _uiState.value = UiState.Error("This playlist belongs to a different YouTube Music account.")
                    }
                }
            }
        }
        albumJob = viewModelScope.launch {
            fun display(details: AlbumDetails) = UiState.Success(details.copy(
                title = details.title.ifBlank { fallbackTitle },
                thumbnailUrl = details.thumbnailUrl.ifBlank { fallbackArtwork.orEmpty() }
            ))
            val cached = albumRepository.getCachedAlbumDetails(normalizedAlbumId)
            _uiState.value = if (cached != null) display(cached) else UiState.Loading
            albumRepository.getAlbumDetails(normalizedAlbumId, forceRefresh)
                .onSuccess { details ->
                    ensureActive()
                    _uiState.value = display(details)
                }
                .onFailure { exception ->
                    ensureActive()
                    Log.e(TAG, "Failed to load album details for ID: $normalizedAlbumId", exception)
                    _uiState.value = UiState.Error(exception.message ?: "Failed to load album details")
                }
        }
    }

    fun loadMoreTracks() {
        val current = (_uiState.value as? UiState.Success)?.data ?: return
        if (!current.hasMoreTracks || albumJob?.isActive == true) return
        _loadingMore.value = true
        _paginationError.value = false
        albumJob = viewModelScope.launch {
            try {
                albumRepository.getAlbumDetails(current.browseId, append = true).onSuccess { page ->
                    ensureActive()
                    _uiState.value = UiState.Success(page.copy(title = current.title, thumbnailUrl = current.thumbnailUrl))
                }.onFailure { _paginationError.value = true }
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun playSong(track: AlbumTrack, details: AlbumDetails) {
        val song = details.songs.firstOrNull { it.videoId == track.videoId }
            ?.toSong(details.thumbnailUrl) ?: return
        // Play only the clicked song + fresh radio recommendations (not the entire album)
        playerServiceConnection.playSongWithRadio(song)
    }

    fun playAll(details: AlbumDetails) {
        val mappedList = details.songs.map { it.toSong(details.thumbnailUrl) }
        if (mappedList.isEmpty()) return
        playerServiceConnection.playSongsLazy(mappedList, 0, songRepository)
    }

    fun shufflePlay(details: AlbumDetails) {
        val mappedList = details.songs.map { it.toSong(details.thumbnailUrl) }
        if (mappedList.isEmpty()) return
        playerServiceConnection.playSongsLazy(mappedList.shuffled(), 0, songRepository)
    }

    fun playVideo(video: AlbumVideo, details: AlbumDetails) {
        val primaryArtist = details.artists.firstOrNull() ?: "Unknown artist"
        val mappedVideo = video.toSong(primaryArtist)
        // Play only the clicked video + fresh radio recommendations
        playerServiceConnection.playSongWithRadio(mappedVideo)
    }
}
