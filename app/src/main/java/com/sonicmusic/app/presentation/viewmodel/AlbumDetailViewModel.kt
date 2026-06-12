package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val artistRepository: ArtistRepository,
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {

    private val _album = MutableStateFlow<Song?>(null)
    val album: StateFlow<Song?> = _album.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentAlbumId: String? = null

    fun loadAlbum(
        albumId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?
    ) {
        val normalizedAlbumId = albumId.trim()
        if (normalizedAlbumId.isEmpty()) {
            _error.value = "Album is unavailable"
            return
        }

        _error.value = null
        _album.value = Song(
            id = normalizedAlbumId,
            title = title.ifBlank { "Album" },
            artist = artist,
            album = title.ifBlank { "Album" },
            albumId = normalizedAlbumId,
            duration = 0,
            thumbnailUrl = thumbnailUrl.orEmpty(),
            category = "Album",
            contentType = ContentType.ALBUM
        )

        if (currentAlbumId == normalizedAlbumId && (_songs.value.isNotEmpty() || _isLoading.value)) {
            return
        }

        currentAlbumId = normalizedAlbumId
        viewModelScope.launch {
            _isLoading.value = true
            artistRepository.getAlbumSongs(normalizedAlbumId)
                .onSuccess { tracks ->
                    _songs.value = tracks.distinctBy { it.id }

                    val currentAlbum = _album.value ?: return@onSuccess
                    val firstTrack = tracks.firstOrNull()
                    _album.value = currentAlbum.copy(
                        artist = currentAlbum.artist.ifBlank {
                            firstTrack?.artist.orEmpty()
                        },
                        thumbnailUrl = currentAlbum.thumbnailUrl.ifBlank {
                            firstTrack?.thumbnailUrl.orEmpty()
                        }
                    )
                }
                .onFailure { exception ->
                    _songs.value = emptyList()
                    _error.value = exception.message ?: "Failed to load album"
                }
            _isLoading.value = false
        }
    }

    fun playSong(song: Song) {
        val trackList = _songs.value
        val index = trackList.indexOfFirst { it.id == song.id }
        if (index == -1) {
            _error.value = "Track is unavailable"
            return
        }

        playerServiceConnection.playSongsLazy(trackList, index, songRepository)
    }

    fun playAll() {
        val trackList = _songs.value
        if (trackList.isEmpty()) {
            _error.value = "No tracks available"
            return
        }

        playerServiceConnection.playSongsLazy(trackList, 0, songRepository)
    }

    fun shufflePlay() {
        val trackList = _songs.value
        if (trackList.isEmpty()) {
            _error.value = "No tracks available"
            return
        }

        playerServiceConnection.playSongsLazy(trackList.shuffled(), 0, songRepository)
    }

    fun clearError() {
        _error.value = null
    }
}
