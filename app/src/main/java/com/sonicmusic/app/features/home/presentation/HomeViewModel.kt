package com.sonicmusic.app.features.home.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.features.queue.data.QueueRepositoryImpl
import com.sonicmusic.app.data.repository.RegionRepository
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.BrowseRepository
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.features.home.domain.GetHomeContentUseCase
import com.sonicmusic.app.shared.components.pullrefresh.RefreshResult
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home ViewModel - Enhanced with ViTune-style Queue Management
 *
 * Improvements:
 * - Context-aware playback (plays song + adds rest of section to queue)
 * - Play All functionality for sections
 * - Better error handling with retry logic
 * - Queue management integration
 * - Shuffle and Play support
 */
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.features.home.data.HomeRefreshManager
import com.sonicmusic.app.domain.model.ContentType

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getHomeContentUseCase: GetHomeContentUseCase,
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
    private val queueRepository: QueueRepositoryImpl,
    private val regionRepository: RegionRepository,
    private val settingsRepository: SettingsRepository,
    private val browseRepository: BrowseRepository,
    private val artistRepository: ArtistRepository,
    private val networkMonitor: com.sonicmusic.app.core.network.NetworkMonitor,
    private val downloadRepository: com.sonicmusic.app.features.library.data.DownloadRepository,
    private val homeRefreshManager: HomeRefreshManager
) : ViewModel() {

    companion object {
        private const val TAG = "SonicHome"
        private const val SECTION_LIMIT = 100
        private const val SECTION_ARTIST_PREFIX = "artist:"

        const val SECTION_LISTEN_AGAIN = "listen_again"
        const val SECTION_QUICK_PICKS = "quick_picks"
        const val SECTION_ENGLISH_HITS = "english_hits"
        const val SECTION_FORGOTTEN_FAVORITES = "forgotten_favorites"
        // YTMusic Browse sections
        const val SECTION_TRENDING_ALBUMS = "trending_albums"
        const val SECTION_CHARTS = "charts"
        const val SECTION_RECENTLY_PLAYED = "recently_played"
        const val SECTION_MIXED_FOR_YOU = "mixed_for_you"

        fun artistSectionKey(artistId: String): String = "$SECTION_ARTIST_PREFIX$artistId"
    }

    private val _homeUiState = MutableStateFlow(HomeUiState())
    val homeUiState: StateFlow<HomeUiState> = _homeUiState.asStateFlow()

    private val _homeContent = MutableStateFlow<HomeContent>(HomeContent())
    val homeContent: StateFlow<HomeContent> = _homeContent.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult: StateFlow<RefreshResult?> = _refreshResult.asStateFlow()

    private val _followedArtistIds = MutableStateFlow<Set<String>>(emptySet())
    val followedArtistIds: StateFlow<Set<String>> = _followedArtistIds.asStateFlow()



    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _visibleSections = MutableStateFlow<Set<Any>>(emptySet())


    private var isScrollInProgress = false

    /** Prevents concurrent load / refresh from racing. */
    private var loadJob: Job? = null
    private var moreJob: Job? = null

    val countryName: StateFlow<String?> = regionRepository.countryName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnlineState

    val downloadedSongIds: StateFlow<Set<String>> = downloadRepository.getDownloadedSongs()
        .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.videoId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        loadHomeContent()
        observeFollowedArtists()
        observeHomeRefreshes()
    }

    private var pendingQuickPicks: List<Song>? = null
    private var pendingListenAgain: List<Song>? = null
    private var pendingForgottenFavorites: List<Song>? = null

    private fun applyPendingQuickPicks() {
        pendingQuickPicks?.let { songs ->
            _homeContent.value = _homeContent.value.copy(quickPicks = songs)
            pendingQuickPicks = null
            Log.d(TAG, "Applied pending Quick Picks update")
        }
    }

    private fun applyPendingListenAgain() {
        pendingListenAgain?.let { songs ->
            _homeContent.value = _homeContent.value.copy(listenAgain = songs)
            pendingListenAgain = null
            Log.d(TAG, "Applied pending Listen Again update")
        }
    }

    private fun applyPendingForgottenFavorites() {
        pendingForgottenFavorites?.let { songs ->
            _homeContent.value = _homeContent.value.copy(forgottenFavorites = songs)
            pendingForgottenFavorites = null
            Log.d(TAG, "Applied pending Forgotten Favorites update")
        }
    }

    private fun applyPendingUpdates() {
        applyPendingQuickPicks()
        applyPendingListenAgain()
        applyPendingForgottenFavorites()
    }

    fun onVisibleSectionsChanged(visibleKeys: Set<Any>, isScrollInProgress: Boolean = false) {
        this.isScrollInProgress = isScrollInProgress
        _visibleSections.value = visibleKeys

        if (!isScrollInProgress) {
            applyPendingUpdates()
        } else {
            if ("quick_picks" !in visibleKeys && "for_you" !in visibleKeys) {
                applyPendingQuickPicks()
            }
            if ("listen_again_grid" !in visibleKeys && "continue_listening" !in visibleKeys) {
                applyPendingListenAgain()
            }
            if ("forgotten_favorites" !in visibleKeys) {
                applyPendingForgottenFavorites()
            }
        }
    }

    private fun observeHomeRefreshes() {
        viewModelScope.launch {
            homeRefreshManager.refreshEvents.collect { (title, section) ->
                Log.d(TAG, "HomeRefreshManager updated section: $title with ${section.songs.size} items")
                val updatedSongs = section.songs.map { homeSong ->
                    val resolvedRefs = when {
                        homeSong.artistRefs.isNotEmpty() -> homeSong.artistRefs
                        homeSong.artists.isNotEmpty() -> homeSong.artists.mapIndexed { index, name ->
                            com.sonicmusic.app.domain.model.ArtistRef(
                                name = name,
                                browseId = homeSong.artistBrowseIds.getOrNull(index)
                            )
                        }
                        else -> emptyList()
                    }
                    val artistDisplayName = resolvedRefs.joinToString(", ") { it.name }
                        .ifBlank { "Unknown Artist" }
                    val primaryArtistId = resolvedRefs.firstOrNull()?.browseId
                        ?: homeSong.artistBrowseIds.firstOrNull()
                    Song(
                        id = homeSong.videoId,
                        title = homeSong.title,
                        artist = artistDisplayName,
                        artistId = primaryArtistId,
                        duration = 0,
                        thumbnailUrl = homeSong.thumbnailUrl,
                        album = homeSong.album,
                        albumBrowseId = null,
                        albumName = homeSong.album,
                        contentType = ContentType.SONG,
                        artists = resolvedRefs
                    )
                }

                val currentContent = _homeContent.value
                val isQuickPicksVisible = "quick_picks" in _visibleSections.value || "for_you" in _visibleSections.value
                val isListenAgainVisible = "listen_again_grid" in _visibleSections.value || "continue_listening" in _visibleSections.value
                val isForgottenVisible = "forgotten_favorites" in _visibleSections.value

                when (title.lowercase(java.util.Locale.US).trim()) {
                    "quick picks", "quickpicks" -> {
                        if (isQuickPicksVisible && isScrollInProgress) {
                            pendingQuickPicks = updatedSongs
                            Log.d(TAG, "Delaying Quick Picks update because section is currently visible and scrolling")
                        } else {
                            _homeContent.value = currentContent.copy(quickPicks = updatedSongs)
                            pendingQuickPicks = null
                            Log.d(TAG, "Applied Quick Picks update immediately")
                        }
                    }
                    "listen again" -> {
                        if (isListenAgainVisible && isScrollInProgress) {
                            pendingListenAgain = updatedSongs
                            Log.d(TAG, "Delaying Listen Again update")
                        } else {
                            _homeContent.value = currentContent.copy(listenAgain = updatedSongs)
                            pendingListenAgain = null
                            Log.d(TAG, "Applied Listen Again update immediately")
                        }
                    }
                    "forgotten favorites", "forgotten" -> {
                        if (isForgottenVisible && isScrollInProgress) {
                            pendingForgottenFavorites = updatedSongs
                            Log.d(TAG, "Delaying Forgotten Favorites update")
                        } else {
                            _homeContent.value = currentContent.copy(forgottenFavorites = updatedSongs)
                            pendingForgottenFavorites = null
                            Log.d(TAG, "Applied Forgotten Favorites update immediately")
                        }
                    }
                }
            }
        }
    }

    private fun observeFollowedArtists() {
        viewModelScope.launch {
            artistRepository.getFollowedArtists().collect { followedList ->
                _followedArtistIds.value = followedList.map { it.browseId }.filterNotNull().toSet() + 
                                           followedList.map { it.artistName.lowercase() }.toSet()
            }
        }
    }

    fun toggleFollowArtist(artist: Song) {
        viewModelScope.launch {
            val name = artist.artist.ifBlank { artist.title }
            val browseId = artist.id.takeIf { it.isNotBlank() } ?: artist.artistId
            val followedIds = _followedArtistIds.value
            val isCurrentlyFollowed = (browseId != null && browseId in followedIds) || name.lowercase() in followedIds
            if (isCurrentlyFollowed) {
                artistRepository.unfollowArtist(name, browseId)
            } else {
                artistRepository.followArtist(name, browseId, artist.thumbnailUrl)
            }
        }
    }



    fun loadMoreHomeContent() {
        val token = _homeContent.value.continuationToken
        if (token.isNullOrBlank() || _isLoadingMore.value) return

        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                browseRepository.getHomeContinuation(token)
                    .onSuccess { (newShelves, nextToken) ->
                        if (newShelves.isNotEmpty()) {
                            val currentShelves = _homeContent.value.continuationShelves
                            _homeContent.value = _homeContent.value.copy(
                                continuationShelves = currentShelves + newShelves,
                                continuationToken = nextToken
                            )
                        } else {
                            _homeContent.value = _homeContent.value.copy(continuationToken = null)
                        }
                    }
                    .onFailure { e ->
                        Log.w(TAG, "Failed loading continuation home content", e)
                        _homeContent.value = _homeContent.value.copy(continuationToken = null)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadMoreHomeContent", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }


    fun loadHomeContent() {
        // In-flight request deduplication: if already loading, reuse existing request
        if (loadJob?.isActive == true) {
            Log.d(TAG, "Home load already in flight, skipping duplicate request")
            return
        }

        loadJob = viewModelScope.launch {
            val hasExisting = _homeContent.value.hasAnyContent()
            _isLoading.value = !hasExisting
            _homeUiState.value = _homeUiState.value.copy(
                status = if (hasExisting) HomeStatus.CACHED else HomeStatus.LOADING,
                isRefreshing = false
            )
            _error.value = null
            try {
                fetchHomeContent(forceRefresh = false)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Pull-to-refresh handler — force reloads all sections while keeping existing content visible. */
    fun refreshHomeContent() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isRefreshing.value = true
            _homeUiState.value = _homeUiState.value.copy(
                status = HomeStatus.REFRESHING,
                isRefreshing = true
            )
            _refreshResult.value = null
            _error.value = null
            try {
                fetchHomeContent(forceRefresh = true)
                val isSuccess = _error.value == null
                _refreshResult.value = if (isSuccess) RefreshResult.SUCCESS else RefreshResult.ERROR
                _homeUiState.value = _homeUiState.value.copy(
                    status = if (isSuccess) HomeStatus.SUCCESS else HomeStatus.ERROR,
                    isRefreshing = false,
                    lastUpdated = if (isSuccess) System.currentTimeMillis() else _homeUiState.value.lastUpdated
                )
            } catch (e: Exception) {
                _refreshResult.value = RefreshResult.ERROR
                _homeUiState.value = _homeUiState.value.copy(
                    status = HomeStatus.ERROR,
                    isRefreshing = false,
                    error = e.message
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Shared loader used by both initial load and refresh. */
    private suspend fun fetchHomeContent(forceRefresh: Boolean) {
        val startMs = System.currentTimeMillis()
        try {
            // Ensure IP-based region is initialized before home recommendations load.
            runCatching { regionRepository.initializeRegion() }
                .onFailure { error -> Log.w(TAG, "Region initialization failed before home load", error) }

            getHomeContentUseCase(forceRefresh).collect { content ->
                val mergedContent = if (forceRefresh) {
                    val current = _homeContent.value
                    content.copy(
                        listenAgain = if (content.listenAgain.isNotEmpty()) content.listenAgain else current.listenAgain,
                        recentlyPlayed = if (content.recentlyPlayed.isNotEmpty()) content.recentlyPlayed else current.recentlyPlayed,
                        quickPicks = if (content.quickPicks.isNotEmpty()) content.quickPicks else current.quickPicks,
                        englishHits = if (content.englishHits.isNotEmpty()) content.englishHits else current.englishHits,
                        trendingAlbums = if (content.trendingAlbums.isNotEmpty()) content.trendingAlbums else current.trendingAlbums,
                        charts = content.charts ?: current.charts,
                        artists = if (content.artists.isNotEmpty()) content.artists else current.artists,
                        mixedForYou = if (content.mixedForYou.isNotEmpty()) content.mixedForYou else current.mixedForYou,
                        forgottenFavorites = if (content.forgottenFavorites.isNotEmpty()) content.forgottenFavorites else current.forgottenFavorites,
                        continuationShelves = if (content.continuationShelves.isNotEmpty()) content.continuationShelves else current.continuationShelves,
                        continuationToken = content.continuationToken ?: current.continuationToken
                    )
                } else {
                    content
                }

                _homeContent.value = mergedContent
                _homeUiState.value = _homeUiState.value.copy(
                    status = if (forceRefresh) HomeStatus.REFRESHING else HomeStatus.SUCCESS,
                    content = mergedContent,
                    lastUpdated = System.currentTimeMillis()
                )

                // Dismiss loading indicator as soon as any content is ready
                if (mergedContent.hasAnyContent()) {
                    _isLoading.value = false
                }
            }
            _isLoading.value = false
            Log.d(TAG, "Home content loaded in ${System.currentTimeMillis() - startMs}ms")
        } catch (e: Exception) {
            _isLoading.value = false
            Log.e(TAG, "Error loading home content (${System.currentTimeMillis() - startMs}ms)", e)
            _error.value = "Failed to load: ${e.message}"
            _homeUiState.value = _homeUiState.value.copy(
                status = if (_homeContent.value.hasAnyContent()) HomeStatus.CACHED else HomeStatus.ERROR,
                error = e.message
            )
        }
    }

    /**
     * Play a single song with dynamic recommendations - YouTube Music Style
     * Plays the clicked song and generates fresh recommendations based on it (max 20 songs).
     */
    fun onSongClick(song: Song) {
        playerServiceConnection.playSongWithRadio(song)
    }

    /**
     * Play a song with dynamic recommendations - YouTube Music Style
     * Does not dump the whole section into the queue; generates fresh recommendations instead.
     */
    fun onSongClickWithContext(song: Song, contextSongs: List<Song>, shuffle: Boolean = false) {
        playerServiceConnection.playSongWithRadio(song)
    }

    /**
     * Play a song with YouTube Music Radio-style queue
     * When user clicks a song, it plays that song and generates fresh recommendations based on it.
     */
    fun onSongClickWithRadioQueue(song: Song) {
        playerServiceConnection.playSongWithRadio(song)
    }

    /**
     * Play All songs from a section - ViTune Style
     *
     * @param songs List of songs to play
     * @param shuffle Whether to shuffle before playing
     */
    fun playAllSongs(songs: List<Song>, shuffle: Boolean = false) {
        if (songs.isEmpty()) return

        viewModelScope.launch {
            try {
                // Prepare list
                val songsToPlay = if (shuffle) songs.shuffled() else songs

                // Mark all songs as queued
                songsToPlay.forEach { queueRepository.markSongAsQueued(it.id) }

                // Play lazy
                playerServiceConnection.playSongsLazy(
                    songs = songsToPlay,
                    startIndex = 0, // Always start at beginning for Play All
                    songRepository = songRepository
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error playing all songs", e)
                _error.value = "Failed to play: ${e.message}"
            }
        }
    }

    /**
     * Add a song to play next (YouTube Music style)
     */
    fun playNext(song: Song) {
        viewModelScope.launch {
            try {
                playerServiceConnection.insertPlayNextLazy(song, songRepository)
                Log.d(TAG, "Added play next: ${song.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding song to play next", e)
            }
        }
    }

    /**
     * Validate section before navigating to section detail.
     *
     * @return section key if it can be opened, otherwise null.
     */
    fun onSectionSeeAll(section: String): String? {
        if (section.isBlank()) {
            _error.value = "Section unavailable"
            return null
        }

        val songs = getSongsForSection(section)
        if (songs.isEmpty()) {
            _error.value = "No songs available in this section"
            return null
        }

        Log.d(TAG, "See all clicked for section: $section (${songs.size} songs)")
        return section
    }

    /**
     * Play all songs for a home section key.
     *
     * @return true if a section had songs and playback was started.
     */
    fun playSection(section: String, shuffle: Boolean = false): Boolean {
        val songs = getSongsForSection(section)
        if (songs.isEmpty()) {
            _error.value = "No songs available in this section"
            return false
        }
        playAllSongs(songs, shuffle = shuffle)
        return true
    }

    /**
     * Handle section song click with queue context for richer playback.
     *
     * Falls back to radio queue if the section cannot be resolved.
     */
    fun onSectionSongClick(section: String, song: Song): Boolean {
        onSongClickWithRadioQueue(song)
        return true
    }

    /**
     * Get songs for a specific section
     */
    fun getSongsForSection(section: String): List<Song> {
        val sectionSongs = when (section) {
            SECTION_LISTEN_AGAIN -> _homeContent.value.listenAgain
            SECTION_QUICK_PICKS -> _homeContent.value.quickPicks
            SECTION_ENGLISH_HITS -> _homeContent.value.englishHits
            SECTION_FORGOTTEN_FAVORITES -> _homeContent.value.forgottenFavorites
            SECTION_TRENDING_ALBUMS -> _homeContent.value.trendingAlbums
            SECTION_CHARTS -> _homeContent.value.charts?.songs.orEmpty()
            SECTION_RECENTLY_PLAYED -> _homeContent.value.recentlyPlayed
            SECTION_MIXED_FOR_YOU -> _homeContent.value.mixedForYou
            else -> {
                if (section.startsWith("continuation_")) {
                    val shelfId = section.removePrefix("continuation_")
                    _homeContent.value.continuationShelves.firstOrNull { it.id == shelfId }?.songs.orEmpty()
                } else if (section.startsWith(SECTION_ARTIST_PREFIX)) {
                    val artistId = section.removePrefix(SECTION_ARTIST_PREFIX)
                    _homeContent.value.artists.firstOrNull { it.artist.id == artistId }?.songs.orEmpty()
                } else {
                    emptyList()
                }
            }
        }

        return sectionSongs.take(SECTION_LIMIT)
    }

    fun getSectionTitle(section: String): String {
        return when (section) {
            SECTION_LISTEN_AGAIN -> "Listen Again"
            SECTION_QUICK_PICKS -> "Quick Picks"
            SECTION_ENGLISH_HITS -> "English Hits"
            SECTION_FORGOTTEN_FAVORITES -> "Forgotten Favorites"
            SECTION_TRENDING_ALBUMS -> "Trending Albums"
            SECTION_CHARTS -> "Charts"
            SECTION_RECENTLY_PLAYED -> "Recently Played"
            SECTION_MIXED_FOR_YOU -> "Mixed For You"
            else -> {
                if (section.startsWith("continuation_")) {
                    val shelfId = section.removePrefix("continuation_")
                    _homeContent.value.continuationShelves.firstOrNull { it.id == shelfId }?.title ?: "Recommended"
                } else if (section.startsWith(SECTION_ARTIST_PREFIX)) {
                    val artistId = section.removePrefix(SECTION_ARTIST_PREFIX)
                    val artistName = _homeContent.value.artists
                        .firstOrNull { it.artist.id == artistId }
                        ?.artist
                        ?.name
                    if (artistName.isNullOrBlank()) {
                        "Artist Songs"
                    } else {
                        "More from $artistName"
                    }
                } else {
                    "Songs"
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearRefreshResult() {
        _refreshResult.value = null
    }
}
