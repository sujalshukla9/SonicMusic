package com.sonicmusic.app.presentation.state

import androidx.compose.runtime.Immutable
import com.sonicmusic.app.domain.model.Song

/**
 * Comprehensive search state management using MVI pattern
 * Handles all search-related UI states with pagination support
 */
@Immutable
sealed interface SearchState {
    
    /**
     * Initial state - shows recent searches and browse categories
     */
    data object Initial : SearchState
    
    /**
     * Loading suggestions while typing
     */
    data class LoadingSuggestions(
        val query: String
    ) : SearchState
    
    /**
     * Showing autocomplete suggestions
     */
    data class Suggestions(
        val query: String,
        val suggestions: List<String>,
        val recentSearches: List<String> = emptyList()
    ) : SearchState
    
    /**
     * Loading initial search results
     */
    data class LoadingResults(
        val query: String
    ) : SearchState
    
    /**
     * Active search results with pagination support
     */
    data class Results(
        val query: String,
        val songs: List<Song>,
        val paginationState: PaginationState = PaginationState.Idle,
        val totalCount: Int = songs.size,
        val filters: SearchFilters = SearchFilters(),
        val continuationToken: String? = null
    ) : SearchState
    
    /**
     * Empty results state
     */
    data class Empty(
        val query: String,
        val suggestions: List<String> = emptyList()
    ) : SearchState
    
    /**
     * Error state with retry capability
     */
    data class Error(
        val message: String,
        val query: String? = null,
        val isRecoverable: Boolean = true,
        val errorType: ErrorType = ErrorType.Unknown
    ) : SearchState
}

/**
 * Pagination states for infinite scroll
 */
@Immutable
sealed interface PaginationState {
    data object Idle : PaginationState
    data object Loading : PaginationState
    data class Error(val message: String) : PaginationState
    data object NoMoreData : PaginationState
}

/**
 * Error types for better error handling
 */
enum class ErrorType {
    Network,
    Server,
    Timeout,
    NoResults,
    Unknown
}

/**
 * Search filters for refining results
 */
@Immutable
data class SearchFilters(
    val duration: DurationFilter = DurationFilter.Any,
    val sortBy: SortOption = SortOption.Relevance,
    val contentType: ContentTypeFilter = ContentTypeFilter.All
)

enum class DurationFilter {
    Any,
    Short,      // < 4 minutes
    Medium,     // 4-10 minutes
    Long        // > 10 minutes
}

enum class SortOption {
    Relevance,
    ViewCount,
    UploadDate,
    Rating
}

enum class ContentTypeFilter {
    All,
    Songs,
    Videos,
    Playlists
}

/**
 * Search actions/events for MVI pattern
 */
sealed interface SearchAction {
    data class QueryChanged(val query: String) : SearchAction
    data class SubmitSearch(val query: String) : SearchAction
    data class SuggestionClicked(val suggestion: String) : SearchAction
    data class RecentSearchClicked(val query: String) : SearchAction
    data class SongClicked(val song: Song) : SearchAction
    data class DeleteRecentSearch(val query: String) : SearchAction
    data object ClearAllRecentSearches : SearchAction
    data object ClearSearch : SearchAction
    data object RetrySearch : SearchAction
    data object LoadMore : SearchAction
    data class UpdateFilters(val filters: SearchFilters) : SearchAction
    data object DismissError : SearchAction
}

/**
 * Search side effects for one-time events
 */
sealed interface SearchEffect {
    data class ShowSnackbar(val message: String) : SearchEffect
    data class NavigateToPlayer(val song: Song) : SearchEffect
    data object ClearFocus : SearchEffect
    data class ScrollToTop(val query: String) : SearchEffect
}

/**
 * Search query validation result
 */
sealed interface QueryValidation {
    data object Valid : QueryValidation
    data class Invalid(val reason: String) : QueryValidation
    
    companion object {
        fun validate(query: String): QueryValidation {
            return when {
                query.isBlank() -> Invalid("Query cannot be empty")
                query.length < 2 -> Invalid("Query too short")
                query.length > 100 -> Invalid("Query too long")
                else -> Valid
            }
        }
    }
}