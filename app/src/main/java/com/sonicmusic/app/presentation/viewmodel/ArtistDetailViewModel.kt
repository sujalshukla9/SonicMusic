package com.sonicmusic.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.ArtistProfile
import com.sonicmusic.app.domain.model.ArtistProfileSection
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Artist Detail ViewModel — YouTube Music Style
 *
 * Uses ArtistRepository.getArtistProfile() for rich profile data:
 * - Hero image, subscriber count
 * - Top songs, albums, singles, videos, featured on, related artists
 * - Fallback to local playback history
 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val songRepository: SongRepository,
    private val artistRepository: ArtistRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {

    companion object {
        private const val TAG = "ArtistDetail"
    }

    private val _artistName = MutableStateFlow("")
    val artistName: StateFlow<String> = _artistName.asStateFlow()
    private val _artistBrowseId = MutableStateFlow<String?>(null)
    val artistBrowseId: StateFlow<String?> = _artistBrowseId.asStateFlow()

    // Full artist profile from YouTube Music
    private val _profile = MutableStateFlow<ArtistProfile?>(null)
    val profile: StateFlow<ArtistProfile?> = _profile.asStateFlow()

    // Follow Status
    private val _isFollowed = MutableStateFlow(false)
    val isFollowed: StateFlow<Boolean> = _isFollowed.asStateFlow()

    // Local playback history for this artist
    private val _recentlyPlayed = MutableStateFlow<List<PlaybackHistory>>(emptyList())
    val recentlyPlayed: StateFlow<List<PlaybackHistory>> = _recentlyPlayed.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Album songs when user taps an album card
    private val _albumSongs = MutableStateFlow<List<Song>>(emptyList())
    val albumSongs: StateFlow<List<Song>> = _albumSongs.asStateFlow()

    private val _isAlbumLoading = MutableStateFlow(false)
    val isAlbumLoading: StateFlow<Boolean> = _isAlbumLoading.asStateFlow()

    private val _loadingMoreSections = MutableStateFlow<Set<ArtistProfileSectionType>>(emptySet())
    val loadingMoreSections: StateFlow<Set<ArtistProfileSectionType>> = _loadingMoreSections.asStateFlow()

    private var historyJob: Job? = null
    private var followStatusJob: Job? = null

    fun toggleFollow() {
        val currentName = _artistName.value
        if (currentName.isBlank()) return

        viewModelScope.launch {
            if (_isFollowed.value) {
                artistRepository.unfollowArtist(currentName, _profile.value?.browseId)
            } else {
                artistRepository.followArtist(
                    currentName,
                    _profile.value?.browseId,
                    _profile.value?.imageUrl
                )
            }
        }
    }

    fun loadArtist(artist: String, browseId: String? = null) {
        val normalizedArtist = artist.trim()
        val normalizedBrowseId = browseId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedArtist.isEmpty() && normalizedBrowseId == null) return

        val sameArtist = _artistName.value == normalizedArtist && _artistBrowseId.value == normalizedBrowseId
        if (sameArtist && (_profile.value != null || _isLoading.value)) return

        val artistChanged = _artistName.value != normalizedArtist || _artistBrowseId.value != normalizedBrowseId
        _artistName.value = normalizedArtist
        _artistBrowseId.value = normalizedBrowseId
        _isLoading.value = true
        _error.value = null
        if (artistChanged) {
            _profile.value = null
            _recentlyPlayed.value = emptyList()
            _loadingMoreSections.value = emptySet()
        }

        historyJob?.cancel()
        if (normalizedArtist.isNotBlank()) {
            historyJob = viewModelScope.launch {
                loadHistory(normalizedArtist)
            }
        }

        followStatusJob?.cancel()
        followStatusJob = viewModelScope.launch {
            artistRepository.isFollowed(normalizedArtist, normalizedBrowseId).collect {
                _isFollowed.value = it
            }
        }

        viewModelScope.launch {
            try {
                // Load artist profile from YouTube Music innertube API
                loadProfile(normalizedArtist, normalizedBrowseId)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadProfile(artist: String, browseId: String?) {
        artistRepository.getArtistProfile(artist, browseId)
            .onSuccess { profile ->
                _profile.value = profile
                Log.d(TAG, "✅ Loaded profile for: ${profile.name} " +
                        "(${profile.topSongs.size} songs, ${profile.albums.size} albums, " +
                        "${profile.singles.size} singles, ${profile.videos.size} videos)")
            }
            .onFailure { e ->
                Log.e(TAG, "Failed to load artist profile", e)
                _error.value = "Couldn't load artist from YouTube Music"
            }
    }

    fun loadAlbumSongs(albumBrowseId: String) {
        if (albumBrowseId.isBlank()) return
        _isAlbumLoading.value = true

        viewModelScope.launch {
            artistRepository.getAlbumSongs(albumBrowseId)
                .onSuccess { songs ->
                    _albumSongs.value = songs
                    Log.d(TAG, "✅ Loaded ${songs.size} album songs")
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load album songs", e)
                }
            _isAlbumLoading.value = false
        }
    }

    fun loadMoreSongs(limit: Int = 220) {
        val current = _profile.value ?: return
        val sectionType = ArtistProfileSectionType.TopSongs
        if (_loadingMoreSections.value.contains(sectionType)) return

        val hasSource = !current.songsMoreEndpoint.isNullOrBlank() ||
            !current.topSongsBrowseId.isNullOrBlank() ||
            !current.browseId.isNullOrBlank()
        if (!hasSource) return

        setSectionLoading(sectionType, true)
        viewModelScope.launch {
            val sectionBrowseId = current.topSongsBrowseId?.trim()?.takeIf { it.isNotEmpty() }
                ?: current.browseId?.trim()?.takeIf { it.isNotEmpty() }
            val result = if (!sectionBrowseId.isNullOrBlank()) {
                artistRepository.getArtistSectionItems(
                    artistBrowseId = sectionBrowseId,
                    sectionType = sectionType,
                    moreEndpoint = current.songsMoreEndpoint,
                    limit = limit,
                    forceRefresh = true
                )
            } else {
                artistRepository.getArtistSongs(
                    name = _artistName.value.ifBlank { current.name },
                    browseId = current.topSongsBrowseId ?: current.browseId,
                    limit = limit,
                    forceRefresh = true
                )
            }
            result
                .onSuccess { songs ->
                    _profile.update { profile ->
                        profile?.withUpdatedSectionItems(sectionType, songs)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load all songs for artist", e)
                }
            setSectionLoading(sectionType, false)
        }
    }

    fun loadMoreSection(
        sectionType: ArtistProfileSectionType,
        limit: Int = 160
    ) {
        if (sectionType == ArtistProfileSectionType.TopSongs) {
            loadMoreSongs(limit = limit.coerceAtMost(300))
            return
        }
        val current = _profile.value ?: return
        if (_loadingMoreSections.value.contains(sectionType)) return

        val moreEndpoint = when (sectionType) {
            ArtistProfileSectionType.Albums -> current.albumsMoreEndpoint
            ArtistProfileSectionType.Singles -> current.singlesMoreEndpoint
            else -> current.sections.firstOrNull { it.type == sectionType }?.moreEndpoint
        }
        val sectionBrowseId = current.sections.firstOrNull { it.type == sectionType }
            ?.browseId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val browseId = sectionBrowseId
            ?: current.browseId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return

        setSectionLoading(sectionType, true)
        viewModelScope.launch {
            artistRepository.getArtistSectionItems(
                artistBrowseId = browseId,
                sectionType = sectionType,
                moreEndpoint = moreEndpoint,
                limit = limit,
                forceRefresh = true
            )
                .onSuccess { items ->
                    _profile.update { profile ->
                        profile?.withUpdatedSectionItems(sectionType, items)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load artist section: $sectionType", e)
                }
            setSectionLoading(sectionType, false)
        }
    }

    private suspend fun loadHistory(artist: String) {
        historyRepository.getSongsByArtist(artist).collect { history ->
            _recentlyPlayed.value = history
        }
    }

    // ─── Playback Actions ───────────────────────────────────────────

    fun playSong(song: Song) {
        viewModelScope.launch {
            songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to play song", e)
                }
        }
    }

    fun playAll() {
        val songs = _profile.value?.topSongs?.ifEmpty { null }
            ?: historyToSongs(_recentlyPlayed.value)
        if (songs.isEmpty()) return

        viewModelScope.launch {
            val urlMap = fetchStreamUrlsBatch(songs.take(10))
            val first = songs.firstOrNull { urlMap.containsKey(it.id) } ?: return@launch
            val firstUrl = urlMap[first.id] ?: return@launch

            playerServiceConnection.playSong(first, firstUrl)

            songs.drop(1).forEach { song ->
                val url = urlMap[song.id]
                if (url != null) {
                    playerServiceConnection.addToQueue(song, url)
                }
            }
        }
    }

    fun shufflePlay() {
        val songs = (_profile.value?.topSongs?.ifEmpty { null }
            ?: historyToSongs(_recentlyPlayed.value)).shuffled()
        if (songs.isEmpty()) return

        viewModelScope.launch {
            val urlMap = fetchStreamUrlsBatch(songs.take(10))
            val first = songs.firstOrNull { urlMap.containsKey(it.id) } ?: return@launch
            val firstUrl = urlMap[first.id] ?: return@launch

            playerServiceConnection.playSong(first, firstUrl)

            songs.drop(1).forEach { song ->
                val url = urlMap[song.id]
                if (url != null) {
                    playerServiceConnection.addToQueue(song, url)
                }
            }
        }
    }

    fun playAlbumSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            val urlMap = fetchStreamUrlsBatch(songs.take(10))
            val first = songs.firstOrNull { urlMap.containsKey(it.id) } ?: return@launch
            val firstUrl = urlMap[first.id] ?: return@launch

            playerServiceConnection.playSong(first, firstUrl)
            songs.drop(1).forEach { song ->
                val url = urlMap[song.id]
                if (url != null) {
                    playerServiceConnection.addToQueue(song, url)
                }
            }
        }
    }

    fun playHistorySong(history: PlaybackHistory) {
        playSong(historyToSong(history))
    }

    // ─── Utilities ──────────────────────────────────────────────────

    private fun historyToSong(h: PlaybackHistory) = Song(
        id = h.songId,
        title = h.title,
        artist = h.artist,
        duration = 0,
        thumbnailUrl = h.thumbnailUrl
    )

    private fun historyToSongs(list: List<PlaybackHistory>) = list.map { historyToSong(it) }

    private suspend fun fetchStreamUrlsBatch(songs: List<Song>): Map<String, String> =
        withContext(Dispatchers.IO) {
            songs.map { song ->
                async {
                    try {
                        songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                            .getOrNull()?.let { url -> song.id to url }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

    private fun setSectionLoading(type: ArtistProfileSectionType, loading: Boolean) {
        _loadingMoreSections.update { current ->
            if (loading) {
                current + type
            } else {
                current - type
            }
        }
    }

    private fun ArtistProfile.withUpdatedSectionItems(
        sectionType: ArtistProfileSectionType,
        items: List<Song>
    ): ArtistProfile {
        val deduped = items
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .toList()
        if (deduped.isEmpty()) return this

        val existingSection = sections.firstOrNull { it.type == sectionType }
        val nextSections = when {
            sections.isEmpty() -> listOf(
                ArtistProfileSection(
                    type = sectionType,
                    title = sectionTitle(sectionType),
                    items = deduped
                )
            )
            existingSection == null -> sections + ArtistProfileSection(
                type = sectionType,
                title = sectionTitle(sectionType),
                items = deduped
            )
            else -> sections.map { section ->
                if (section.type == sectionType) {
                    section.copy(items = deduped)
                } else {
                    section
                }
            }
        }

        return when (sectionType) {
            ArtistProfileSectionType.TopSongs -> copy(topSongs = deduped, sections = nextSections)
            ArtistProfileSectionType.Albums -> copy(albums = deduped, sections = nextSections)
            ArtistProfileSectionType.Singles -> copy(singles = deduped, sections = nextSections)
            ArtistProfileSectionType.Videos -> copy(videos = deduped, sections = nextSections)
            ArtistProfileSectionType.FeaturedOn -> copy(featuredOn = deduped, sections = nextSections)
            ArtistProfileSectionType.RelatedArtists -> copy(relatedArtists = deduped, sections = nextSections)
            ArtistProfileSectionType.Unknown -> this
        }
    }

    private fun sectionTitle(type: ArtistProfileSectionType): String {
        return when (type) {
            ArtistProfileSectionType.TopSongs -> "Songs"
            ArtistProfileSectionType.Albums -> "Albums"
            ArtistProfileSectionType.Singles -> "Singles"
            ArtistProfileSectionType.Videos -> "Videos"
            ArtistProfileSectionType.FeaturedOn -> "Featured on"
            ArtistProfileSectionType.RelatedArtists -> "Fans might also like"
            ArtistProfileSectionType.Unknown -> ""
        }
    }
}
