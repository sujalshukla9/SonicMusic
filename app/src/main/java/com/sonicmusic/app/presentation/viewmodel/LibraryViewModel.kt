package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.downloadmanager.SongDownloadManager
import com.sonicmusic.app.data.local.entity.FollowedArtistEntity
import com.sonicmusic.app.data.local.dao.ArtistPlayCount
import com.sonicmusic.app.domain.model.LocalSong
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.LocalMusicRepository
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
    private val historyRepository: HistoryRepository,
    private val localMusicRepository: LocalMusicRepository,
    private val artistRepository: ArtistRepository,
    private val songDownloadManager: SongDownloadManager,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {
    companion object {
        private const val RECENTLY_PLAYED_LIMIT = 10 // Preview only; full list loaded in RecentlyPlayedScreen
    }

    private val _likedSongs = MutableStateFlow<List<Song>>(emptyList())
    val likedSongs: StateFlow<List<Song>> = _likedSongs.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<PlaybackHistory>>(emptyList())
    val recentlyPlayed: StateFlow<List<PlaybackHistory>> = _recentlyPlayed.asStateFlow()
    
    private val _localSongs = MutableStateFlow<List<LocalSong>>(emptyList())
    val localSongs: StateFlow<List<LocalSong>> = _localSongs.asStateFlow()

    private val _artists = MutableStateFlow<List<FollowedArtistEntity>>(emptyList())
    val artists: StateFlow<List<FollowedArtistEntity>> = _artists.asStateFlow()

    private val _downloadedSongs = MutableStateFlow<List<Song>>(emptyList())
    val downloadedSongs: StateFlow<List<Song>> = _downloadedSongs.asStateFlow()

    /** Active downloads being tracked — exposed for UI progress indicators */
    val activeDownloads = songDownloadManager.activeDownloads

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Library search filter
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    // Filtered playlists based on search query
    val filteredPlaylists: StateFlow<List<Playlist>> = combine(
        _playlists, _searchQuery.debounce(300)
    ) { playlists, query ->
        if (query.isBlank()) playlists
        else playlists.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered liked songs based on search query
    val filteredLikedSongs: StateFlow<List<Song>> = combine(
        _likedSongs, _searchQuery.debounce(300)
    ) { songs, query ->
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadLibraryData()
    }

    private fun loadLibraryData() {
        viewModelScope.launch {
            _isLoading.value = true

            // Launch all data loaders
            launch {
                songRepository.getLikedSongs().collect { songs ->
                    _likedSongs.value = songs
                }
            }

            launch {
                playlistRepository.getAllPlaylists().collect { playlists ->
                    _playlists.value = playlists
                }
            }

            launch {
                historyRepository.getRecentlyPlayed(RECENTLY_PLAYED_LIMIT).collect { history ->
                    _recentlyPlayed.value = history
                }
            }
            
            launch {
                localMusicRepository.getLocalSongs().collect { songs ->
                    _localSongs.value = songs
                }
            }

            launch {
                artistRepository.getFollowedArtists().collect { artistList ->
                    _artists.value = artistList
                }
            }

            launch {
                songDownloadManager.getDownloadedSongs().collect { downloaded ->
                    _downloadedSongs.value = downloaded.map { entity ->
                        Song(
                            id = entity.songId,
                            title = entity.title,
                            artist = entity.artist,
                            duration = 0,
                            thumbnailUrl = entity.thumbnailUrl.orEmpty()
                        )
                    }
                }
            }

            // Dismiss loading spinner once ANY data arrives
            // (all these flows emit immediately from Room)
            kotlinx.coroutines.delay(300)
            _isLoading.value = false
        }
    }
    
    fun refreshLocalMusic() {
        viewModelScope.launch {
            _isLoading.value = true
            localMusicRepository.scanDeviceMusic()
            _isLoading.value = false
        }
    }

    fun onLikedSongsClick() {
        // Navigation handled in UI
    }

    fun onPlaylistsClick() {
        // Navigation handled in UI
    }

    fun onRecentlyPlayedClick() {
        // Navigation handled in UI
    }

    fun onLocalSongsClick() {
        // Navigation handled in UI
    }

    fun onArtistsClick() {
        // Navigation handled in UI
    }

    fun onDownloadedSongClick(song: Song) {
        onSongClick(song)
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

    fun onSongClick(song: Song) {
        viewModelScope.launch {
            songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
                .onFailure { exception ->
                    _error.value = "Failed to play: ${exception.message}"
                }
        }
    }
    
    fun onLocalSongClick(localSong: LocalSong) {
        // Convert local song to Song and play
        val song = Song(
            id = "local_${localSong.id}",
            title = localSong.title,
            artist = localSong.artist,
            duration = localSong.duration / 1000,
            thumbnailUrl = "" // Local songs don't have thumbnails
        )
        
        // For local songs, use the file path directly as stream URL
        playerServiceConnection.playSong(song, localSong.filePath)
    }
    
    fun onHistoryItemClick(history: PlaybackHistory) {
        // Convert history to song and play
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
                .onFailure { exception ->
                    _error.value = "Failed to play: ${exception.message}"
                }
        }
    }

    fun unlikeSong(song: Song) {
        viewModelScope.launch {
            songRepository.unlikeSong(song.id)
        }
    }

    fun likeSong(song: Song) {
        viewModelScope.launch {
            songRepository.likeSong(song)
        }
    }
    
    fun clearError() {
        _error.value = null
    }

    // --- Library Search ---
    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) {
            _searchQuery.value = ""
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _isSearchActive.value = false
    }

    // --- Play All / Shuffle Liked Songs ---
    fun playAllLikedSongs() {
        val songs = _likedSongs.value
        if (songs.isEmpty()) return

        // Use lazy loading — starts playback instantly, fetches remaining URLs in background
        playerServiceConnection.playSongsLazy(songs, 0, songRepository)
    }

    fun shuffleLikedSongs() {
        val songs = _likedSongs.value.shuffled()
        if (songs.isEmpty()) return

        playerServiceConnection.playSongsLazy(songs, 0, songRepository)
    }

    fun playAllDownloadedSongs(shuffle: Boolean = false) {
        val songs = if (shuffle) _downloadedSongs.value.shuffled() else _downloadedSongs.value
        if (songs.isEmpty()) return

        playerServiceConnection.playSongsLazy(songs, 0, songRepository)
    }

    fun removeDownloadedSong(songId: String) {
        viewModelScope.launch {
            val removed = songDownloadManager.deleteDownload(songId)
            if (!removed) {
                _error.value = "Failed to remove download"
            }
        }
    }
}
