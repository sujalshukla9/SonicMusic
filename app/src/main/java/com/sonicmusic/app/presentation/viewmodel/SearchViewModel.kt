package com.sonicmusic.app.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.RecentSearch
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.usecase.GetSearchSuggestionsUseCase
import com.sonicmusic.app.domain.usecase.SearchSongsUseCase
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sealed UI state for the search page.
 * 
 * ALL visual states are captured here so the composable simply 
 * pattern-matches on one flow instead of juggling 6 separate flows.
 */
sealed interface SearchUiState {
    /** Default state – shows recent searches + browse categories */
    data object Initial : SearchUiState

    /** Loading indicator while fetching full results */
    data object Loading : SearchUiState

    /** Autocomplete suggestions (shown while typing, before submitting) */
    @Immutable
    data class Suggestions(
        val query: String,
        val suggestions: List<String>,
    ) : SearchUiState

    /** Full search results after an explicit submit */
    @Immutable
    data class Results(
        val query: String,
        val songs: List<Song>,
    ) : SearchUiState

    /** The query returned zero results */
    @Immutable
    data class Empty(val query: String) : SearchUiState

    /** Something went wrong */
    @Immutable
    data class Error(val message: String) : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchSongsUseCase: SearchSongsUseCase,
    private val getSearchSuggestionsUseCase: GetSearchSuggestionsUseCase,
    private val recentSearchRepository: RecentSearchRepository,
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC STATE
    // ═══════════════════════════════════════════════════════════════

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Initial)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recentSearches: StateFlow<List<RecentSearch>> = _recentSearches.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═══════════════════════════════════════════════════════════════

    /** Active search/suggestions job – cancelled whenever the query changes */
    private var activeJob: Job? = null

    init {
        // Debounce typing → fetch suggestions (NOT full search)
        _searchQuery
            .debounce(400)
            .onEach { query ->
                if (query.isNotBlank() && query.length >= 2) {
                    // Only fetch suggestions if we're NOT already showing Results
                    val current = _uiState.value
                    if (current !is SearchUiState.Results && current !is SearchUiState.Loading) {
                        fetchSuggestions(query)
                    }
                } else if (query.isEmpty()) {
                    _uiState.value = SearchUiState.Initial
                }
            }
            .launchIn(viewModelScope)

        loadRecentSearches()
    }

    // ═══════════════════════════════════════════════════════════════
    // QUERY MUTATIONS
    // ═══════════════════════════════════════════════════════════════

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query

        // If user clears the field, reset immediately (no debounce)
        if (query.isEmpty()) {
            cancelActiveJob()
            _uiState.value = SearchUiState.Initial
        }
    }

    fun clearSearch() {
        cancelActiveJob()
        _searchQuery.value = ""
        _uiState.value = SearchUiState.Initial
    }

    // ═══════════════════════════════════════════════════════════════
    // SEARCH ACTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Called when user explicitly submits (Enter / button).
     * Saves to history and performs full search.
     */
    fun submitSearch() {
        val query = _searchQuery.value.trim()
        if (query.isBlank() || query.length < 2) return

        cancelActiveJob()
        activeJob = viewModelScope.launch {
            // Save to history
            recentSearchRepository.addSearch(query)

            // Show loading
            _uiState.value = SearchUiState.Loading

            searchSongsUseCase(query)
                .onSuccess { songs ->
                    _uiState.value = if (songs.isEmpty()) {
                        SearchUiState.Empty(query)
                    } else {
                        SearchUiState.Results(query, songs)
                    }
                }
                .onFailure { exception ->
                    _uiState.value = SearchUiState.Error(
                        exception.message ?: "Search failed",
                    )
                }
        }
    }

    /**
     * When user clicks on a recent search item.
     */
    fun onRecentSearchClick(query: String) {
        _searchQuery.value = query
        submitSearch()
    }

    /**
     * When user clicks on a suggestion.
     */
    fun onSuggestionClick(suggestion: String) {
        _searchQuery.value = suggestion
        submitSearch()
    }

    // ═══════════════════════════════════════════════════════════════
    // RECENT SEARCH MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // SONG PLAYBACK
    // ═══════════════════════════════════════════════════════════════

    /**
     * When user taps a song to play.
     * Saves the *search query* (not song title) to history.
     */
    fun onSongClick(song: Song) {
        viewModelScope.launch {
            val currentQuery = _searchQuery.value.trim()
            if (currentQuery.isNotBlank() && currentQuery.length >= 2) {
                recentSearchRepository.addSearch(currentQuery)
            }

            songRepository.getStreamUrl(song.id, StreamQuality.BEST)
                .onSuccess { streamUrl ->
                    playerServiceConnection.playSong(song, streamUrl)
                }
                .onFailure { exception ->
                    _uiState.value = SearchUiState.Error(
                        "Failed to play: ${exception.message}",
                    )
                }
        }
    }

    /**
     * Dismiss error state – returns to Initial or re-shows last results.
     */
    fun clearError() {
        if (_uiState.value is SearchUiState.Error) {
            _uiState.value = if (_searchQuery.value.isEmpty()) {
                SearchUiState.Initial
            } else {
                SearchUiState.Initial // allow retry
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch autocomplete suggestions (does NOT save to history).
     */
    private fun fetchSuggestions(query: String) {
        cancelActiveJob()
        activeJob = viewModelScope.launch {
            getSearchSuggestionsUseCase(query)
                .onSuccess { suggestions ->
                    if (suggestions.isNotEmpty()) {
                        _uiState.value = SearchUiState.Suggestions(query, suggestions)
                    }
                    // If suggestions are empty, stay on current state (don't flash)
                }
            // Silently ignore suggestion failures (not critical)
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

    private fun cancelActiveJob() {
        activeJob?.cancel()
        activeJob = null
    }
}