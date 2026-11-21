package com.sonicmusic.app.features.charts.data

import android.util.Log
import com.sonicmusic.app.data.local.dao.BrowseSectionDao
import com.sonicmusic.app.data.local.entity.BrowseSectionEntity
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ChartSection
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.MoodGenre
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.BrowseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [BrowseRepository] backed by YTMusic browse endpoints.
 *
 * Uses a two-tier caching strategy:
 * 1. **In-memory cache** with per-section TTL for hot reads
 * 2. **Room cache** via [BrowseSectionDao] for offline-first / cold reads
 *
 * Network calls delegate to [YouTubeiService]'s browse methods.
 */
@Singleton
class BrowseRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val browseSectionDao: BrowseSectionDao
) : BrowseRepository {

    companion object {
        private const val TAG = "BrowseRepo"

        // Cache section keys
        private const val KEY_MOODS_GENRES = "moods_and_genres"
        private const val KEY_CHARTS = "charts_top_songs"
        private const val KEY_TRENDING_ALBUMS = "trending_albums"
        private const val KEY_TRENDING_ARTISTS = "trending_artists"

        // Cache TTLs (in ms)
        private const val TTL_MOODS_GENRES = 30 * 60 * 1000L    // 30 minutes
        private const val TTL_CHARTS = 60 * 60 * 1000L          // 1 hour
        private const val TTL_TRENDING = 15 * 60 * 1000L        // 15 minutes
    }

    // ══════════════════════════════════════════
    // IN-MEMORY CACHE
    // ══════════════════════════════════════════

    @Volatile private var cachedMoodGenres: List<MoodGenre>? = null
    @Volatile private var cachedMoodGenresAt: Long = 0L

    @Volatile private var cachedCharts: ChartSection? = null
    @Volatile private var cachedChartsAt: Long = 0L

    @Volatile private var cachedTrendingAlbums: List<Song>? = null
    @Volatile private var cachedTrendingAlbumsAt: Long = 0L

    @Volatile private var cachedTrendingArtists: List<Song>? = null
    @Volatile private var cachedTrendingArtistsAt: Long = 0L

    // ══════════════════════════════════════════
    // MOODS & GENRES
    // ══════════════════════════════════════════

    override suspend fun getMoodsAndGenres(forceRefresh: Boolean): Result<List<MoodGenre>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            // In-memory cache hit
            cachedMoodGenres?.takeIf { now - cachedMoodGenresAt < TTL_MOODS_GENRES }?.let {
                Log.d(TAG, "Mood/genre in-memory cache hit (${it.size} items)")
                return@withContext Result.success(it)
            }

            // Room cache hit
            browseSectionDao.getValidCache(KEY_MOODS_GENRES, now)?.let { entity ->
                val moods = deserializeMoodGenres(entity.jsonData)
                if (moods.isNotEmpty()) {
                    cachedMoodGenres = moods
                    cachedMoodGenresAt = entity.cachedAt
                    Log.d(TAG, "Mood/genre Room cache hit (${moods.size} items)")
                    return@withContext Result.success(moods)
                }
            }
        }

        // Network fetch
        try {
            val result = youTubeiService.browseMoodsAndGenres()
            result.onSuccess { categories ->
                val moods = categories.map { cat ->
                    MoodGenre(
                        title = cat.title,
                        params = cat.params,
                        color = cat.color
                    )
                }

                // Cache in-memory
                cachedMoodGenres = moods
                cachedMoodGenresAt = now

                // Cache in Room
                persistToRoom(KEY_MOODS_GENRES, serializeMoodGenres(moods), TTL_MOODS_GENRES)

                Log.d(TAG, "Mood/genre fetched from network (${moods.size} items)")
                return@withContext Result.success(moods)
            }

            result.onFailure { e ->
                Log.e(TAG, "Mood/genre network fetch failed", e)
                return@withContext Result.failure(e)
            }

            Result.success(emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Mood/genre error", e)
            Result.failure(e)
        }
    }

    // ══════════════════════════════════════════
    // CHARTS
    // ══════════════════════════════════════════

    override suspend fun getCharts(continuationToken: String?, forceRefresh: Boolean): Result<ChartSection> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // In-memory cache (only for initial fetch, not continuations, and not force-refresh)
            if (continuationToken.isNullOrBlank() && !forceRefresh) {
                cachedCharts?.takeIf { now - cachedChartsAt < TTL_CHARTS }?.let {
                    Log.d(TAG, "Charts in-memory cache hit (${it.songs.size} songs)")
                    return@withContext Result.success(it)
                }

                // Room cache
                browseSectionDao.getValidCache(KEY_CHARTS, now)?.let { entity ->
                    val chart = deserializeChartSection(entity.jsonData)
                    if (chart != null && chart.songs.isNotEmpty()) {
                        cachedCharts = chart
                        cachedChartsAt = entity.cachedAt
                        Log.d(TAG, "Charts Room cache hit (${chart.songs.size} songs)")
                        return@withContext Result.success(chart)
                    }
                }
            }

            // Network fetch
            try {
                val result = youTubeiService.browseCharts(
                    continuationToken = continuationToken
                )

                result.onSuccess { browseResult ->
                    val chart = ChartSection(
                        title = "Top Songs",
                        songs = browseResult.items,
                        continuationToken = browseResult.continuationToken
                    )

                    // Cache initial fetch
                    if (continuationToken.isNullOrBlank()) {
                        cachedCharts = chart
                        cachedChartsAt = now
                        persistToRoom(KEY_CHARTS, serializeChartSection(chart), TTL_CHARTS)
                    }

                    Log.d(TAG, "Charts fetched (${chart.songs.size} songs)")
                    return@withContext Result.success(chart)
                }

                Result.failure(result.exceptionOrNull() ?: Exception("Charts fetch failed"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Charts error", e)
                Result.failure(e)
            }
        }

    // ══════════════════════════════════════════
    // TRENDING ALBUMS
    // ══════════════════════════════════════════

    override suspend fun getTrendingAlbums(limit: Int, forceRefresh: Boolean): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            if (!forceRefresh) {
                // In-memory cache
                cachedTrendingAlbums?.takeIf { now - cachedTrendingAlbumsAt < TTL_TRENDING }?.let {
                    Log.d(TAG, "Trending albums in-memory cache hit (${it.size} items)")
                    return@withContext Result.success(it.take(limit))
                }

                // Room cache
                browseSectionDao.getValidCache(KEY_TRENDING_ALBUMS, now)?.let { entity ->
                    val albums = deserializeSongs(entity.jsonData)
                    if (albums.isNotEmpty()) {
                        cachedTrendingAlbums = albums
                        cachedTrendingAlbumsAt = entity.cachedAt
                        Log.d(TAG, "Trending albums Room cache hit (${albums.size} items)")
                        return@withContext Result.success(albums.take(limit))
                    }
                }
            }

            // Network fetch
            try {
                val result = youTubeiService.getTopAlbums(limit)
                result.onSuccess { albums ->
                    cachedTrendingAlbums = albums
                    cachedTrendingAlbumsAt = now
                    persistToRoom(KEY_TRENDING_ALBUMS, serializeSongs(albums), TTL_TRENDING)
                    Log.d(TAG, "Trending albums fetched (${albums.size} items)")
                }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Trending albums error", e)
                Result.failure(e)
            }
        }

    // ══════════════════════════════════════════
    // TRENDING ARTISTS
    // ══════════════════════════════════════════

    override suspend fun getTrendingArtists(limit: Int, forceRefresh: Boolean): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            if (!forceRefresh) {
                // In-memory cache
                cachedTrendingArtists?.takeIf { now - cachedTrendingArtistsAt < TTL_TRENDING }?.let {
                    Log.d(TAG, "Trending artists in-memory cache hit (${it.size} items)")
                    return@withContext Result.success(it.take(limit))
                }

                // Room cache
                browseSectionDao.getValidCache(KEY_TRENDING_ARTISTS, now)?.let { entity ->
                    val artists = deserializeSongs(entity.jsonData)
                    if (artists.isNotEmpty()) {
                        cachedTrendingArtists = artists
                        cachedTrendingArtistsAt = entity.cachedAt
                        Log.d(TAG, "Trending artists Room cache hit (${artists.size} items)")
                        return@withContext Result.success(artists.take(limit))
                    }
                }
            }

            // Network fetch
            try {
                val result = youTubeiService.getTopArtists(limit)
                result.onSuccess { artists ->
                    cachedTrendingArtists = artists
                    cachedTrendingArtistsAt = now
                    persistToRoom(KEY_TRENDING_ARTISTS, serializeSongs(artists), TTL_TRENDING)
                    Log.d(TAG, "Trending artists fetched (${artists.size} items)")
                }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Trending artists error", e)
                Result.failure(e)
            }
        }

    // ══════════════════════════════════════════
    override suspend fun getPlaylistSongs(browseId: String): Result<List<Song>> =
        youTubeiService.getPlaylistDetailsFromInnertube(browseId, audioTracksOnly = true).map { it.second }

    // ══════════════════════════════════════════
    // MOOD/GENRE SONGS
    // ══════════════════════════════════════════

    override suspend fun getMoodGenreSongs(params: String, limit: Int): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            try {
                youTubeiService.browseMoodGenrePlaylist(params, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Mood/genre songs error", e)
                Result.failure(e)
            }
        }

    // ══════════════════════════════════════════
    // FALLBACK LOGIC
    // ══════════════════════════════════════════

    private suspend fun persistToRoom(key: String, jsonData: String, ttlMs: Long) {
        val now = System.currentTimeMillis()
        try {
            browseSectionDao.insertOrReplace(
                BrowseSectionEntity(
                    sectionKey = key,
                    jsonData = jsonData,
                    cachedAt = now,
                    expiresAt = now + ttlMs
                )
            )
            // Opportunistic cleanup
            browseSectionDao.clearExpired(now)
        } catch (e: Exception) {
            Log.w(TAG, "Room cache write failed for $key", e)
        }
    }

    // ══════════════════════════════════════════
    // SERIALIZATION (simple JSON)
    // ══════════════════════════════════════════

    private fun serializeMoodGenres(moods: List<MoodGenre>): String {
        val array = JSONArray()
        moods.forEach { mood ->
            val obj = JSONObject().apply {
                put("title", mood.title)
                put("params", mood.params)
                mood.color?.let { put("color", it) }
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeMoodGenres(json: String): List<MoodGenre> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                MoodGenre(
                    title = obj.optString("title", ""),
                    params = obj.optString("params", ""),
                    color = if (obj.has("color")) obj.optLong("color") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeChartSection(chart: ChartSection): String {
        val obj = JSONObject().apply {
            put("title", chart.title)
            put("songs", serializeSongsArray(chart.songs))
            chart.continuationToken?.let { put("continuationToken", it) }
            chart.country?.let { put("country", it) }
        }
        return obj.toString()
    }

    private fun deserializeChartSection(json: String): ChartSection? {
        return try {
            val obj = JSONObject(json)
            ChartSection(
                title = obj.optString("title", "Top Songs"),
                songs = deserializeSongsArray(obj.optJSONArray("songs")),
                continuationToken = obj.optString("continuationToken").takeIf { it.isNotBlank() },
                country = obj.optString("country").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun serializeSongs(songs: List<Song>): String {
        return serializeSongsArray(songs).toString()
    }

    private fun deserializeSongs(json: String): List<Song> {
        return try {
            deserializeSongsArray(JSONArray(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeSongsArray(songs: List<Song>): JSONArray {
        val array = JSONArray()
        songs.forEach { song ->
            val obj = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("duration", song.duration)
                put("thumbnailUrl", song.thumbnailUrl)
                put("contentType", song.contentType.name)
                song.artistId?.let { put("artistId", it) }
                if (song.category.isNotBlank()) put("category", song.category)
                song.viewCount?.let { put("viewCount", it) }
                song.albumBrowseId?.let { put("albumBrowseId", it) }
                song.albumName?.let { put("albumName", it) }
            }
            array.put(obj)
        }
        return array
    }

    private fun deserializeSongsArray(array: JSONArray?): List<Song> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val albumBrowseId = obj.optString("albumBrowseId").takeIf { it.isNotBlank() }
            val albumName = obj.optString("albumName").takeIf { it.isNotBlank() }
            Song(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                artist = obj.optString("artist", ""),
                duration = obj.optInt("duration", 0),
                thumbnailUrl = obj.optString("thumbnailUrl", ""),
                contentType = try {
                    ContentType.valueOf(obj.optString("contentType", "SONG"))
                } catch (e: Exception) {
                    ContentType.SONG
                },
                artistId = obj.optString("artistId").takeIf { it.isNotBlank() },
                category = obj.optString("category", "Music"),
                viewCount = if (obj.has("viewCount")) obj.optLong("viewCount") else null,
                albumId = albumBrowseId,
                album = albumName,
                albumBrowseId = albumBrowseId,
                albumName = albumName
            )
        }
    }
}
