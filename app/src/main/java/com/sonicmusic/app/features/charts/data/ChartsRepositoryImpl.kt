package com.sonicmusic.app.features.charts.data

import android.util.Log
import com.sonicmusic.app.data.remote.source.Innertube
import com.sonicmusic.app.domain.model.ChartSong
import com.sonicmusic.app.domain.model.ChartArtist
import com.sonicmusic.app.domain.model.ChartAlbum
import com.sonicmusic.app.domain.repository.ChartsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChartsRepositoryImpl @Inject constructor(
    private val okHttpClient: OkHttpClient
) : ChartsRepository {

    companion object {
        private const val TAG = "ChartsRepoImpl"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour TTL
    }

    private data class CacheEntry<T>(
        val data: List<T>,
        val cachedAt: Long
    )

    private val songCache = ConcurrentHashMap<String, CacheEntry<ChartSong>>()
    private val artistCache = ConcurrentHashMap<String, CacheEntry<ChartArtist>>()
    private val albumCache = ConcurrentHashMap<String, CacheEntry<ChartAlbum>>()
    private val chartPlaylistIdCache = ConcurrentHashMap<String, CacheEntry<String>>()

    override suspend fun getTopSongs(region: String): List<ChartSong> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        songCache[region]?.takeIf { now - it.cachedAt < CACHE_TTL_MS }?.let {
            Log.d(TAG, "Top songs cache hit for region: $region")
            return@withContext it.data
        }

        try {
            val language = "en"
            val acceptLanguage = "$language-$region,$language;q=0.9,en;q=0.8"

            // Step 1: Discover the chart playlist browseId from FEmusic_charts
            val chartPlaylistBrowseId = getChartPlaylistBrowseId(region, language, acceptLanguage)
            if (chartPlaylistBrowseId == null) {
                Log.e(TAG, "No chart playlist found for region: $region")
                return@withContext emptyList()
            }
            Log.d(TAG, "Chart playlist browseId: $chartPlaylistBrowseId for region: $region")

            // Step 2: Browse the chart playlist to get individual songs
            val playlistBody = Innertube.webRemixBody(language, region) {
                put("browseId", chartPlaylistBrowseId)
            }
            val playlistRequest = Innertube.musicPost(
                endpoint = "/youtubei/v1/browse",
                body = playlistBody,
                acceptLanguage = acceptLanguage
            )

            val playlistResponse = okHttpClient.newCall(playlistRequest).execute()
            if (!playlistResponse.isSuccessful) {
                Log.e(TAG, "Failed to fetch chart playlist: HTTP ${playlistResponse.code}")
                return@withContext emptyList()
            }

            val playlistJson = playlistResponse.body?.string() ?: return@withContext emptyList()
            Log.d(TAG, "Playlist response length: ${playlistJson.length}")

            val songs = parseTopSongs(playlistJson)
            Log.d(TAG, "Parsed ${songs.size} songs from chart playlist")
            if (songs.isNotEmpty()) {
                songCache[region] = CacheEntry(songs, now)
            }
            songs
        } catch (e: Exception) {
            Log.e(TAG, "Error loading top songs", e)
            emptyList()
        }
    }

    override suspend fun getTopArtists(region: String): List<ChartArtist> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        artistCache[region]?.takeIf { now - it.cachedAt < CACHE_TTL_MS }?.let {
            Log.d(TAG, "Top artists cache hit for region: $region")
            return@withContext it.data
        }

        try {
            val language = "en"
            val requestBody = Innertube.buildYtmContext(language, region) {
                put("browseId", com.sonicmusic.app.data.remote.source.YtmBrowseId.Charts.value)
                put("params", Innertube.CHARTS_PARAMS_ARTISTS)
            }

            val acceptLanguage = "$language-$region,$language;q=0.9,en;q=0.8"
            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = acceptLanguage
            )

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch top artists: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val artists = parseTopArtists(responseBody)
            if (artists.isNotEmpty()) {
                artistCache[region] = CacheEntry(artists, now)
            }
            artists
        } catch (e: Exception) {
            Log.e(TAG, "Error loading top artists", e)
            emptyList()
        }
    }

    override suspend fun getTopAlbums(region: String): List<ChartAlbum> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        albumCache[region]?.takeIf { now - it.cachedAt < CACHE_TTL_MS }?.let {
            Log.d(TAG, "Top albums cache hit for region: $region")
            return@withContext it.data
        }

        try {
            val language = "en"
            val requestBody = Innertube.buildYtmContext(language, region) {
                put("browseId", com.sonicmusic.app.data.remote.source.YtmBrowseId.NewReleasesAlbums.value)
            }

            val acceptLanguage = "$language-$region,$language;q=0.9,en;q=0.8"
            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = acceptLanguage
            )

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch top albums: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val albums = parseTopAlbums(responseBody)
            if (albums.isNotEmpty()) {
                albumCache[region] = CacheEntry(albums, now)
            }
            albums
        } catch (e: Exception) {
            Log.e(TAG, "Error loading top albums", e)
            emptyList()
        }
    }

    // ══════════════════════════════════════════
    // CHART PLAYLIST DISCOVERY
    // ══════════════════════════════════════════

    /**
     * Fetches the FEmusic_charts page and extracts the first chart playlist browseId.
     * The charts page returns playlist cards (musicTwoRowItemRenderer) like "Top 100 India",
     * NOT individual songs. We need the playlist browseId to then browse for actual songs.
     */
    private fun getChartPlaylistBrowseId(
        region: String,
        language: String,
        acceptLanguage: String
    ): String? {
        val now = System.currentTimeMillis()
        chartPlaylistIdCache[region]?.takeIf { now - it.cachedAt < CACHE_TTL_MS }?.let {
            Log.d(TAG, "Chart playlist ID cache hit for region: $region")
            return it.data.firstOrNull()
        }

        val chartBody = Innertube.buildYtmContext(language, region) {
            put("browseId", com.sonicmusic.app.data.remote.source.YtmBrowseId.Charts.value)
            put("params", Innertube.CHARTS_PARAMS_SONGS)
        }
        val chartRequest = Innertube.musicPost(
            endpoint = Innertube.BROWSE,
            body = chartBody,
            acceptLanguage = acceptLanguage
        )

        val chartResponse = okHttpClient.newCall(chartRequest).execute()
        if (!chartResponse.isSuccessful) {
            Log.e(TAG, "Failed to fetch charts page: HTTP ${chartResponse.code}")
            return null
        }

        val chartJson = chartResponse.body?.string() ?: return null
        val browseId = parseChartPlaylistId(chartJson)

        if (browseId != null) {
            chartPlaylistIdCache[region] = CacheEntry(listOf(browseId), now)
        }

        return browseId
    }

    /**
     * Parses the FEmusic_charts response to find the first chart playlist browseId.
     * Looks for musicTwoRowItemRenderer items whose browseEndpoint points to a playlist
     * (browseId starts with "VL"). Prefers PL-style playlists (Top 100) over OLAK (curated).
     */
    private fun parseChartPlaylistId(jsonString: String): String? {
        try {
            val root = JSONObject(jsonString)
            val renderers = mutableListOf<JSONObject>()
            findRenderers(root, "musicTwoRowItemRenderer", renderers)
            Log.d(TAG, "parseChartPlaylistId: found ${renderers.size} musicTwoRowItemRenderer nodes")

            val playlistBrowseIds = mutableListOf<String>()
            for (rendererObj in renderers) {
                val renderer = rendererObj.optJSONObject("musicTwoRowItemRenderer") ?: continue
                val browseEndpoint = renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint") ?: continue
                val browseId = browseEndpoint.optString("browseId").orEmpty()
                val pageType = browseEndpoint
                    .optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType").orEmpty()

                if (browseId.startsWith("VL") && pageType.contains("PLAYLIST", ignoreCase = true)) {
                    playlistBrowseIds.add(browseId)
                }
            }

            Log.d(TAG, "Found ${playlistBrowseIds.size} chart playlist browseIds")
            if (playlistBrowseIds.isNotEmpty()) {
                playlistBrowseIds.take(3).forEachIndexed { i, id ->
                    Log.d(TAG, "  playlist[$i]: $id")
                }
            }

            // Prefer PL playlists (Top 100 charts) over OLAK (curated/trending playlists)
            return playlistBrowseIds.firstOrNull { it.startsWith("VLPL") }
                ?: playlistBrowseIds.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chart playlist ID", e)
            return null
        }
    }

    // ══════════════════════════════════════════
    // PARSING HELPERS
    // ══════════════════════════════════════════

    private fun parseTopSongs(jsonString: String): List<ChartSong> {
        val songs = mutableListOf<ChartSong>()
        try {
            val root = JSONObject(jsonString)
            val renderers = mutableListOf<JSONObject>()
            findRenderers(root, "musicResponsiveListItemRenderer", renderers)
            Log.d(TAG, "parseTopSongs: found ${renderers.size} musicResponsiveListItemRenderer nodes")

            if (renderers.isEmpty()) {
                // Log the top-level keys to help diagnose structure mismatch
                Log.w(TAG, "No renderers found! Top-level keys: ${root.keys().asSequence().toList()}")
                val contents = root.optJSONObject("contents")
                if (contents != null) {
                    Log.w(TAG, "contents keys: ${contents.keys().asSequence().toList()}")
                }
            }

            renderers.forEachIndexed { index, rendererObj ->
                try {
                    val renderer = rendererObj.optJSONObject("musicResponsiveListItemRenderer")
                    if (renderer == null) {
                        Log.w(TAG, "Song item $index: musicResponsiveListItemRenderer is null inside wrapper")
                        return@forEachIndexed
                    }
                    
                    val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
                        ?: renderer.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                            ?.optJSONObject("playNavigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId")
                            ?: run {
                                Log.w(TAG, "Song item $index: no videoId found (playlistItemData=${renderer.has(" playlistItemData")}, overlay=${renderer.has(" overlay")})")
                                return@forEachIndexed
                            }
                    
                    if (videoId.isBlank()) {
                        Log.w(TAG, "Song item $index: videoId is blank")
                        return@forEachIndexed
                    }

                    val flexColumns = renderer.optJSONArray("flexColumns")
                    if (flexColumns == null) {
                        Log.w(TAG, "Song item $index ($videoId): flexColumns is null")
                        return@forEachIndexed
                    }
                    val title = extractTextFromColumn(flexColumns.optJSONObject(0))
                    if (title.isBlank()) {
                        Log.w(TAG, "Song item $index ($videoId): title is blank")
                        return@forEachIndexed
                    }

                    val artists = mutableListOf<String>()
                    val artistRefsList = mutableListOf<com.sonicmusic.app.domain.model.ArtistRef>()
                    val artistIdsList = mutableListOf<String>()
                    if (flexColumns.length() > 1) {
                        val columnRuns = flexColumns.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (columnRuns != null) {
                            for (i in 0 until columnRuns.length()) {
                                val run = columnRuns.optJSONObject(i) ?: continue
                                val text = run.optString("text").trim()
                                if (text.isEmpty() || text == "•" || text == "·" || text == "|") continue
                                val browseEndpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                                val browseId = browseEndpoint?.optString("browseId").orEmpty()
                                val pageType = browseEndpoint
                                    ?.optJSONObject("browseEndpointContextSupportedConfigs")
                                    ?.optJSONObject("browseEndpointContextMusicConfig")
                                    ?.optString("pageType")
                                    .orEmpty()
                                if (pageType.contains("artist", ignoreCase = true) || browseId.startsWith("UC")) {
                                    artists.add(text)
                                    val id = browseId.takeIf { it.startsWith("UC") }
                                    if (id != null) artistIdsList.add(id)
                                    artistRefsList.add(com.sonicmusic.app.domain.model.ArtistRef(text, id))
                                }
                            }
                            if (artists.isEmpty()) {
                                val firstText = columnRuns.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
                                if (firstText.isNotEmpty()) {
                                    artists.add(firstText)
                                    artistRefsList.add(com.sonicmusic.app.domain.model.ArtistRef(firstText, null))
                                }
                            }
                        }
                    }
                    if (artists.isEmpty()) artists.add("Unknown Artist")

                    val thumbnails = renderer.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbnail = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
                        ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                    val upgradedThumbnail = upgradeThumbQuality(thumbnail, videoId)

                    val fixedColumns = renderer.optJSONArray("fixedColumns")
                    var durationText: String? = null
                    if (fixedColumns != null && fixedColumns.length() > 0) {
                        durationText = fixedColumns.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text")
                    }

                    val parsedRank = renderer.optJSONObject("customIndexColumn")
                        ?.optJSONObject("musicCustomIndexColumnRenderer")
                        ?.optJSONObject("index")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.toIntOrNull()

                    var albumName: String? = null
                    var albumBrowseId: String? = null
                    if (flexColumns.length() > 2) {
                        val albumColumnRuns = flexColumns.optJSONObject(2)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (albumColumnRuns != null && albumColumnRuns.length() > 0) {
                            val firstRun = albumColumnRuns.optJSONObject(0)
                            if (firstRun != null) {
                                val text = firstRun.optString("text", "").trim()
                                if (text.isNotEmpty()) {
                                    albumName = text
                                    val browseId = firstRun.optJSONObject("navigationEndpoint")
                                        ?.optJSONObject("browseEndpoint")
                                        ?.optString("browseId", "")?.trim()
                                    albumBrowseId = browseId?.takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }

                    songs.add(
                        ChartSong(
                            videoId = videoId,
                            title = title,
                            artists = artists,
                            thumbnailUrl = upgradedThumbnail,
                            durationText = durationText,
                            rank = parsedRank ?: (index + 1),
                            albumBrowseId = albumBrowseId,
                            albumName = albumName,
                            artistBrowseIds = artistIdsList,
                            artistRefs = artistRefsList
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing individual song item at index $index", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing top songs JSON", e)
        }
        Log.d(TAG, "parseTopSongs: parsed ${songs.size} songs (${songs.distinctBy { it.videoId }.size} unique)")
        return songs.distinctBy { it.videoId }
    }

    private fun parseTopArtists(jsonString: String): List<ChartArtist> {
        val artists = mutableListOf<ChartArtist>()
        try {
            val root = JSONObject(jsonString)
            val renderers = mutableListOf<JSONObject>()
            findRenderers(root, "musicResponsiveListItemRenderer", renderers)

            renderers.forEach { rendererObj ->
                try {
                    val renderer = rendererObj.optJSONObject("musicResponsiveListItemRenderer") ?: return@forEach
                    
                    val browseId = renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")
                        ?: return@forEach
                    
                    if (browseId.isBlank() || !browseId.startsWith("UC")) return@forEach

                    val flexColumns = renderer.optJSONArray("flexColumns") ?: return@forEach
                    val name = extractTextFromColumn(flexColumns.optJSONObject(0))
                    if (name.isBlank()) return@forEach

                    val thumbnails = renderer.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbnail = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
                    val upgradedThumbnail = if (thumbnail.contains("lh3.googleusercontent.com") || thumbnail.contains("yt3.ggpht.com")) {
                        thumbnail.replace(Regex("=w\\d+-h\\d+"), "=w540-h540")
                    } else thumbnail

                    var subscriberCountText: String? = null
                    if (flexColumns.length() > 1) {
                        subscriberCountText = extractTextFromColumn(flexColumns.optJSONObject(1))
                    }

                    artists.add(
                        ChartArtist(
                            channelId = browseId,
                            name = name,
                            thumbnailUrl = upgradedThumbnail,
                            subscriberCountText = subscriberCountText
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing individual artist item", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing top artists JSON", e)
        }
        return artists.distinctBy { it.channelId }
    }

    private fun parseTopAlbums(jsonString: String): List<ChartAlbum> {
        val albums = mutableListOf<ChartAlbum>()
        try {
            val root = JSONObject(jsonString)
            
            // Grid items are typically musicTwoRowItemRenderer
            val renderers = mutableListOf<JSONObject>()
            findRenderers(root, "musicTwoRowItemRenderer", renderers)
            Log.d(TAG, "parseTopAlbums: found ${renderers.size} musicTwoRowItemRenderer nodes")
            
            // Fallback to musicResponsiveListItemRenderer if no grid
            if (renderers.isEmpty()) {
                findRenderers(root, "musicResponsiveListItemRenderer", renderers)
                Log.d(TAG, "parseTopAlbums (fallback): found ${renderers.size} musicResponsiveListItemRenderer nodes")
            }

            if (renderers.isEmpty()) {
                Log.w(TAG, "No album renderers found! Top-level keys: ${root.keys().asSequence().toList()}")
                val contents = root.optJSONObject("contents")
                if (contents != null) {
                    Log.w(TAG, "contents keys: ${contents.keys().asSequence().toList()}")
                    val singleCol = contents.optJSONObject("singleColumnBrowseResultsRenderer")
                    if (singleCol != null) {
                        Log.w(TAG, "singleColumnBrowseResultsRenderer keys: ${singleCol.keys().asSequence().toList()}")
                    }
                }
            }

            renderers.forEach { rendererObj ->
                try {
                    val twoRow = rendererObj.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        val browseId = twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")
                            ?: return@forEach

                        if (!isAlbumBrowseId(browseId)) return@forEach

                        val title = extractText(twoRow.optJSONObject("title"))
                        if (title.isBlank()) return@forEach

                        val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                        val artist = if (subtitleRuns != null && subtitleRuns.length() > 0) {
                            subtitleRuns.optJSONObject(0)?.optString("text").orEmpty()
                        } else {
                            extractText(twoRow.optJSONObject("subtitle"))
                        }

                        val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val thumbnail = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
                        val upgradedThumbnail = if (thumbnail.contains("lh3.googleusercontent.com") || thumbnail.contains("yt3.ggpht.com")) {
                            thumbnail.replace(Regex("=w\\d+-h\\d+"), "=w540-h540")
                        } else thumbnail

                        var year: String? = null
                        if (subtitleRuns != null) {
                            for (i in 0 until subtitleRuns.length()) {
                                val runText = subtitleRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                                if (runText.matches(Regex("\\d{4}"))) {
                                    year = runText
                                    break
                                }
                            }
                        }

                        albums.add(
                            ChartAlbum(
                                browseId = browseId,
                                title = title,
                                artist = artist,
                                thumbnailUrl = upgradedThumbnail,
                                year = year
                            )
                        )
                    } else {
                        val renderer = rendererObj.optJSONObject("musicResponsiveListItemRenderer") ?: return@forEach
                        val browseId = renderer.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")
                            ?: return@forEach

                        if (!isAlbumBrowseId(browseId)) return@forEach

                        val flexColumns = renderer.optJSONArray("flexColumns") ?: return@forEach
                        val title = extractTextFromColumn(flexColumns.optJSONObject(0))
                        if (title.isBlank()) return@forEach

                        var artist = "Unknown Artist"
                        var year: String? = null
                        if (flexColumns.length() > 1) {
                            val columnRuns = flexColumns.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                            if (columnRuns != null) {
                                artist = columnRuns.optJSONObject(0)?.optString("text", "Unknown Artist").orEmpty()
                                for (i in 0 until columnRuns.length()) {
                                    val text = columnRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                                    if (text.matches(Regex("\\d{4}"))) {
                                        year = text
                                        break
                                    }
                                }
                            }
                        }

                        val thumbnails = renderer.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val thumbnail = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
                        val upgradedThumbnail = if (thumbnail.contains("lh3.googleusercontent.com") || thumbnail.contains("yt3.ggpht.com")) {
                            thumbnail.replace(Regex("=w\\d+-h\\d+"), "=w540-h540")
                        } else thumbnail

                        albums.add(
                            ChartAlbum(
                                browseId = browseId,
                                title = title,
                                artist = artist,
                                thumbnailUrl = upgradedThumbnail,
                                year = year
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing individual album item", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing top albums JSON", e)
        }
        Log.d(TAG, "parseTopAlbums: parsed ${albums.size} albums (${albums.distinctBy { it.browseId }.size} unique)")
        return albums.distinctBy { it.browseId }
    }

    private fun isAlbumBrowseId(browseId: String): Boolean {
        return browseId.startsWith("MPRE") || browseId.startsWith("MPLA")
    }

    private fun findRenderers(json: Any?, targetKey: String, outputList: MutableList<JSONObject>) {
        when (json) {
            is JSONObject -> {
                if (json.has(targetKey)) {
                    outputList.add(json)
                }
                for (key in json.keys()) {
                    findRenderers(json.get(key), targetKey, outputList)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    findRenderers(json.opt(i), targetKey, outputList)
                }
            }
        }
    }

    private fun extractTextFromColumn(column: JSONObject?): String {
        return column?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "") ?: ""
    }

    private fun extractText(textObj: JSONObject?): String {
        return textObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
            ?: textObj?.optString("text", "") ?: ""
    }

    private fun upgradeThumbQuality(url: String, videoId: String): String {
        if (url.isEmpty()) return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        if (url.contains("lh3.googleusercontent.com") || url.contains("yt3.ggpht.com")) {
            return url.replace(Regex("=w\\d+-h\\d+"), "=w540-h540")
        }
        if (url.contains("i.ytimg.com")) {
            return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        }
        return url
    }

    /**
     * Diagnostic function: fetches charts for a region, logs raw response
     * stats and parsed result counts. Call from a coroutine scope to debug
     * empty results — output goes to Logcat under tag "ChartsRepoImpl".
     *
     * @param region ISO 3166-1 alpha-2 country code (e.g. "US", "IN", "GB")
     * @return A human-readable debug summary string
     */
    suspend fun debugFetchCharts(region: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("═══ debugFetchCharts(region=$region) ═══")

        try {
            val language = "en"

            // ── 1. Songs request ──
            val songsBody = Innertube.buildYtmContext(language, region) {
                put("browseId", com.sonicmusic.app.data.remote.source.YtmBrowseId.Charts.value)
                put("params", Innertube.CHARTS_PARAMS_SONGS)
            }
            sb.appendLine("\n── Songs Request ──")
            sb.appendLine("browseId: ${com.sonicmusic.app.data.remote.source.YtmBrowseId.Charts.value}")
            sb.appendLine("params: ${Innertube.CHARTS_PARAMS_SONGS}")
            sb.appendLine("gl: $region")
            sb.appendLine("Request body (first 300 chars): ${songsBody.toString().take(300)}")

            val songsRequest = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = songsBody,
                acceptLanguage = "$language-$region,$language;q=0.9,en;q=0.8"
            )

            val songsResponse = okHttpClient.newCall(songsRequest).execute()
            sb.appendLine("HTTP status: ${songsResponse.code}")

            if (songsResponse.isSuccessful) {
                val songsJson = songsResponse.body?.string().orEmpty()
                sb.appendLine("Response length: ${songsJson.length} chars")
                sb.appendLine("First 500 chars: ${songsJson.take(500)}")

                val listItemCount = songsJson.split("musicResponsiveListItemRenderer").size - 1
                val twoRowCount = songsJson.split("musicTwoRowItemRenderer").size - 1
                val carouselCount = songsJson.split("musicCarouselShelfRenderer").size - 1
                val shelfCount = songsJson.split("musicShelfRenderer").size - 1
                sb.appendLine("Renderer counts: listItem=$listItemCount, twoRow=$twoRowCount, carousel=$carouselCount, shelf=$shelfCount")

                val parsed = parseTopSongs(songsJson)
                sb.appendLine("Parsed songs count: ${parsed.size}")
                parsed.take(3).forEachIndexed { i, s ->
                    sb.appendLine(" Song $i: '${s.title}' by ${s.artists} (${s.videoId})")
                }
            } else {
                sb.appendLine("ERROR: HTTP ${songsResponse.code} - ${songsResponse.message}")
            }

            // ── 2. Albums request ──
            val albumsBody = Innertube.buildYtmContext(language, region) {
                put("browseId", com.sonicmusic.app.data.remote.source.YtmBrowseId.NewReleasesAlbums.value)
            }
            sb.appendLine("\n── Albums Request ──")
            sb.appendLine("browseId: ${com.sonicmusic.app.data.remote.source.YtmBrowseId.NewReleasesAlbums.value}")

            val albumsRequest = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = albumsBody,
                acceptLanguage = "$language-$region,$language;q=0.9,en;q=0.8"
            )

            val albumsResponse = okHttpClient.newCall(albumsRequest).execute()
            sb.appendLine("HTTP status: ${albumsResponse.code}")

            if (albumsResponse.isSuccessful) {
                val albumsJson = albumsResponse.body?.string().orEmpty()
                sb.appendLine("Response length: ${albumsJson.length} chars")
                sb.appendLine("First 500 chars: ${albumsJson.take(500)}")

                val twoRowCount = albumsJson.split("musicTwoRowItemRenderer").size - 1
                val listItemCount = albumsJson.split("musicResponsiveListItemRenderer").size - 1
                sb.appendLine("Renderer counts: twoRow=$twoRowCount, listItem=$listItemCount")

                val parsed = parseTopAlbums(albumsJson)
                sb.appendLine("Parsed albums count: ${parsed.size}")
                parsed.take(3).forEachIndexed { i, a ->
                    sb.appendLine("  Album $i: '${a.title}' by ${a.artist} (${a.browseId})")
                }
            } else {
                sb.appendLine("ERROR: HTTP ${albumsResponse.code} - ${albumsResponse.message}")
            }

        } catch (e: Exception) {
            sb.appendLine("EXCEPTION: ${e.message}")
            Log.e(TAG, "debugFetchCharts failed", e)
        }

        val summary = sb.toString()
        // Log each line separately to avoid logcat truncation
        summary.lines().forEach { line ->
            Log.d(TAG, "$line")
        }
        summary
    }
}
