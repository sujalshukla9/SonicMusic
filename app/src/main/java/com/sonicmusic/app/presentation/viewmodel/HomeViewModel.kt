package com.sonicmusic.app.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.repository.QueueRepositoryImpl
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.usecase.GetHomeContentUseCase
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val historyRepository: HistoryRepository,
    private val queueRepository: QueueRepositoryImpl
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    private val _homeContent = MutableStateFlow<HomeContent>(HomeContent())
    val homeContent: StateFlow<HomeContent> = _homeContent.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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
            _isLoading.value = true
            _error.value = null

            try {
                // Immediate UI feedback
                playerServiceConnection.preparePlayback(song)
                
                // Record playback FIRST so history is immediately updated
                historyRepository.recordPlayback(song)

                // Clear any existing queue state for fresh start
                queueRepository.clearQueue()

                var lastException: Exception? = null

                // Retry loop for better reliability
                repeat(MAX_RETRY_ATTEMPTS) { attempt ->
                    try {
                        songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                            .onSuccess { streamUrl ->
                                playerServiceConnection.playSong(song, streamUrl)
                                _isLoading.value = false
                                return@launch
                            }
                            .onFailure { exception ->
                                Log.w(TAG, "Attempt ${attempt + 1} failed: ${exception.message}")
                                lastException = exception as? Exception
                            }
                    } catch (e: Exception) {
                        Log.w(TAG, "Attempt ${attempt + 1} error: ${e.message}")
                        lastException = e
                    }

                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(1000L * (attempt + 1))
                    }
                }

                // All retries failed
                _error.value = "Failed to play: ${lastException?.message ?: "Unknown error"}"

            } catch (e: Exception) {
                Log.e(TAG, "Error playing song", e)
                _error.value = "Failed to play: ${e.message}"
            }

            _isLoading.value = false
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
            _isLoading.value = true
            _error.value = null

            try {
                // Record playback
                historyRepository.recordPlayback(song)

                // Clear existing queue
                queueRepository.clearQueue()

                // Filter out the clicked song and shuffle if requested
                val remainingSongs = contextSongs.filter { it.id != song.id }
                val orderedSongs = if (shuffle) remainingSongs.shuffled() else remainingSongs

                // Mark songs as queued
                orderedSongs.forEach { queueRepository.markSongAsQueued(it.id) }

                // Get stream URL for the clicked song first
                songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        // Start playback immediately
                        playerServiceConnection.playSong(song, streamUrl)

                        // Add remaining songs in background
                        if (orderedSongs.isNotEmpty()) {
                            addSongsToQueueInBackground(orderedSongs)
                        }

                        _isLoading.value = false
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "Failed to get stream URL", exception)
                        _error.value = "Failed to play: ${exception.message}"
                        _isLoading.value = false
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error playing song with context", e)
                _error.value = "Failed to play: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * Play a song with ViTune Radio-style queue
     * When user clicks a song, it plays that song and starts radio recommendations
     * The queue fills automatically with similar songs (infinite radio)
     * 
     * NOTE: Queue generation is handled by PlayerViewModel's callback mechanism
     * This just starts playback and enables infinite mode
     *
     * @param song The song to start radio from
     */
    fun onSongClickWithRadioQueue(song: Song) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                Log.d(TAG, "🎵 Starting ViTune Radio for: ${song.title} (${song.id})")

                // Immediate UI feedback
                playerServiceConnection.preparePlayback(song)

                // Record playback
                historyRepository.recordPlayback(song)

                // Get stream URL for the song FIRST
                songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        Log.d(TAG, "✅ Got stream URL, starting playback")
                        
                        // Clear any existing queue state
                        queueRepository.clearQueue()
                        
                        // Start playback immediately - this creates the initial queue
                        playerServiceConnection.playSong(song, streamUrl)

                        // Enable infinite mode for radio-style behavior
                        // This allows PlayerViewModel to auto-fill the queue
                        queueRepository.setInfiniteMode(true)

                        // Mark this song as queued
                        queueRepository.markSongAsQueued(song.id)

                        _isLoading.value = false
                        Log.d(TAG, "🎵 Started ViTune Radio from: ${song.title}")
                        Log.d(TAG, "📻 Queue will auto-fill via PlayerViewModel callback")
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "❌ Failed to start radio", exception)
                        _error.value = "Failed to play: ${exception.message}"
                        _isLoading.value = false
                    }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting radio", e)
                _error.value = "Failed to play: ${e.message}"
                _isLoading.value = false
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
            _isLoading.value = true
            _error.value = null

            try {
                // Clear existing queue
                queueRepository.clearQueue()

                // Shuffle if requested
                val orderedSongs = if (shuffle) songs.shuffled() else songs

                // Get the first song
                val firstSong = orderedSongs.first()

                // Record playback
                historyRepository.recordPlayback(firstSong)

                // Mark all songs as queued
                orderedSongs.forEach { queueRepository.markSongAsQueued(it.id) }

                // Get stream URL for first song
                songRepository.getStreamUrl(firstSong.id, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        val remainingSongs = orderedSongs.drop(1)

                        if (remainingSongs.isEmpty()) {
                            // Single song - just play it
                            playerServiceConnection.playSong(firstSong, streamUrl)
                        } else {
                            // Multiple songs - use queue
                            val initialMap = mapOf(firstSong.id to streamUrl)
                            playerServiceConnection.playWithQueue(orderedSongs, initialMap, 0)

                            // Fetch remaining URLs in background
                            addSongsToQueueInBackground(remainingSongs)
                        }

                        _isLoading.value = false
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "Failed to play queue", exception)
                        _error.value = "Failed to play: ${exception.message}"
                        _isLoading.value = false
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Error playing all songs", e)
                _error.value = "Failed to play: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * Add a song to play next (queue position 1)
     */
    fun playNext(song: Song) {
        viewModelScope.launch {
            try {
                songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                    .onSuccess { streamUrl ->
                        playerServiceConnection.addToQueue(song, streamUrl)
                        Log.d(TAG, "➕ Added to play next: ${song.title}")
                    }
                    .onFailure { exception ->
                        Log.e(TAG, "Failed to add to queue", exception)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding song to queue", e)
            }
        }
    }

    /**
     * Add songs to queue in background with parallel URL fetching
     * Syncs both PlayerServiceConnection and QueueRepository
     */
    private suspend fun addSongsToQueueInBackground(songs: List<Song>) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "⏳ Fetching URLs for ${songs.size} songs...")
                
                // Fetch URLs in batches to avoid overwhelming the network
                songs.chunked(5).forEach { batch ->
                    val jobs = batch.map { song ->
                        async {
                            try {
                                songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                                    .getOrNull()
                                    ?.let { url -> 
                                        Log.d(TAG, "🔗 Got URL for: ${song.title}")
                                        song to url 
                                    }
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ Failed to get URL for ${song.id}: ${e.message}")
                                null
                            }
                        }
                    }

                    val results = jobs.awaitAll().filterNotNull()
                    if (results.isNotEmpty()) {
                        val urlMap = results.associate { it.first.id to it.second }
                        val validSongs = results.map { it.first }
                        
                        Log.d(TAG, "➕ Adding ${validSongs.size} songs to queue...")
                        
                        // Add to PlayerServiceConnection for playback
                        playerServiceConnection.addToQueue(validSongs, urlMap)
                        
                        // Also add to QueueRepository for tracking and infinite queue
                        queueRepository.addToQueue(validSongs)
                        
                        // Mark songs as queued
                        validSongs.forEach { queueRepository.markSongAsQueued(it.id) }
                        
                        Log.d(TAG, "✅ Added ${validSongs.size} songs to queue. Total: ${playerServiceConnection.queue.value.size}")
                    } else {
                        Log.w(TAG, "⚠️ No valid URLs fetched in this batch")
                    }

                    kotlinx.coroutines.delay(100) // Small delay between batches
                }
                
                Log.d(TAG, "✅ Finished adding all songs to queue")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding songs to queue", e)
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
        return when (section) {
            "listen_again" -> _homeContent.value.listenAgain
            "quick_picks" -> _homeContent.value.quickPicks
            "trending" -> _homeContent.value.trending
            "new_releases" -> _homeContent.value.newReleases
            "english_hits" -> _homeContent.value.englishHits
            "personalized" -> _homeContent.value.personalizedForYou
            "forgotten_favorites" -> _homeContent.value.forgottenFavorites
            else -> emptyList()
        }
    }

    fun clearError() {
        _error.value = null
    }
}