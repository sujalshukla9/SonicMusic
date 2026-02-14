package com.sonicmusic.app.data.repository

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
    private val songDao: SongDao,
    private val settingsDataStore: SettingsDataStore
) : SearchRepository {

    // Simple in-memory cache
    private val searchCache = mutableMapOf<String, CacheEntry>()
    private val cacheDuration = TimeUnit.MINUTES.toMillis(5)
    private val fallbackCountryCode = "US"

    override fun searchSongsPaged(
        query: String,
        filters: SearchFilters
    ): Flow<PagingData<Song>> {
        return Pager(
            config = PagingConfig(
                pageSize = SearchPagingSource.DEFAULT_PAGE_SIZE,
                prefetchDistance = 5,
                enablePlaceholders = false,
                initialLoadSize = SearchPagingSource.DEFAULT_PAGE_SIZE * 2
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
        filters: SearchFilters
    ): Result<SearchResult> {
        val normalizedQuery = normalizeSearchTerm(query)
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)

        // Check cache first
        val cacheKey = "$normalizedQuery-$safeLimit-$safeOffset-${filters.hashCode()}"
        searchCache[cacheKey]?.let { entry ->
            if (entry.isValid()) {
                return Result.success(entry.result)
            } else {
                searchCache.remove(cacheKey)
            }
        }

        return try {
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
                        nextOffset = if (hasMore) nextOffsetCandidate else null
                    )
                    
                    // Cache the result
                    searchCache[cacheKey] = CacheEntry(searchResult)
                    
                    Result.success(searchResult)
                },
                onFailure = { exception ->
                    Result.failure(exception)
                }
            )
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
     * Cache entry with expiration
     */
    private inner class CacheEntry(
        val result: SearchResult,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        constructor(data: List<String>) : this(
            SearchResult(
                songs = emptyList(),
                totalCount = 0,
                hasMore = false,
                query = ""
            )
        ) {
            @Suppress("UNCHECKED_CAST")
            this.data = data as Any
        }
        
        var data: Any = result
        
        fun isValid(): Boolean {
            return System.currentTimeMillis() - timestamp < cacheDuration
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
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.UNKNOWN -> duration == 0 || duration in 60..600
            else -> false
        }
    }
}
