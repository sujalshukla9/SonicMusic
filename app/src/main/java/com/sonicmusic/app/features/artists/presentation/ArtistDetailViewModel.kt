package com.sonicmusic.app.features.artists.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.ArtistProfile
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.toSong
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val songRepository: SongRepository,
    private val artistRepository: ArtistRepository,
    private val playerServiceConnection: PlayerServiceConnection
) : ViewModel() {
    private data class ArtistRequest(val name: String, val browseId: String?)
    private var request: ArtistRequest? = null
    private var generation = 0
    private var profileJob: Job? = null
    private var historyJob: Job? = null
    private var followJob: Job? = null
    private val sectionJobs = mutableMapOf<ArtistProfileSectionType, Job>()
    private val completedSections = mutableSetOf<ArtistProfileSectionType>()

    private val _profile = MutableStateFlow<ArtistProfile?>(null)
    val profile = _profile.asStateFlow()
    private val _isFollowed = MutableStateFlow(false)
    val isFollowed = _isFollowed.asStateFlow()
    private val _recentlyPlayed = MutableStateFlow<List<PlaybackHistory>>(emptyList())
    val recentlyPlayed = _recentlyPlayed.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loadingMoreSections = MutableStateFlow<Set<ArtistProfileSectionType>>(emptySet())
    val loadingMoreSections = _loadingMoreSections.asStateFlow()
    private val _failedSections = MutableStateFlow<Set<ArtistProfileSectionType>>(emptySet())
    val failedSections = _failedSections.asStateFlow()

    fun loadArtist(artist: String, browseId: String? = null, forceRefresh: Boolean = false) {
        val next = ArtistRequest(artist.trim(), browseId?.trim()?.takeIf(String::isNotEmpty))
        if (!forceRefresh && request == next) return
        val changed = request != next
        request = next
        val loadGeneration = ++generation
        profileJob?.cancel()
        sectionJobs.values.forEach { it.cancel() }
        sectionJobs.clear()
        completedSections.clear()
        _failedSections.value = emptySet()
        _loadingMoreSections.value = emptySet()
        if (changed) {
            _profile.value = null
            _recentlyPlayed.value = emptyList()
            _isFollowed.value = false
        }
        _isLoading.value = true
        _error.value = null
        observeLibrary(next.name, next.browseId)
        profileJob = viewModelScope.launch {
            try {
                val loaded = artistRepository.getArtistProfile(next.name, next.browseId, forceRefresh).getOrThrow()
                if (generation != loadGeneration) return@launch
                _profile.value = loaded
                if (loaded.isStale) _error.value = "Showing the saved artist profile"
                val name = next.name.ifBlank { loaded.name }
                val id = loaded.browseId ?: next.browseId
                if (name != next.name || id != next.browseId) observeLibrary(name, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration) {
                    Log.w(TAG, "Artist profile unavailable", e)
                    _error.value = "Couldn't load the artist catalog"
                }
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }
        }
    }

    fun retryArtist() {
        request?.let { loadArtist(it.name, it.browseId, forceRefresh = true) }
    }

    private fun observeLibrary(name: String, id: String?) {
        historyJob?.cancel()
        followJob?.cancel()
        if (name.isNotBlank()) {
            historyJob = viewModelScope.launch {
                historyRepository.getSongsByArtist(name).collect { _recentlyPlayed.value = it }
            }
        }
        followJob = viewModelScope.launch {
            artistRepository.isFollowed(name, id).collect { _isFollowed.value = it }
        }
    }

    fun toggleFollow() {
        val name = _profile.value?.name ?: request?.name.orEmpty()
        if (name.isBlank()) return
        val id = _profile.value?.browseId ?: request?.browseId
        viewModelScope.launch {
            if (_isFollowed.value) {
                artistRepository.unfollowArtist(name, id)
            } else {
                artistRepository.followArtist(name, id, _profile.value?.imageUrl)
            }
        }
    }

    fun loadMoreSection(type: ArtistProfileSectionType, retry: Boolean = false) {
        val current = _profile.value ?: return
        if (sectionJobs[type]?.isActive == true || type in completedSections ||
            (!retry && type in _failedSections.value) || _isLoading.value
        ) return
        val legacyId = current.topSongsBrowseId.takeIf { type == ArtistProfileSectionType.TopSongs }
        val legacyParams = when (type) {
            ArtistProfileSectionType.TopSongs -> current.songsMoreEndpoint
            ArtistProfileSectionType.Albums -> current.albumsMoreEndpoint
            ArtistProfileSectionType.Singles -> current.singlesMoreEndpoint
            else -> null
        }
        val ownerId = current.browseId ?: request?.browseId
        val sources = current.sections.filter { it.type == type }
            .map { Triple(it.browseId, it.moreEndpoint, it.continuationToken) }
            .filter { (id, params, token) -> params != null || token != null || (id != null && id != ownerId) }
            .ifEmpty { listOf(Triple(legacyId, legacyParams, null)) }
            .distinct()
        val usableSources = sources.filter { (id, params, token) ->
            params != null || token != null || (id != null && id != ownerId)
        }
        val needsSongFallback = type == ArtistProfileSectionType.TopSongs && current.topSongs.isEmpty()
        if (usableSources.isEmpty() && !needsSongFallback) {
            completedSections += type
            return
        }

        val loadGeneration = generation
        _failedSections.update { it - type }
        _loadingMoreSections.update { it + type }
        sectionJobs[type] = viewModelScope.launch {
            try {
                val items = mutableListOf<Song>()
                var failed = false
                for ((sectionId, params, token) in usableSources) {
                    val browseId = sectionId ?: ownerId ?: continue
                    artistRepository.getArtistSectionItems(
                        artistBrowseId = browseId,
                        sectionType = type,
                        moreEndpoint = params,
                        forceRefresh = retry,
                        continuationToken = token,
                        dedicatedBrowse = sectionId != null && sectionId != ownerId
                    ).onSuccess { items += it }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        Log.w(TAG, "Artist section unavailable: $type", failure)
                        failed = true
                    }
                }
                if (items.isEmpty() && needsSongFallback) {
                    artistRepository.getArtistSongs(current.name, ownerId, limit = 60, forceRefresh = retry)
                        .onSuccess { items += it }
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            failed = true
                        }
                    if (items.isNotEmpty()) failed = false
                }
                if (loadGeneration != generation) return@launch
                _profile.update { it?.mergeSection(type, items) }
                if (failed) _failedSections.update { it + type } else completedSections += type
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGeneration == generation) {
                    Log.w(TAG, "Artist section unavailable: $type", e)
                    _failedSections.update { it + type }
                }
            } finally {
                if (loadGeneration == generation) _loadingMoreSections.update { it - type }
            }
        }
    }

    private fun ArtistProfile.mergeSection(type: ArtistProfileSectionType, items: List<Song>): ArtistProfile {
        fun List<Song>.merge(loaded: List<Song> = items) =
            (this + loaded).filter { it.id.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.id }

        return when (type) {
            ArtistProfileSectionType.TopSongs -> copy(topSongs = topSongs.merge())
            ArtistProfileSectionType.Albums -> copy(albums = albums.merge(items.filter { item -> singles.none { it.id == item.id } }))
            ArtistProfileSectionType.Singles -> {
                val releases = singles.merge()
                val singleIds = releases.mapTo(hashSetOf()) { it.id }
                copy(singles = releases, albums = albums.filterNot { it.id in singleIds })
            }
            ArtistProfileSectionType.Videos -> copy(videos = videos.merge())
            ArtistProfileSectionType.FeaturedOn -> copy(featuredOn = featuredOn.merge())
            ArtistProfileSectionType.RelatedArtists -> copy(relatedArtists = relatedArtists.merge())
            ArtistProfileSectionType.Unknown -> this
        }
    }

    fun playSong(song: Song) {
        // Play only the clicked song + fresh radio recommendations (not the entire artist catalog)
        playerServiceConnection.playSongWithRadio(song)
    }

    fun playAll(shuffle: Boolean = false): Boolean {
        val songs = _profile.value?.topSongs.orEmpty().ifEmpty { _recentlyPlayed.value.map { it.toSong() } }
        if (songs.isEmpty()) return false
        playQueue(if (shuffle) songs.shuffled() else songs)
        return true
    }

    fun startRadio(): Boolean {
        val artist = _profile.value ?: return false
        val seed = artist.topSongs.firstOrNull() ?: artist.videos.firstOrNull() ?: return false
        playerServiceConnection.playSongWithRadio(seed, artist.radioPlaylistId)
        return true
    }

    fun playHistorySong(history: PlaybackHistory) {
        // Play only the clicked history song + fresh radio recommendations
        playerServiceConnection.playSongWithRadio(history.toSong())
    }

    private fun playQueue(songs: List<Song>, selectedId: String? = null) {
        if (songs.isEmpty()) return
        playerServiceConnection.playSongsLazy(
            songs, songs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0), songRepository
        )
    }

    private companion object {
        const val TAG = "ArtistDetail"
    }
}
