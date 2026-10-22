package com.sonicmusic.app.features.library.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.features.downloads.data.SongDownloadManager
import com.sonicmusic.app.data.local.entity.FollowedArtistEntity
import com.sonicmusic.app.data.local.entity.DownloadedSong
import com.sonicmusic.app.data.local.entity.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
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
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DownloadedSortType {
    RECENTLY_DOWNLOADED, TITLE_A_Z, ARTIST_A_Z, FILE_SIZE, DURATION
}

enum class DownloadedFilterType {
    ALL, COMPLETED_ONLY, FAILED_ONLY
}

@OptIn(FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    val likedSongs: StateFlow<List<Song>> = songRepository.getLikedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayed: StateFlow<List<PlaybackHistory>> = historyRepository.getRecentlyPlayed(RECENTLY_PLAYED_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val localSongs: StateFlow<List<LocalSong>> = localMusicRepository.getLocalSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<FollowedArtistEntity>> = artistRepository.getFollowedArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val downloadedSongsFlow = songDownloadManager.getDownloadedSongs()

    val downloadedSongs: StateFlow<List<Song>> = downloadedSongsFlow
        .map { downloaded ->
            downloaded
                .filter { it.status == com.sonicmusic.app.data.local.entity.DownloadStatus.COMPLETED }
                .map { entity ->
                    Song(
                        id = entity.songId,
                        title = entity.title,
                        artist = entity.artists,
                        duration = (entity.durationMs / 1000).toInt(),
                        thumbnailUrl = entity.thumbnailUrl
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // History search filter
    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    val fullHistory: StateFlow<List<PlaybackHistory>> = combine(
        _historySearchQuery,
        historyRepository.getRecentlyPlayed(500)
    ) { query, history ->
        if (query.isBlank()) {
            history
        } else {
            history.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setHistorySearchQuery(query: String) {
        _historySearchQuery.value = query
    }

    // Filtered playlists based on search query
    val filteredPlaylists: StateFlow<List<Playlist>> = combine(
        playlists, _searchQuery.debounce(300)
    ) { playlistsList, query ->
        if (query.isBlank()) playlistsList
        else playlistsList.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered liked songs based on search query
    val filteredLikedSongs: StateFlow<List<Song>> = combine(
        likedSongs, _searchQuery.debounce(300)
    ) { songs, query ->
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Downloads Filtering, Sorting, and Searching ---
    private val _downloadSortType = MutableStateFlow(DownloadedSortType.RECENTLY_DOWNLOADED)
    val downloadSortType: StateFlow<DownloadedSortType> = _downloadSortType.asStateFlow()

    private val _downloadFilterType = MutableStateFlow(DownloadedFilterType.ALL)
    val downloadFilterType: StateFlow<DownloadedFilterType> = _downloadFilterType.asStateFlow()

    private val _downloadSearchQuery = MutableStateFlow("")
    val downloadSearchQuery: StateFlow<String> = _downloadSearchQuery.asStateFlow()

    private val _freeSpaceBytes = MutableStateFlow(Long.MAX_VALUE)
    val freeSpaceBytes: StateFlow<Long> = _freeSpaceBytes.asStateFlow()

    val totalDownloadSize: StateFlow<Long> = downloadedSongsFlow
        .map { downloaded ->
            downloaded
                .filter { it.status == com.sonicmusic.app.data.local.entity.DownloadStatus.COMPLETED }
                .sumOf { it.fileSizeBytes }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val filteredDownloadedSongs: StateFlow<List<DownloadedSong>> = combine(
        downloadedSongsFlow,
        _downloadSortType,
        _downloadFilterType,
        _downloadSearchQuery
    ) { downloaded, sortType, filterType, query ->
        var list = downloaded.filter { it.status != com.sonicmusic.app.data.local.entity.DownloadStatus.REMOVED }

        // Filter
        list = when (filterType) {
            DownloadedFilterType.ALL -> list
            DownloadedFilterType.COMPLETED_ONLY -> list.filter { it.status == com.sonicmusic.app.data.local.entity.DownloadStatus.COMPLETED }
            DownloadedFilterType.FAILED_ONLY -> list.filter { it.status == com.sonicmusic.app.data.local.entity.DownloadStatus.FAILED }
        }

        // Search
        if (query.isNotBlank()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artists.contains(query, ignoreCase = true)
            }
        }

        // Sort
        list = when (sortType) {
            DownloadedSortType.RECENTLY_DOWNLOADED -> list.sortedByDescending { it.downloadedAt }
            DownloadedSortType.TITLE_A_Z -> list.sortedBy { it.title.lowercase().removePrefix("a ").removePrefix(" an ").removePrefix(" the ") }
            DownloadedSortType.ARTIST_A_Z -> list.sortedBy { it.artists.lowercase() }
            DownloadedSortType.FILE_SIZE -> list.sortedByDescending { it.fileSizeBytes }
            DownloadedSortType.DURATION -> list.sortedByDescending { it.durationMs }
        }

        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            historyRepository.pruneOldHistory(500)
        }
        updateStorageMetrics()
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

    fun onDownloadedSongClick(download: DownloadedSong) {
        val song = Song(
            id = download.videoId,
            title = download.title,
            artist = download.artists,
            duration = (download.durationMs / 1000).toInt(),
            thumbnailUrl = download.thumbnailUrl
        )
        val file = java.io.File(download.localFilePath)
        if (file.exists()) {
            playerServiceConnection.playSong(song, download.localFilePath)
        } else {
            // Fallback to streaming
            onSongClick(song)
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

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteHistoryEntry(id)
        }
    }

    fun restoreHistoryEntry(history: PlaybackHistory) {
        viewModelScope.launch {
            historyRepository.restoreHistoryEntry(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
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
        val songs = likedSongs.value
        if (songs.isEmpty()) return

        // Use lazy loading — starts playback instantly, fetches remaining URLs in background
        playerServiceConnection.playSongsLazy(songs, 0, songRepository)
    }

    fun shuffleLikedSongs() {
        val songs = likedSongs.value.shuffled()
        if (songs.isEmpty()) return

        playerServiceConnection.playSongsLazy(songs, 0, songRepository)
    }

    fun playAllDownloadedSongs(shuffle: Boolean = false) {
        val songs = if (shuffle) downloadedSongs.value.shuffled() else downloadedSongs.value
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

    fun restoreDownloadedSong(song: DownloadedSong) {
        viewModelScope.launch {
            songDownloadManager.restoreDownloadedSong(song)
        }
    }

    fun updateStorageMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = context.getExternalFilesDir(null) ?: context.filesDir
            _freeSpaceBytes.value = file.usableSpace
        }
    }

    fun updateDownloadSort(sortType: DownloadedSortType) {
        _downloadSortType.value = sortType
    }

    fun updateDownloadFilter(filterType: DownloadedFilterType) {
        _downloadFilterType.value = filterType
    }

    fun updateDownloadSearchQuery(query: String) {
        _downloadSearchQuery.value = query
    }

    fun pauseDownload(videoId: String) {
        songDownloadManager.pauseDownload(videoId)
    }

    fun resumeDownload(videoId: String) {
        songDownloadManager.resumeDownload(videoId)
    }

    fun cancelDownload(videoId: String) {
        songDownloadManager.cancelDownload(videoId)
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            val success = songDownloadManager.clearAllDownloads()
            if (!success) {
                _error.value = "Failed to clear all downloads"
            }
        }
    }

    fun deleteDownloadsBatch(videoIds: List<String>) {
        viewModelScope.launch {
            var allSuccess = true
            videoIds.forEach { id ->
                val success = songDownloadManager.deleteDownload(id)
                if (!success) allSuccess = false
            }
            if (!allSuccess) {
                _error.value = "Failed to delete some downloads"
            }
        }
    }

    fun addSongsToPlaylistBatch(videoIds: List<String>, playlistId: Long, force: Boolean = false) {
        viewModelScope.launch {
            videoIds.forEach { videoId ->
                playlistRepository.addSongToPlaylist(playlistId, videoId, force)
            }
        }
    }

    suspend fun hasAnySongInPlaylist(playlistId: Long, songIds: List<String>): Boolean {
        return songIds.any { playlistRepository.hasSongInPlaylist(playlistId, it) }
    }
}
