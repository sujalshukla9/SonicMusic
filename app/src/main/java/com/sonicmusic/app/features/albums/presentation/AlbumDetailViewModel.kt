package com.sonicmusic.app.features.albums.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.AlbumDetails
import com.sonicmusic.app.domain.model.AlbumTrack
import com.sonicmusic.app.domain.model.AlbumVideo
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.AlbumRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.shared.state.UiState
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {

    companion object {
        private const val TAG = "AlbumDetailViewModel"
    }

    private val _uiState = MutableStateFlow<UiState<AlbumDetails>>(UiState.Loading)
    val uiState: StateFlow<UiState<AlbumDetails>> = _uiState.asStateFlow()

    private var currentAlbumId: String? = null

    fun loadAlbum(albumId: String, forceRefresh: Boolean = false) {
        val normalizedAlbumId = albumId.trim()
        Log.d(TAG, "loadAlbum called with albumId='$albumId', normalized='$normalizedAlbumId', forceRefresh=$forceRefresh")
        if (normalizedAlbumId.isEmpty()) {
            Log.e(TAG, "Album browse ID is empty!")
            _uiState.value = UiState.Error("Album is unavailable")
            return
        }

        if (!forceRefresh && currentAlbumId == normalizedAlbumId && _uiState.value is UiState.Success) {
            Log.d(TAG, "Album already loaded for ID: $normalizedAlbumId")
            return
        }

        currentAlbumId = normalizedAlbumId
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            albumRepository.getAlbumDetails(normalizedAlbumId, forceRefresh)
                .onSuccess { details ->
                    _uiState.value = UiState.Success(details)
                }
                .onFailure { exception ->
                    Log.e(TAG, "Failed to load album details for ID: $normalizedAlbumId", exception)
                    _uiState.value = UiState.Error(exception.message ?: "Failed to load album details")
                }
        }
    }

    fun playSong(track: AlbumTrack, details: AlbumDetails) {
        val mappedList = details.songs.map { it.toSong(details.thumbnailUrl) }
        val index = mappedList.indexOfFirst { it.id == track.videoId }
        if (index == -1) {
            return
        }
        playerServiceConnection.playSongsLazy(mappedList, index, songRepository)
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
        playerServiceConnection.playSongsLazy(listOf(mappedVideo), 0, songRepository)
    }
}

private fun AlbumTrack.toSong(albumArtUrl: String): Song {
    val refs = if (artistRefs.isNotEmpty()) {
        artistRefs
    } else {
        artists.mapIndexed { index, name ->
            com.sonicmusic.app.domain.model.ArtistRef(name, artistBrowseIds.getOrNull(index))
        }
    }
    val combinedArtistId = if (artistBrowseIds.isNotEmpty()) {
        artistBrowseIds.filter { it.isNotBlank() }.joinToString(",").takeIf { it.isNotBlank() }
    } else {
        refs.mapNotNull { it.browseId }.joinToString(",").takeIf { it.isNotBlank() }
    }
    return Song(
        id = videoId,
        title = title,
        artist = artists.joinToString(", "),
        artistId = combinedArtistId,
        album = albumName,
        albumId = albumBrowseId,
        duration = parseDuration(durationText),
        thumbnailUrl = albumArtUrl,
        category = "Music",
        contentType = ContentType.SONG,
        albumBrowseId = albumBrowseId,
        albumName = albumName,
        artists = refs
    )
}

private fun AlbumVideo.toSong(albumArtist: String): Song {
    return Song(
        id = videoId,
        title = title,
        artist = albumArtist,
        album = "Videos",
        albumId = null,
        duration = durationText?.let { parseDuration(it) } ?: 0,
        thumbnailUrl = thumbnailUrl,
        category = "Video",
        contentType = ContentType.VIDEO,
        artists = if (albumArtist.isNotBlank()) listOf(com.sonicmusic.app.domain.model.ArtistRef(albumArtist, null)) else emptyList()
    )
}

private fun parseDuration(text: String): Int {
    val parts = text.split(":")
    return try {
        when (parts.size) {
            1 -> parts[0].toIntOrNull() ?: 0
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
