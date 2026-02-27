package com.sonicmusic.app.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.state.SearchFilters
import retrofit2.HttpException
import java.io.IOException

/**
 * Paging source for search results with infinite scroll support
 */
class SearchPagingSource(
    private val youTubeiService: YouTubeiService,
    private val query: String,
    private val filters: SearchFilters = SearchFilters(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE
) : PagingSource<Int, Song>() {

    override fun getRefreshKey(state: PagingState<Int, Song>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Song> {
        val offset = params.key ?: 0
        
        return try {
            // Load data from YouTube service
            val result = youTubeiService.searchSongs(
                query = query,
                limit = params.loadSize,
                offset = offset
            )
            
            result.fold(
                onSuccess = { songs ->
                    // Apply local filters
                    val filteredSongs = applyFilters(songs, filters)
                    
                    // Determine if there's more data
                    val hasMore = songs.size >= params.loadSize
                    
                    LoadResult.Page(
                        data = filteredSongs,
                        prevKey = if (offset == 0) null else offset - params.loadSize,
                        nextKey = if (hasMore) offset + params.loadSize else null
                    )
                },
                onFailure = { exception ->
                    LoadResult.Error(exception)
                }
            )
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: HttpException) {
            LoadResult.Error(e)
        }
    }
    
    /**
     * Apply local filters to search results
     */
    private fun applyFilters(songs: List<Song>, filters: SearchFilters): List<Song> {
        // Accept SONG and UNKNOWN (YTM API already returns songs-only via SearchFilter.Song)
        val musicSongs = songs.filter { song ->
            when (song.contentType) {
                ContentType.SONG -> true
                ContentType.VIDEO -> true  // Music videos are valid search results
                ContentType.UNKNOWN -> true
                else -> false
            }
        }

        return musicSongs.filter { song ->
            // Duration filter (user-selected)
            when (filters.duration) {
                com.sonicmusic.app.presentation.state.DurationFilter.Any -> true
                com.sonicmusic.app.presentation.state.DurationFilter.Short -> song.duration in 0..240
                com.sonicmusic.app.presentation.state.DurationFilter.Medium -> song.duration in 240..600
                com.sonicmusic.app.presentation.state.DurationFilter.Long -> song.duration > 600
            }
        }
    }
    
    companion object {
        const val DEFAULT_PAGE_SIZE = 40
    }
}
