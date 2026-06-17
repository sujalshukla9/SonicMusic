package com.sonicmusic.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.repository.QueueRepositoryImpl
import com.sonicmusic.app.data.repository.RegionRepository
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.BrowseRepository
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.usecase.GetHomeContentUseCase
import com.sonicmusic.app.presentation.ui.components.pullrefresh.RefreshResult
import com.sonicmusic.app.service.PlayerServiceConnection
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
import com.sonicmusic.app.data.repository.HomeRefreshManager
import com.sonicmusic.app.domain.model.ContentType
import java.util.Locale

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
    private val downloadRepository: com.sonicmusic.app.data.repository.DownloadRepository,
    private val homeRefreshManager: HomeRefreshManager
) : ViewModel() {

    companion object {
        private const val TAG = "SonicHome"
        private const val SECTION_LIMIT = 100
        private const val SECTION_ARTIST_PREFIX = "artist:"

        const val SECTION_LISTEN_AGAIN = "listen_again"
        const val SECTION_QUICK_PICKS = "quick_picks"
        const val SECTION_TRENDING = "trending"
        const val SECTION_NEW_RELEASES = "new_releases"
        const val SECTION_ENGLISH_HITS = "english_hits"
        const val SECTION_PERSONALIZED = "personalized"
        const val SECTION_FORGOTTEN_FAVORITES = "forgotten_favorites"
        // New YTMusic Browse sections
        const val SECTION_TRENDING_ALBUMS = "trending_albums"
        const val SECTION_TRENDING_ARTISTS = "trending_artists"
        const val SECTION_CHARTS = "charts"
        const val SECTION_RECENTLY_PLAYED = "recently_played"
        const val SECTION_MIXED_FOR_YOU = "mixed_for_you"
        const val SECTION_MOOD_GENRE = "mood_genre"

        fun artistSectionKey(artistId: String): String = "$SECTION_ARTIST_PREFIX$artistId"
    }

    private val _homeContent = MutableStateFlow<HomeContent>(HomeContent())
    val homeContent: StateFlow<HomeContent> = _homeContent.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult: StateFlow<RefreshResult?> = _refreshResult.asStateFlow()

    private val _followedArtistIds = MutableStateFlow<Set<String>>(emptySet())
    val followedArtistIds: StateFlow<Set<String>> = _followedArtistIds.asStateFlow()

    private val _dynamicSections = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val dynamicSections: StateFlow<Map<String, List<Song>>> = _dynamicSections.asStateFlow()

    private val _visibleSections = MutableStateFlow<Set<Any>>(emptySet())

    private var pendingQuickPicks: List<Song>? = null
    private var pendingPersonalized: List<Song>? = null
    private var isScrollInProgress = false

    /** Prevents concurrent load / refresh from racing. */
    private var loadJob: Job? = null

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

    private fun applyPendingQuickPicks() {
        pendingQuickPicks?.let { songs ->
            _homeContent.value = _homeContent.value.copy(quickPicks = songs)
            pendingQuickPicks = null
            Log.d(TAG, "Applied pending Quick Picks update")
        }
    }

    private fun applyPendingPersonalized() {
        pendingPersonalized?.let { songs ->
            _homeContent.value = _homeContent.value.copy(personalizedForYou = songs)
            pendingPersonalized = null
            Log.d(TAG, "Applied pending Made for You update")
        }
    }

    private fun applyPendingUpdates() {
        applyPendingQuickPicks()
        applyPendingPersonalized()
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
            if ("personalized_for_you" !in visibleKeys) {
                applyPendingPersonalized()
            }
        }
    }

    private fun observeHomeRefreshes() {
        viewModelScope.launch {
            homeRefreshManager.refreshEvents.collect { (title, section) ->
                Log.d(TAG, "HomeRefreshManager updated section: $title with ${section.songs.size} items")
                val updatedSongs = section.songs.map { homeSong ->
                    Song(
                        id = homeSong.videoId,
                        title = homeSong.title,
                        artist = homeSong.artists.firstOrNull() ?: "Unknown Artist",
                        duration = 0,
                        thumbnailUrl = homeSong.thumbnailUrl,
                        album = homeSong.album,
                        albumBrowseId = null,
                        albumName = homeSong.album,
                        contentType = ContentType.SONG
                    )
                }

                val currentContent = _homeContent.value
                val isQuickPicksVisible = "quick_picks" in _visibleSections.value || "for_you" in _visibleSections.value
                val isPersonalizedVisible = "personalized_for_you" in _visibleSections.value

                when (title.lowercase(Locale.US)) {
                    "quick picks", "quickpicks" -> {
                        if (isQuickPicksVisible && isScrollInProgress) {
                            pendingQuickPicks = updatedSongs
                            Log.d(TAG, "Delaying Quick Picks update because section is currently visible and scrolling")
                        } else {
                            _homeContent.value = currentContent.copy(quickPicks = updatedSongs)
                            pendingQuickPicks = null
                            Log.d(TAG, "Applied Quick Picks update immediately (visible=$isQuickPicksVisible, scrolling=$isScrollInProgress)")
                        }
                    }
                    "made for you", "personalized", "mixed for you" -> {
                        if (isPersonalizedVisible && isScrollInProgress) {
                            pendingPersonalized = updatedSongs
                            Log.d(TAG, "Delaying Made for You update because section is currently visible and scrolling")
                        } else {
                            _homeContent.value = currentContent.copy(personalizedForYou = updatedSongs)
                            pendingPersonalized = null
                            Log.d(TAG, "Applied Made for You update immediately (visible=$isPersonalizedVisible, scrolling=$isScrollInProgress)")
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

    fun loadDynamicSectionIfNeeded(sectionKey: String) {
        if (!sectionKey.startsWith("mood_genre:")) return
        if (_dynamicSections.value.containsKey(sectionKey)) return

        val params = sectionKey.removePrefix("mood_genre:").substringAfter("|")
        viewModelScope.launch {
            _isLoading.value = true
            browseRepository.getMoodGenreSongs(params)
                .onSuccess { songs ->
                    _dynamicSections.value = _dynamicSections.value + (sectionKey to songs)
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Failed to load mood playlist"
                }
            _isLoading.value = false
        }
    }

    fun loadHomeContent() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            fetchHomeContent(forceRefresh = false)
            _isLoading.value = false
        }
    }

    /** Pull-to-refresh handler — force reloads all sections. */
    fun refreshHomeContent() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isRefreshing.value = true
            _refreshResult.value = null
            _error.value = null
            try {
                fetchHomeContent(forceRefresh = true)
                _refreshResult.value = if (_error.value == null) RefreshResult.SUCCESS else RefreshResult.ERROR
            } catch (_: Exception) {
                _refreshResult.value = RefreshResult.ERROR
            }
            _isRefreshing.value = false
        }
    }

    /** Shared loader used by both initial load and refresh. */
    private suspend fun fetchHomeContent(forceRefresh: Boolean) {
        val startMs = System.currentTimeMillis()
        try {
            // Ensure IP-based region is initialized before home recommendations load.
            runCatching { regionRepository.initializeRegion() }
                .onFailure { error -> Log.w(TAG, "Region initialization failed before home load", error) }

            var emissionCount = 0
            getHomeContentUseCase(forceRefresh).collect { content ->
                _homeContent.value = content
                emissionCount++
                
                // Progressive loading: dismiss loading indicator as soon as local/cached items are visible.
                // Emission 1 represents Stage 1a (Instant DB). Emission 2 is Stage 1b (Quick Picks).
                if (emissionCount >= 2 || content.listenAgain.isNotEmpty() || content.recentlyPlayed.isNotEmpty()) {
                    _isLoading.value = false
                }
            }
            Log.d(TAG, "⏱️ Home content loaded in ${System.currentTimeMillis() - startMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading home content (${System.currentTimeMillis() - startMs}ms)", e)
            _error.value = "Failed to load: ${e.message}"
        }
    }

    /**
     * Play a single song - ViTune Style
     * Records history and starts playback with instant recommendations
     */
    fun onSongClick(song: Song) {
        viewModelScope.launch {
            try {
                // Immediate UI feedback
                playerServiceConnection.preparePlayback(song)

                // Use robust lazy loading
                playerServiceConnection.playSongsLazy(
                    songs = listOf(song),
                    startIndex = 0,
                    songRepository = songRepository
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error playing song", e)
                _error.value = "Failed to play: ${e.message}"
            }
        }
    }

    /**
     * Play a song with context - ViTune Style
     * Plays the clicked song and adds the remaining songs from the list to the queue
     *
     * @param song The song to play
     * @param contextSongs List of songs for context (e.g., section songs)
     * @param shuffle Whether to shuffle the queue after the current song
     */
    fun onSongClickWithContext(song: Song, contextSongs: List<Song>, shuffle: Boolean = false) {
        viewModelScope.launch {
            try {
                // Prepare list
                val songsToPlay = if (shuffle) {
                    // If shuffle, put clicked song first, shuffle the rest
                    val remaining = contextSongs.filter { it.id != song.id }.shuffled()
                    listOf(song) + remaining
                } else {
                    contextSongs
                }
                
                // Find index of clicked song
                val startIndex = songsToPlay.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

                // Mark songs as queued (so they don't get re-added by recommendations immediately)
                songsToPlay.forEach { queueRepository.markSongAsQueued(it.id) }

                // Play lazy
                playerServiceConnection.playSongsLazy(
                    songs = songsToPlay,
                    startIndex = startIndex,
                    songRepository = songRepository
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error playing song with context", e)
                _error.value = "Failed to play: ${e.message}"
            }
        }
    }

    /**
     * Play a song with ViTune Radio-style queue
     * When user clicks a song, it plays that song and starts radio recommendations
     * The queue fills automatically with similar songs (infinite radio)
     *
     * @param song The song to start radio from
     */
    fun onSongClickWithRadioQueue(song: Song) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🎵 Starting ViTune Radio for: ${song.title} (${song.id})")

                // Clear and reset queue state ONCE
                queueRepository.clearQueue()

                // Immediate UI feedback
                playerServiceConnection.preparePlayback(song)
                
                val autoQueueSimilar = settingsRepository.autoQueueSimilar.first()
                queueRepository.setInfiniteMode(autoQueueSimilar)
                if (autoQueueSimilar) {
                    queueRepository.markSongAsQueued(song.id)
                }

                // Play lazy
                playerServiceConnection.playSongsLazy(
                    songs = listOf(song),
                    startIndex = 0,
                    songRepository = songRepository
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting radio", e)
                _error.value = "Failed to play: ${e.message}"
            }
        }
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
     * Add a song to play next
     */
    fun playNext(song: Song) {
        viewModelScope.launch {
            try {
                // Append through normal queue path so local queue state is updated.
                playerServiceConnection.addSongsToQueueLazy(
                    songs = listOf(song),
                    songRepository = songRepository,
                    isQueueAlreadyPopulated = true
                )
                Log.d(TAG, "➕ Added to queue: ${song.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding song to queue", e)
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
            SECTION_TRENDING -> _homeContent.value.trending
            SECTION_NEW_RELEASES -> _homeContent.value.newReleases
            SECTION_ENGLISH_HITS -> _homeContent.value.englishHits
            SECTION_PERSONALIZED -> _homeContent.value.personalizedForYou
            SECTION_FORGOTTEN_FAVORITES -> _homeContent.value.forgottenFavorites
            SECTION_TRENDING_ALBUMS -> _homeContent.value.trendingAlbums
            SECTION_TRENDING_ARTISTS -> _homeContent.value.trendingArtists
            SECTION_CHARTS -> _homeContent.value.charts?.songs.orEmpty()
            SECTION_RECENTLY_PLAYED -> _homeContent.value.recentlyPlayed
            SECTION_MIXED_FOR_YOU -> _homeContent.value.mixedForYou
            else -> {
                if (section.startsWith(SECTION_ARTIST_PREFIX)) {
                    val artistId = section.removePrefix(SECTION_ARTIST_PREFIX)
                    _homeContent.value.artists.firstOrNull { it.artist.id == artistId }?.songs.orEmpty()
                } else if (section.startsWith("mood_genre:")) {
                    _dynamicSections.value[section].orEmpty()
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
            SECTION_TRENDING -> "Trending Now"
            SECTION_NEW_RELEASES -> "New Releases"
            SECTION_ENGLISH_HITS -> "English Hits"
            SECTION_PERSONALIZED -> "Made for You"
            SECTION_FORGOTTEN_FAVORITES -> "Forgotten Favorites"
            SECTION_TRENDING_ALBUMS -> "Trending Albums"
            SECTION_TRENDING_ARTISTS -> "Trending Artists"
            SECTION_CHARTS -> "Charts"
            SECTION_RECENTLY_PLAYED -> "Recently Played"
            SECTION_MIXED_FOR_YOU -> "Mixed For You"
            SECTION_MOOD_GENRE -> "Moods & Genres"
            else -> {
                if (section.startsWith(SECTION_ARTIST_PREFIX)) {
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
                } else if (section.startsWith("mood_genre:")) {
                    section.removePrefix("mood_genre:").substringBefore("|")
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
