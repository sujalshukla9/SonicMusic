package com.sonicmusic.app.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.paging.SearchPagingSource
import com.sonicmusic.app.data.remote.source.NewPipeService
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SearchRepository
import com.sonicmusic.app.domain.repository.SearchResult
import com.sonicmusic.app.presentation.state.SearchFilters
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.presentation.state.ContentTypeFilter
import com.sonicmusic.app.presentation.state.DurationFilter
import com.sonicmusic.app.presentation.state.SortOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SearchRepository with caching and pagination
 */
@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val newPipeService: NewPipeService,
    private val songDao: SongDao,
    private val settingsDataStore: SettingsDataStore
) : SearchRepository {

    // Simple in-memory cache
    private val searchCache = mutableMapOf<String, CacheEntry>()
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
        cacheKey?.let { key ->
            searchCache[key]?.let { entry ->
                if (entry.isValid()) {
                    val cached = entry.result ?: return@let
                    return Result.success(cached)
                } else {
                    searchCache.remove(key)
                }
            }
        }

        return try {
            if (isTokenPagingRequest) {
                val primaryResult = youTubeiService.searchSongsPage(
                    query = normalizedQuery,
                    continuationToken = safeContinuation,
                    limit = safeLimit
                )

                val result = if (primaryResult.isSuccess) {
                    primaryResult
                } else if (safeContinuation == null) {
                    // First page fallback only. Continuation requests cannot switch backend.
                    newPipeService.searchSongs(
                        query = normalizedQuery,
                        limit = safeLimit,
                        offset = 0
                    ).map { fallbackSongs ->
                        com.sonicmusic.app.data.remote.source.YouTubeiService.SongSearchPage(
                            songs = fallbackSongs,
                            continuationToken = null
                        )
                    }.recoverCatching {
                        primaryResult.getOrThrow()
                    }
                } else {
                    primaryResult
                }

                result.fold(
                    onSuccess = { page ->
                        val pageSongs = applyFilters(
                            songs = page.songs.distinctBy { it.id },
                            filters = filters
                        ).take(safeLimit)

                        if (pageSongs.isNotEmpty()) {
                            songDao.insertAll(pageSongs.map { it.toEntity() })
                        }

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

                        val searchResult = SearchResult(
                            songs = pageSongs,
                            totalCount = safeOffset + pageSongs.size,
                            hasMore = hasMore,
                            query = normalizedQuery,
                            nextOffset = nextOffsetCandidate,
                            continuationToken = nextContinuation
                        )

                        cacheKey?.let { key ->
                            searchCache[key] = CacheEntry(searchResult)
                        }

                        Result.success(searchResult)
                    },
                    onFailure = { exception ->
                        Result.failure(exception)
                    }
                )
            } else {
                // Legacy offset-based path retained for compatibility.
                val primaryResult = youTubeiService.searchSongs(
                    query = normalizedQuery,
                    limit = safeLimit,
                    offset = safeOffset
                )

                val result = if (!primaryResult.getOrNull().isNullOrEmpty()) {
                    primaryResult
                } else {
                    newPipeService.searchSongs(
                        query = normalizedQuery,
                        limit = safeLimit,
                        offset = safeOffset
                    ).recoverCatching {
                        primaryResult.getOrThrow()
                    }
                }

                result.fold(
                    onSuccess = { songsPage ->
                        val pageSongs = applyFilters(
                            songs = songsPage.distinctBy { it.id },
                            filters = filters
                        ).take(safeLimit)

                        if (pageSongs.isNotEmpty()) {
                            songDao.insertAll(pageSongs.map { it.toEntity() })
                        }

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
                            searchCache[key] = CacheEntry(searchResult)
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
        val entry = searchCache[cacheKey]
        if (entry != null) {
            if (entry.isValid()) {
                @Suppress("UNCHECKED_CAST")
                val songs = (entry.data as? SearchResult)?.songs
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
                    searchCache[cacheKey] = CacheEntry(searchResult)
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
        searchCache[cacheKey]?.let { entry ->
            if (entry.isValid()) {
                @Suppress("UNCHECKED_CAST")
                return Result.success(entry.data as List<String>)
            }
        }

        return youTubeiService.getSearchSuggestions(query).map { suggestions ->
            // Cache suggestions
            searchCache[cacheKey] = CacheEntry(suggestions)
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

    override suspend fun clearCache() {
        searchCache.clear()
    }

    /**
     * Cache entry with expiration.
     * Stores any cacheable payload — SearchResult or List<String> — as a single `data` field.
     */
    private class CacheEntry(
        val data: Any,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        /** Return the data as a [SearchResult] if it is one, otherwise null. */
        val result: SearchResult?
            get() = data as? SearchResult

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
        val strictSongs = songs.filter { it.isStrictSong() }

        val durationFiltered = strictSongs.filter { song ->
            when (filters.duration) {
                DurationFilter.Any -> true
                DurationFilter.Short -> song.duration in 1..240
                DurationFilter.Medium -> song.duration in 241..600
                DurationFilter.Long -> song.duration > 600
            }
        }

        val contentFiltered = durationFiltered.filter { song ->
            when (filters.contentType) {
                ContentTypeFilter.All -> true
                ContentTypeFilter.Songs -> song.contentType == ContentType.SONG
                ContentTypeFilter.Videos -> song.contentType == ContentType.VIDEO
                ContentTypeFilter.Playlists -> song.contentType == ContentType.PLAYLIST
            }
        }

        return when (filters.sortBy) {
            SortOption.Relevance -> contentFiltered
            SortOption.ViewCount -> contentFiltered.sortedByDescending { it.viewCount ?: 0L }
            SortOption.UploadDate -> contentFiltered.sortedByDescending { it.year ?: 0 }
            SortOption.Rating -> contentFiltered
        }
    }

    private data class RegionContext(
        val countryCode: String,
        val countryName: String
    )

    private fun Song.isStrictSong(): Boolean {
        // Accept songs and videos from YouTube Music API
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.VIDEO -> true  // Music videos are valid search results
            ContentType.UNKNOWN -> true // YTM API already returns songs-only
            else -> false
        }
    }
}
