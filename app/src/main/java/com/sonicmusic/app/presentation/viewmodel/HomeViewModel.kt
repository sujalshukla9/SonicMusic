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
import com.sonicmusic.app.domain.usecase.GetHomeContentUseCase
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getHomeContentUseCase: GetHomeContentUseCase,
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
    private val queueRepository: QueueRepositoryImpl,
    private val regionRepository: RegionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val SECTION_LIMIT = 100
        private const val SECTION_ARTIST_PREFIX = "artist:"

        const val SECTION_LISTEN_AGAIN = "listen_again"
        const val SECTION_QUICK_PICKS = "quick_picks"
        const val SECTION_TRENDING = "trending"
        const val SECTION_NEW_RELEASES = "new_releases"
        const val SECTION_ENGLISH_HITS = "english_hits"
        const val SECTION_PERSONALIZED = "personalized"
        const val SECTION_FORGOTTEN_FAVORITES = "forgotten_favorites"

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

    val countryName: StateFlow<String?> = regionRepository.countryName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        loadHomeContent()
    }

    fun loadHomeContent() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                getHomeContentUseCase()
                    .onSuccess { content ->
                        _homeContent.value = content
                    }
                    .onFailure { exception ->
                        _error.value = exception.message ?: "Failed to load content"
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading home content", e)
                _error.value = "Failed to load: ${e.message}"
            }

            _isLoading.value = false
        }
    }

    /**
     * Pull-to-refresh handler — force reloads all sections.
     */
    fun refreshHomeContent() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null

            try {
                getHomeContentUseCase()
                    .onSuccess { content ->
                        _homeContent.value = content
                    }
                    .onFailure { exception ->
                        _error.value = exception.message ?: "Failed to refresh"
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing home content", e)
                _error.value = "Failed to refresh: ${e.message}"
            }

            _isRefreshing.value = false
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
                // Use lazy add to queue - currently appends, but robust
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
     * Navigate to section detail view
     */
    fun onSectionSeeAll(section: String) {
        Log.d(TAG, "See all clicked for section: $section")
        // TODO: Navigate to section detail view with full song list
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
            else -> {
                if (section.startsWith(SECTION_ARTIST_PREFIX)) {
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
            SECTION_TRENDING -> "Trending Now"
            SECTION_NEW_RELEASES -> "New Releases"
            SECTION_ENGLISH_HITS -> "English Hits"
            SECTION_PERSONALIZED -> "Made for You"
            SECTION_FORGOTTEN_FAVORITES -> "Forgotten Favorites"
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
                } else {
                    "Songs"
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
