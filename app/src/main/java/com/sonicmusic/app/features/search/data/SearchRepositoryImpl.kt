package com.sonicmusic.app.features.search.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.paging.SearchPagingSource
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SearchRepository
import com.sonicmusic.app.domain.repository.SearchResult
import com.sonicmusic.app.shared.state.SearchFilters
import com.sonicmusic.app.features.recommendations.data.RegionalRecommendationHelper
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.shared.state.ContentTypeFilter
import com.sonicmusic.app.shared.state.DurationFilter
import com.sonicmusic.app.shared.state.SortOption
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SearchRepository with caching and pagination
 */
@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val songDao: SongDao,
    private val settingsDataStore: SettingsDataStore
) : SearchRepository {

    private sealed interface CacheValue {
        data class Search(val result: SearchResult) : CacheValue
        data class Suggestions(val suggestions: List<String>) : CacheValue
    }

    // Bounded in-memory cache (LRU, max 50 entries) to prevent unbounded memory growth
    private val searchCache = LruCache<String, CacheEntry>(50)
    private val fallbackCountryCode = "US"

    override fun searchSongsPaged(
        query: String,
        filters: SearchFilters
    ): Flow<PagingData<Song>> {
        return Pager(
            config = PagingConfig(
                pageSize = SearchPagingSource.DEFAULT_PAGE_SIZE,
                prefetchDistance = 3,
                enablePlaceholders = false,
                initialLoadSize = SearchPagingSource.DEFAULT_PAGE_SIZE
            ),
            pagingSourceFactory = {
                SearchPagingSource(
                    youTubeiService = youTubeiService,
                    query = query,
                    filters = filters
                )
            }
        ).flow
    }

    override suspend fun searchSongs(
        query: String,
        limit: Int,
        offset: Int,
        continuation: String?,
        filters: SearchFilters
    ): Result<SearchResult> {
        val normalizedQuery = normalizeSearchTerm(query)
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val safeContinuation = continuation?.trim()?.takeIf { it.isNotEmpty() }
        val isInitialTokenPage = safeOffset == 0 && safeContinuation == null
        val isTokenPagingRequest = safeContinuation != null || safeOffset == 0

        // Cache only first-page searches; continuation pages are query-session specific.
        val cacheKey = if (isInitialTokenPage) {
            "$normalizedQuery-$safeLimit-${filters.hashCode()}"
        } else {
            null
        }
        if (cacheKey != null) {
            val entry = searchCache.get(cacheKey)
            if (entry != null) {
                if (entry.isValid()) {
                    val cached = (entry.value as? CacheValue.Search)?.result
                    if (cached != null) {
                        return Result.success(cached)
                    }
                } else {
                    searchCache.remove(cacheKey)
                }
            }
        }

        return try {
            if (isTokenPagingRequest) {
                // On first page, fire video search concurrently with song search for the Videos section
                val (videoSearchResult, result) = coroutineScope {
                    val videoDeferred = if (isInitialTokenPage) {
                        async {
                            youTubeiService.searchVideos(normalizedQuery, 20)
                                .getOrNull()
                        }
                    } else {
                        null
                    }
                    val songsDeferred = async {
                        youTubeiService.searchSongsPage(
                            query = normalizedQuery,
                            continuationToken = safeContinuation,
                            limit = safeLimit
                        )
                    }
                    Pair(videoDeferred?.await(), songsDeferred.await())
                }
                val videoResults = videoSearchResult?.songs.orEmpty()
                val videoContinuation = videoSearchResult?.continuationToken

                result.fold(
                    onSuccess = { page ->
                        val pageSongs = applyFilters(
                            songs = page.songs.distinctBy { it.id },
                            filters = filters
                        ).take(safeLimit)


                        val nextContinuation = page.continuationToken?.takeIf { it.isNotBlank() }
                        val hasMore = if (nextContinuation != null) {
                            true
                        } else {
                            // Offset fallback path (e.g. NewPipe first page)
                            page.songs.size >= safeLimit
                        }
                        val nextOffsetCandidate = if (nextContinuation == null && hasMore) {
                            safeOffset + safeLimit
                        } else {
                            null
                        }

                        // Filter out videos that are already in song results
                        val songIds = pageSongs.map { it.id }.toHashSet()
                        val filteredVideos = videoResults.filterNot { songIds.contains(it.id) }

                        val searchResult = SearchResult(
                            songs = pageSongs,
                            videos = filteredVideos,
                            totalCount = safeOffset + pageSongs.size,
                            hasMore = hasMore,
                            query = normalizedQuery,
                            nextOffset = nextOffsetCandidate,
                            continuationToken = nextContinuation,
                            videoContinuationToken = videoContinuation
                        )

                        cacheKey?.let { key ->
                            searchCache.put(key, CacheEntry(CacheValue.Search(searchResult)))
                        }

                        Result.success(searchResult)
                    },
                    onFailure = { exception ->
                        Result.failure(exception)
                    }
                )
            } else {
                // Legacy offset-based path retained for compatibility.
                val result = youTubeiService.searchSongs(
                    query = normalizedQuery,
                    limit = safeLimit,
                    offset = safeOffset
                )

                result.fold(
                    onSuccess = { songsPage ->
                        val pageSongs = applyFilters(
                            songs = songsPage.distinctBy { it.id },
                            filters = filters
                        ).take(safeLimit)


                        val nextOffsetCandidate = safeOffset + safeLimit
                        val hasMore = songsPage.size >= safeLimit

                        val searchResult = SearchResult(
                            songs = pageSongs,
                            totalCount = safeOffset + pageSongs.size,
                            hasMore = hasMore,
                            query = normalizedQuery,
                            nextOffset = if (hasMore) nextOffsetCandidate else null,
                            continuationToken = null
                        )

                        cacheKey?.let { key ->
                            searchCache.put(key, CacheEntry(CacheValue.Search(searchResult)))
                        }

                        Result.success(searchResult)
                    },
                    onFailure = { exception ->
                        Result.failure(exception)
                    }
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchMixed(query: String, limit: Int): Result<List<Song>> {
        // Check cache first (reusing same cache for simplicity, though keys might need differentiation if strictly needed)
        val cacheKey = "mixed-$query-$limit"
        val entry = searchCache.get(cacheKey)
        if (entry != null) {
            if (entry.isValid()) {
                val songs = (entry.value as? CacheValue.Search)?.result?.songs
                if (songs != null) return Result.success(songs)
            } else {
                searchCache.remove(cacheKey)
            }
        }

        return try {
            val result = youTubeiService.searchMixed(query, limit)
            
            result.fold(
                onSuccess = { songs ->
                     // Cache
                    val searchResult = SearchResult(
                        songs = songs,
                        totalCount = songs.size,
                        hasMore = false, // Mixed search doesn't support pagination in this MVP phase
                        query = query
                    )
                    searchCache.put(cacheKey, CacheEntry(CacheValue.Search(searchResult)))
                    Result.success(songs)
                },
                onFailure = { exception ->
                    Result.failure(exception)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSearchSuggestions(query: String): Result<List<String>> {
        // Check cache for suggestions
        val cacheKey = "suggestions-$query"
        searchCache.get(cacheKey)?.let { entry ->
            if (entry.isValid()) {
                val suggestions = (entry.value as? CacheValue.Suggestions)?.suggestions
                if (suggestions != null) return Result.success(suggestions)
            }
        }

        return youTubeiService.getSearchSuggestions(query).map { suggestions ->
            // Cache suggestions
            searchCache.put(cacheKey, CacheEntry(CacheValue.Suggestions(suggestions)))
            suggestions.take(15)
        }
    }

    override suspend fun getTrendingSearches(): Result<List<String>> {
        val region = getRegionContext()
        val regionalFallback = RegionalRecommendationHelper.fallbackTrendingSearches(
            countryCode = region.countryCode,
            countryName = region.countryName
        )

        return try {
            val trendingSongs = youTubeiService.getTrendingSongs(16).getOrNull().orEmpty()
            if (trendingSongs.isEmpty()) {
                Result.success(regionalFallback)
            } else {
                val fromSongs = trendingSongs
                    .flatMap { song ->
                        listOf(song.title, song.artist)
                    }
                    .map { term -> normalizeSearchTerm(term) }
                    .filter { term -> term.length >= 3 }
                    .distinct()
                    .take(8)

                Result.success((fromSongs + regionalFallback).distinct().take(10))
            }
        } catch (e: Exception) {
            Result.success(regionalFallback)
        }
    }

    override suspend fun searchVideos(query: String, limit: Int, continuation: String?): Result<SearchResult> {
        return try {
            youTubeiService.searchVideos(query, limit, continuation).map { page ->
                SearchResult(
                    songs = emptyList(),
                    videos = page.songs,
                    totalCount = page.songs.size,
                    hasMore = !page.continuationToken.isNullOrBlank(),
                    query = query,
                    videoContinuationToken = page.continuationToken
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getTopArtists(limit: Int): Result<List<Song>> {
        return try {
            youTubeiService.getTopArtists(limit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadMoreArtists(continuationToken: String): Result<com.sonicmusic.app.data.remote.source.YouTubeiService.BrowsePageResult> {
        return try {
            youTubeiService.loadMoreArtists(continuationToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getTopAlbums(limit: Int): Result<List<Song>> {
        return try {
            youTubeiService.getTopAlbums(limit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadMoreAlbums(continuationToken: String): Result<com.sonicmusic.app.data.remote.source.YouTubeiService.BrowsePageResult> {
        return try {
            youTubeiService.loadMoreAlbums(continuationToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearCache() {
        searchCache.evictAll()
    }

    /**
     * Cache entry with expiration.
     */
    private class CacheEntry(
        val value: CacheValue,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isValid(): Boolean {
            return System.currentTimeMillis() - timestamp < cacheDuration
        }

        companion object {
            private val cacheDuration = TimeUnit.MINUTES.toMillis(5)
        }
    }

    private suspend fun getRegionContext(): RegionContext {
        val countryCode = RegionalRecommendationHelper.normalizeCountryCode(settingsDataStore.countryCode.first())
            ?: RegionalRecommendationHelper.normalizeCountryCode(Locale.getDefault().country)
            ?: fallbackCountryCode

        val countryName = RegionalRecommendationHelper.canonicalCountryName(
            countryCode = countryCode,
            cachedName = settingsDataStore.countryName.first()
        )

        return RegionContext(countryCode = countryCode, countryName = countryName)
    }

    private fun normalizeSearchTerm(value: String): String {
        return value.replace("\\s+".toRegex(), " ").trim()
    }

    private fun applyFilters(songs: List<Song>, filters: SearchFilters): List<Song> {
        val typeFiltered = songs.filter { it.isAllowedContentType(filters.contentType) }

        val durationFiltered = typeFiltered.filter { song ->
            when (filters.duration) {
                DurationFilter.Any -> true
                DurationFilter.Short -> song.duration in 1..240
                DurationFilter.Medium -> song.duration in 241..600
                DurationFilter.Long -> song.duration > 600
            }
        }

        return when (filters.sortBy) {
            SortOption.Relevance -> durationFiltered
            SortOption.ViewCount -> durationFiltered.sortedByDescending { it.viewCount ?: 0L }
            SortOption.UploadDate -> durationFiltered.sortedByDescending { it.year ?: 0 }
            SortOption.Rating -> durationFiltered
        }
    }

    private data class RegionContext(
        val countryCode: String,
        val countryName: String
    )

    private fun Song.isAllowedContentType(filter: ContentTypeFilter): Boolean {
        return when (filter) {
            ContentTypeFilter.All -> when (contentType) {
                ContentType.SONG, ContentType.VIDEO, ContentType.UNKNOWN, ContentType.PLAYLIST -> true
                else -> false
            }
            ContentTypeFilter.Songs -> contentType == ContentType.SONG || contentType == ContentType.UNKNOWN
            ContentTypeFilter.Videos -> contentType == ContentType.VIDEO
            ContentTypeFilter.Playlists -> contentType == ContentType.PLAYLIST
        }
    }
}
