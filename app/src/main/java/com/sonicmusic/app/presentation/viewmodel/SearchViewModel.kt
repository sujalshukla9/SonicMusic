package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.RecentSearch
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.usecase.SearchSongsUseCase
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchSongsUseCase: SearchSongsUseCase,
    private val recentSearchRepository: RecentSearchRepository,
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults.asStateFlow()
    
    // Search suggestions while typing (shows matching artists/song titles)
    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recentSearches: StateFlow<List<RecentSearch>> = _recentSearches.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Track if search was explicitly submitted
    private var lastSubmittedQuery: String = ""

    init {
        // Debounce search queries for live results (but don't save to history)
        _searchQuery
            .debounce(300)
            .onEach { query ->
                if (query.isNotBlank() && query.length >= 2) {
                    performLiveSearch(query)
                } else {
                    _searchResults.value = emptyList()
                    _searchSuggestions.value = emptyList()
                }
            }
            .launchIn(viewModelScope)

        loadRecentSearches()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchSuggestions.value = emptyList()
    }
    
    /**
     * Called when user explicitly submits search (press Enter/Search button)
     * This saves to search history
     */
    fun submitSearch() {
        val query = _searchQuery.value.trim()
        if (query.isNotBlank() && query.length >= 2) {
            lastSubmittedQuery = query
            viewModelScope.launch {
                recentSearchRepository.addSearch(query)
            }
        }
    }
    
    /**
     * Live search - doesn't save to history, just shows results
     */
    private fun performLiveSearch(query: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            searchSongsUseCase(query)
                .onSuccess { songs ->
                    _searchResults.value = songs
                    
                    // Generate suggestions from results (unique artists + song titles)
                    val suggestions = mutableListOf<String>()
                    
                    // Add matching artists
                    val artists = songs
                        .map { it.artist }
                        .distinct()
                        .filter { it.contains(query, ignoreCase = true) }
                        .take(3)
                    suggestions.addAll(artists)
                    
                    // Add matching song titles
                    val titles = songs
                        .map { it.title }
                        .filter { it.contains(query, ignoreCase = true) }
                        .take(3)
                    suggestions.addAll(titles)
                    
                    _searchSuggestions.value = suggestions.distinct().take(5)
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Search failed"
                }

            _isLoading.value = false
        }
    }

    private fun loadRecentSearches() {
        viewModelScope.launch {
            recentSearchRepository.getRecentSearches(10)
                .collect { searches ->
                    _recentSearches.value = searches
                }
        }
    }

    /**
     * When user clicks on a recent search
     */
    fun onRecentSearchClick(query: String) {
        _searchQuery.value = query
        // This is an intentional search action, save it
        viewModelScope.launch {
            recentSearchRepository.addSearch(query)
        }
    }
    
    /**
     * When user clicks on a suggestion
     */
    fun onSuggestionClick(suggestion: String) {
        _searchQuery.value = suggestion
        // This is an intentional search action, save it
        viewModelScope.launch {
            recentSearchRepository.addSearch(suggestion)
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch {
            recentSearchRepository.deleteSearch(query)
        }
    }

    fun clearAllRecentSearches() {
        viewModelScope.launch {
            recentSearchRepository.clearAllSearches()
        }
    }

    /**
     * When user clicks on a song to play
     * Save the current search query to history (not the song title)
     */
    fun onSongClick(song: Song) {
        viewModelScope.launch {
            // Save the search query (not song title) to recent searches
            val currentQuery = _searchQuery.value.trim()
            if (currentQuery.isNotBlank() && currentQuery.length >= 2) {
                recentSearchRepository.addSearch(currentQuery)
            }
            
            // Get stream URL and play
            songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
                .onFailure { exception ->
                    _error.value = "Failed to play: ${exception.message}"
                }
        }
    }

    fun clearError() {
        _error.value = null
    }
}