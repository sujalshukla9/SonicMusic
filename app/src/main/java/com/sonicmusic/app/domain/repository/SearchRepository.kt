package com.sonicmusic.app.domain.repository

import androidx.paging.PagingData
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.state.SearchFilters
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for search operations with pagination support
 */
interface SearchRepository {
    
    /**
     * Search for songs with pagination support
     * @param query Search query string
     * @param filters Optional search filters
     * @return Flow of paginated search results
     */
    fun searchSongsPaged(
        query: String, 
        filters: SearchFilters = SearchFilters()
    ): Flow<PagingData<Song>>
    
    /**
     * Search for songs (non-paged, for initial/quick searches)
     * @param query Search query string
     * @param limit Maximum number of results
     * @param offset Offset for pagination
     * @param continuation Continuation token for token-based pagination
     * @return Result containing list of songs
     */
    suspend fun searchSongs(
        query: String,
        limit: Int = 100,
        offset: Int = 0,
        continuation: String? = null,
        filters: SearchFilters = SearchFilters()
    ): Result<SearchResult>

    /**
     * Search for mixed content types (Songs, Videos, Albums, Artists, Playlists)
     * @param query Search query string
     * @param limit Maximum number of results
     * @return Result containing list of mixed content items (mapped to Song)
     */
    suspend fun searchMixed(
        query: String,
        limit: Int = 100
    ): Result<List<Song>>
    
    /**
     * Get search suggestions for autocomplete
     * @param query Partial query string
     * @return Result containing list of suggestions
     */
    suspend fun getSearchSuggestions(query: String): Result<List<String>>
    
    /**
     * Get trending/popular search queries
     * @return List of trending searches
     */
    suspend fun getTrendingSearches(): Result<List<String>>
    
    /**
     * Search for video content only, with continuation-token pagination.
     * @param query Search query string
     * @param limit Maximum number of results per page
     * @param continuation Continuation token for loading more (null = first page)
     * @return Result containing SearchResult with videos and continuation token
     */
    suspend fun searchVideos(
        query: String,
        limit: Int = 20,
        continuation: String? = null
    ): Result<SearchResult>

    /**
     * Get top artists from YTM charts for the user's region.
     */
    suspend fun getTopArtists(limit: Int = 50): Result<List<Song>>

    /**
     * Load more artists using continuation token
     */
    suspend fun loadMoreArtists(continuationToken: String): Result<com.sonicmusic.app.data.remote.source.YouTubeiService.BrowsePageResult>

    /**
     * Get top albums from YTM charts for the user's region.
     */
    suspend fun getTopAlbums(limit: Int = 50): Result<List<Song>>

    /**
     * Load more albums using continuation token
     */
    suspend fun loadMoreAlbums(continuationToken: String): Result<com.sonicmusic.app.data.remote.source.YouTubeiService.BrowsePageResult>

    /**
     * Clear search cache
     */
    suspend fun clearCache()
}

/**
 * Search result data class with metadata
 */
data class SearchResult(
    val songs: List<Song>,
    val videos: List<Song> = emptyList(),
    val totalCount: Int,
    val hasMore: Boolean,
    val query: String,
    val nextOffset: Int? = null,
    val continuationToken: String? = null,
    val videoContinuationToken: String? = null
)
