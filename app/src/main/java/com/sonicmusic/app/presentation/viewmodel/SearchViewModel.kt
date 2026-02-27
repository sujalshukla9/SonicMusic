package com.sonicmusic.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.player.audio.AudioEngine
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.domain.repository.SearchRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.presentation.state.ErrorType
import com.sonicmusic.app.presentation.state.PaginationState
import com.sonicmusic.app.presentation.state.SearchAction
import com.sonicmusic.app.presentation.state.SearchEffect
import com.sonicmusic.app.presentation.state.SearchFilters
import com.sonicmusic.app.presentation.state.SearchState
import com.sonicmusic.app.service.PlayerServiceConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * Enhanced Search ViewModel with MVI pattern, pagination, and improved state management
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val recentSearchRepository: RecentSearchRepository,
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection,
    private val audioEngine: AudioEngine
) : ViewModel() {

    // State
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Initial)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    // Effects (one-time events)
    private val _effects = Channel<SearchEffect>()
    val effects = _effects.receiveAsFlow()

    // Pagination
    private val pageSize = 100
    private var currentQuery: String = ""
    private var currentFilters: SearchFilters = SearchFilters()
    private var activeSearchToken: Long = 0L

    // Jobs
    private var searchJob: Job? = null
    private var suggestionsJob: Job? = null

    // Trending searches
    private val _trendingSearches = MutableStateFlow<List<String>>(emptyList())
    val trendingSearches: StateFlow<List<String>> = _trendingSearches.asStateFlow()

    init {
        // Debounced query changes for suggestions
        _searchQuery
            .debounce(300)
            .onEach { query ->
                if (query.isNotBlank() && query.length >= 2) {
                    fetchSuggestions(query)
                } else if (query.isEmpty()) {
                    _searchState.value = SearchState.Initial
                }
            }
            .launchIn(viewModelScope)

        loadRecentSearches()
        loadTrendingSearches()
    }

    private fun loadTrendingSearches() {
        viewModelScope.launch {
            searchRepository.getTrendingSearches()
                .onSuccess { _trendingSearches.value = it }
                .onFailure {
                    _trendingSearches.value = getFallbackTrendingSearches()
                }
        }
    }

    private fun getFallbackTrendingSearches(): List<String> {
        val countryName = Locale.getDefault().displayCountry.takeIf { it.isNotBlank() }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return if (countryName != null) {
            listOf(
                "Top Hits in $countryName",
                "Trending Songs $year",
                "New Releases",
                "Workout Mix",
                "Chill Vibes",
                "Party Mix"
            )
        } else {
            listOf(
                "Top Hits",
                "Trending Songs",
                "New Releases",
                "Workout Mix",
                "Chill Vibes",
                "Party Mix"
            )
        }
    }

    /**
     * Handle search actions (MVI pattern)
     */
    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> handleQueryChange(action.query)
            is SearchAction.SubmitSearch -> submitSearch(action.query)
            is SearchAction.SuggestionClicked -> handleSuggestionClick(action.suggestion)
            is SearchAction.RecentSearchClicked -> handleRecentSearchClick(action.query)
            is SearchAction.SongClicked -> handleSongClick(action.song)
            is SearchAction.DeleteRecentSearch -> deleteRecentSearch(action.query)
            is SearchAction.ClearAllRecentSearches -> clearAllRecentSearches()
            is SearchAction.ClearSearch -> clearSearch()
            is SearchAction.RetrySearch -> retrySearch()
            is SearchAction.LoadMore -> loadMoreResults()
            is SearchAction.UpdateFilters -> updateFilters(action.filters)
            is SearchAction.DismissError -> dismissError()
        }
    }

    private fun handleQueryChange(query: String) {
        _searchQuery.value = query

        if (query.isBlank()) {
            cancelActiveJobs()
            currentQuery = ""
            _searchState.value = SearchState.Initial
        }
    }

    private fun submitSearch(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 2) return

        cancelActiveJobs()
        currentQuery = trimmedQuery
        _searchQuery.value = trimmedQuery
        activeSearchToken += 1L
        val searchToken = activeSearchToken

        _searchState.value = SearchState.LoadingResults(trimmedQuery)

        searchJob = viewModelScope.launch {
            searchRepository.searchSongs(
                query = trimmedQuery,
                limit = pageSize,
                offset = 0,
                filters = currentFilters
            ).fold(
                onSuccess = { result ->
                    if (searchToken != activeSearchToken) return@fold

                    val songs = result.songs.distinctBy { it.id }
                    if (songs.isEmpty()) {
                        _searchState.value = SearchState.Empty(
                            query = trimmedQuery,
                            suggestions = getAlternativeSuggestions(trimmedQuery)
                        )
                    } else {
                        recentSearchRepository.addSearch(trimmedQuery)
                        _searchState.value = SearchState.Results(
                            query = trimmedQuery,
                            songs = songs,
                            totalCount = songs.size,
                            paginationState = if (result.hasMore) {
                                PaginationState.Idle
                            } else {
                                PaginationState.NoMoreData
                            },
                            filters = currentFilters,
                            continuationToken = result.continuationToken
                        )
                        _effects.send(SearchEffect.ScrollToTop(trimmedQuery))
                    }
                },
                onFailure = { exception ->
                    if (searchToken != activeSearchToken) return@fold
                    _searchState.value = SearchState.Error(
                        message = getErrorMessage(exception),
                        query = trimmedQuery,
                        errorType = classifyError(exception)
                    )
                }
            )
        }
    }

    private fun fetchSuggestions(query: String) {
        suggestionsJob?.cancel()

        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 2) return

        val currentState = _searchState.value
        if (currentState is SearchState.Results && currentState.query == trimmedQuery) {
            return
        }
        if (currentState is SearchState.LoadingResults && currentState.query == trimmedQuery) {
            return
        }

        _searchState.value = SearchState.LoadingSuggestions(trimmedQuery)

        suggestionsJob = viewModelScope.launch {
            searchRepository.getSearchSuggestions(trimmedQuery).fold(
                onSuccess = { suggestions ->
                    if (_searchQuery.value.trim() != trimmedQuery) return@fold
                    _searchState.value = SearchState.Suggestions(
                        query = trimmedQuery,
                        suggestions = suggestions,
                        recentSearches = _recentSearches.value
                            .filter { it.contains(trimmedQuery, ignoreCase = true) }
                            .take(3)
                    )
                },
                onFailure = {
                    if (_searchQuery.value.trim() != trimmedQuery) return@fold
                    _searchState.value = SearchState.Suggestions(
                        query = trimmedQuery,
                        suggestions = emptyList(),
                        recentSearches = _recentSearches.value
                            .filter { it.contains(trimmedQuery, ignoreCase = true) }
                            .take(3)
                    )
                }
            )
        }
    }

    private fun handleSuggestionClick(suggestion: String) {
        _searchQuery.value = suggestion
        submitSearch(suggestion)
        viewModelScope.launch {
            _effects.send(SearchEffect.ClearFocus)
        }
    }

    private fun handleRecentSearchClick(query: String) {
        _searchQuery.value = query
        submitSearch(query)
        viewModelScope.launch {
            _effects.send(SearchEffect.ClearFocus)
        }
    }

    private fun handleSongClick(song: Song) {
        viewModelScope.launch {
            // Save current query to recent searches with metadata
            val currentQuery = _searchQuery.value.trim()
            if (currentQuery.isNotBlank() && currentQuery.length >= 2) {
                recentSearchRepository.addSearch(
                    query = currentQuery,
                    resultId = song.id,
                    resultType = song.contentType.name
                )
            }

            // Get stream URL and play
            songRepository.getStreamUrl(song.id, audioEngine.getOptimalQuality())
                .fold(
                    onSuccess = { streamUrl ->
                        playerServiceConnection.playSong(song, streamUrl)
                        _effects.send(SearchEffect.NavigateToPlayer(song))
                    },
                    onFailure = { exception ->
                        _effects.send(
                            SearchEffect.ShowSnackbar(
                                "Failed to play: ${exception.message}"
                            )
                        )
                    }
                )
        }
    }

    private fun deleteRecentSearch(query: String) {
        viewModelScope.launch {
            recentSearchRepository.deleteSearch(query)
        }
    }

    private fun clearAllRecentSearches() {
        viewModelScope.launch {
            recentSearchRepository.clearAllSearches()
        }
    }

    private fun clearSearch() {
        cancelActiveJobs()
        _searchQuery.value = ""
        _searchState.value = SearchState.Initial
    }

    private fun retrySearch() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results && currentState.paginationState is PaginationState.Error) {
            loadMoreResults()
            return
        }

        val query = when (currentState) {
            is SearchState.Error -> currentState.query
            is SearchState.Empty -> currentState.query
            else -> _searchQuery.value
        }
        
        if (!query.isNullOrBlank()) {
            submitSearch(query)
        }
    }

    private fun loadMoreResults() {
        val currentState = _searchState.value
        if (currentState !is SearchState.Results) return
        if (currentState.paginationState is PaginationState.Loading) return
        if (currentState.paginationState is PaginationState.NoMoreData) return

        _searchState.value = currentState.copy(
            paginationState = PaginationState.Loading
        )

        viewModelScope.launch {
            val offset = currentState.songs.size

            searchRepository.searchSongs(
                query = currentState.query,
                limit = pageSize,
                offset = offset,
                continuation = currentState.continuationToken,
                filters = currentState.filters
            ).fold(
                onSuccess = { result ->
                    val latestState = _searchState.value as? SearchState.Results
                    if (latestState == null || latestState.query != currentState.query) return@fold

                    val existingIds = latestState.songs.asSequence().map { it.id }.toHashSet()
                    val appendedSongs = result.songs.filterNot { existingIds.contains(it.id) }
                    val mergedSongs = latestState.songs + appendedSongs
                    val hasMoreData = result.hasMore && appendedSongs.isNotEmpty()

                    _searchState.value = latestState.copy(
                        songs = mergedSongs,
                        totalCount = mergedSongs.size,
                        paginationState = if (hasMoreData) {
                            PaginationState.Idle
                        } else {
                            PaginationState.NoMoreData
                        },
                        continuationToken = result.continuationToken
                    )
                },
                onFailure = { exception ->
                    val latestState = _searchState.value as? SearchState.Results
                    if (latestState == null || latestState.query != currentState.query) return@fold

                    _searchState.value = latestState.copy(
                        paginationState = PaginationState.Error(
                            getErrorMessage(exception)
                        )
                    )
                }
            )
        }
    }

    private fun updateFilters(filters: SearchFilters) {
        currentFilters = filters
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
            _searchState.value = currentState.copy(filters = filters)
            // Re-submit search with new filters
            submitSearch(currentState.query)
        }
    }

    private fun dismissError() {
        val currentState = _searchState.value
        if (currentState is SearchState.Error) {
            _searchState.value = SearchState.Initial
        }
    }

    private fun loadRecentSearches() {
        viewModelScope.launch {
            recentSearchRepository.getRecentSearches(10)
                .collect { searches ->
                    val recentQueries = searches.map { it.query }
                    _recentSearches.value = recentQueries

                    val state = _searchState.value
                    if (state is SearchState.Suggestions) {
                        _searchState.value = state.copy(
                            recentSearches = recentQueries
                                .filter { it.contains(state.query, ignoreCase = true) }
                                .take(3)
                        )
                    }
                }
        }
    }

    private fun cancelActiveJobs() {
        searchJob?.cancel()
        suggestionsJob?.cancel()
    }

    private fun getAlternativeSuggestions(query: String): List<String> {
        return listOf(
            "$query official",
            "$query lyrics",
            "$query audio",
            "$query cover"
        )
    }

    private fun getErrorMessage(exception: Throwable): String {
        return when (exception) {
            is SocketTimeoutException -> "Connection timed out. Please try again."
            is IOException -> "No internet connection. Check your network."
            else -> exception.message ?: "An unexpected error occurred"
        }
    }

    private fun classifyError(exception: Throwable): ErrorType {
        return when (exception) {
            is SocketTimeoutException -> ErrorType.Timeout
            is IOException -> ErrorType.Network
            else -> ErrorType.Unknown
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveJobs()
    }
}
