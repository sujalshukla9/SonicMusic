package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.source.Innertube
import com.sonicmusic.app.data.remote.source.RawHomeSong
import com.sonicmusic.app.data.remote.source.RawHomeSection
import com.sonicmusic.app.data.remote.source.HomeContentFilter
import com.sonicmusic.app.data.remote.source.HomeMapper
import com.sonicmusic.app.domain.model.HomeSong
import com.sonicmusic.app.domain.model.HomeSection
import com.sonicmusic.app.domain.repository.HomeRepository
import com.sonicmusic.app.domain.repository.QuickPicksRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for fetching and processing the YouTube Music home feed.
 * Implements a clean end-to-end pipeline returning only official songs.
 */
@Singleton
class InnertubeHomeRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsDataStore: SettingsDataStore,
    private val quickPicksRepository: QuickPicksRepository,
    private val userTasteRepository: UserTasteRepository,
    private val listenAgainRepository: com.sonicmusic.app.domain.repository.ListenAgainRepository,
    private val recommendationRepository: com.sonicmusic.app.domain.repository.RecommendationRepository
) : HomeRepository {
    companion object {
        private const val TAG = "InnertubeHomeRepo"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    }

    // Thread-safe in-memory cache mapped by "$region:$authenticated" cache keys
    private val feedCache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val sections: List<HomeSection>,
        val timestamp: Long
    )

    override suspend fun getHomeFeed(customCookies: String?): List<HomeSection> {
        return getHomeFeedInternal(customCookies, forceRefresh = false)
    }

    override suspend fun refreshQuickPicks(): HomeSection = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing Quick Picks from local QuickPicksRepository")
        val songs = quickPicksRepository.getQuickPicks(limit = 20, forceRefresh = true)
        val homeSongs = songs.map { song ->
            HomeSong(
                videoId = song.id,
                title = song.title,
                artists = listOf(song.artist),
                album = song.albumName ?: song.album,
                thumbnailUrl = song.thumbnailUrl,
                durationText = null
            )
        }
        HomeSection("Quick Picks", homeSongs)
    }

    override suspend fun refreshMadeForYou(): HomeSection = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing Made for You from local UserTasteRepository")
        val songsResult = userTasteRepository.getPersonalizedMix(limit = 20)
        val songs = songsResult.getOrDefault(emptyList())
        val homeSongs = songs.map { song ->
            HomeSong(
                videoId = song.id,
                title = song.title,
                artists = listOf(song.artist),
                album = song.albumName ?: song.album,
                thumbnailUrl = song.thumbnailUrl,
                durationText = null
            )
        }
        HomeSection("Made for You", homeSongs)
    }

    override suspend fun refreshListenAgain(): HomeSection = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing Listen Again from local ListenAgainRepository")
        val songs = try {
            listenAgainRepository.getListenAgainSongs(27)
        } catch (e: Exception) {
            emptyList()
        }
        val homeSongs = songs.map { song ->
            HomeSong(
                videoId = song.id,
                title = song.title,
                artists = listOf(song.artist),
                album = song.albumName ?: song.album,
                thumbnailUrl = song.thumbnailUrl,
                durationText = null
            )
        }
        HomeSection("Listen Again", homeSongs)
    }

    override suspend fun refreshForgottenFavorites(): HomeSection = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing Forgotten Favorites from local RecommendationRepository")
        val songs = try {
            recommendationRepository.getForgottenFavorites(20).getOrDefault(emptyList())
        } catch (e: Exception) {
            emptyList()
        }
        val homeSongs = songs.map { song ->
            HomeSong(
                videoId = song.id,
                title = song.title,
                artists = listOf(song.artist),
                album = song.albumName ?: song.album,
                thumbnailUrl = song.thumbnailUrl,
                durationText = null
            )
        }
        HomeSection("Forgotten Favorites", homeSongs)
    }

    /**
     * Executes the pipeline orchestrator:
     * Chains: Fetch raw JSON -> Parse shelves -> Filter song-only & Blacklists -> Drop empty shelves -> Map to HomeSection.
     *
     * @param customCookies Optional session cookies to fetch personalized shelf recommendations.
     * @param forceRefresh If true, clears the memory cache first.
     * @return List of clean home sections containing official song tracks.
     */
    suspend fun getHomeFeedInternal(customCookies: String? = null, forceRefresh: Boolean = false): List<HomeSection> = withContext(Dispatchers.IO) {
        val region = settingsDataStore.countryCode.first() ?: "US"
        val cacheKey = "$region:${customCookies != null}"

        if (forceRefresh) {
            feedCache.remove(cacheKey)
            Log.d(TAG, "Bypassed and cleared memory cache for key: $cacheKey")
        } else {
            // 1. CACHING LAYER: Return cached sections if within TTL
            val cached = feedCache[cacheKey]
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                Log.d(TAG, "♻️ Memory cache hit for key: $cacheKey")
                return@withContext cached.sections
            }
        }

        try {
            // 2. NETWORK LAYER: Fetch raw home JSON response
            val rawJson = fetchHomeJson(region, customCookies)
            if (rawJson.isNullOrBlank()) {
                Log.w(TAG, "Empty or null response fetched from home feed.")
                return@withContext emptyList()
            }

            // 3. PARSING LAYER: Parse JSON into raw shelves and handle nested items safely
            val rawSections = parseHomeJson(rawJson)

            // 4. SONG-ONLY FILTER LAYER: Exclude video/UGC types and filter empty shelves
            val cleanRawSections = HomeContentFilter.filterSections(rawSections)

            // 5. MAPPING LAYER: Map raw sections to finalized clean HomeSections
            val finalSections = cleanRawSections.map(HomeMapper::mapToHomeSection)

            // Cache the processed feed
            feedCache[cacheKey] = CacheEntry(finalSections, System.currentTimeMillis())
            Log.d(TAG, "✅ Clean home feed pipeline succeeded. Cached ${finalSections.size} sections.")

            finalSections
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to run clean home feed pipeline", e)
            emptyList()
        }
    }

    /**
     * POST requests to the FEmusic_home endpoint.
     */
    private fun fetchHomeJson(region: String, cookies: String?): String? {
        val language = "en"
        val requestBody = Innertube.buildYtmContext(language, region) {
            put("browseId", com.sonicmusic.app.data.remote.source.YtmBrowseId.Home.value)
        }
        val acceptLanguage = "$language-$region,$language;q=0.9,en;q=0.8"
        val request = Innertube.musicPost(
            endpoint = Innertube.BROWSE,
            body = requestBody,
            acceptLanguage = acceptLanguage,
            cookies = cookies
        )

        return try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.w(TAG, "HTTP error fetching FEmusic_home: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed network request for home browse ID", e)
            null
        }
    }

    /**
     * Parses the outer browse JSON structure into raw section shelves.
     */
    private fun parseHomeJson(jsonString: String): List<RawHomeSection> {
        val sectionsList = mutableListOf<RawHomeSection>()
        try {
            val json = JSONObject(jsonString)
            val sections = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?: return sectionsList

            for (i in 0 until sections.length()) {
                val sectionObj = sections.optJSONObject(i) ?: continue
                try {
                    // Parse individual section; if one section is corrupted, it is skipped without breaking others
                    parseSingleSection(sectionObj)?.let { sectionsList.add(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Skipped corrupted section at index $i", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing home JSON payload", e)
        }
        return sectionsList
    }

    private fun parseSingleSection(sectionObj: JSONObject): RawHomeSection? {
        // 1. Horizontal Carousel
        val carousel = sectionObj.optJSONObject("musicCarouselShelfRenderer")
        if (carousel != null) {
            val title = extractHeaderTitle(carousel.optJSONObject("header"))
            val contents = carousel.optJSONArray("contents") ?: return null
            val songs = mutableListOf<RawHomeSong>()
            for (j in 0 until contents.length()) {
                val item = contents.optJSONObject(j) ?: continue
                parseSingleItem(item)?.let { songs.add(it) }
            }
            return RawHomeSection(title, songs)
        }

        // 2. Vertical list shelf
        val shelf = sectionObj.optJSONObject("musicShelfRenderer")
        if (shelf != null) {
            val title = extractText(shelf.optJSONObject("title"))
            val contents = shelf.optJSONArray("contents") ?: return null
            val songs = mutableListOf<RawHomeSong>()
            for (j in 0 until contents.length()) {
                val item = contents.optJSONObject(j) ?: continue
                parseSingleItem(item)?.let { songs.add(it) }
            }
            return RawHomeSection(title, songs)
        }

        return null
    }

    private fun extractHeaderTitle(headerObj: JSONObject?): String {
        val basicHeader = headerObj?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        if (basicHeader != null) {
            return extractText(basicHeader.optJSONObject("title"))
        }
        return "Recommended"
    }

    private fun parseSingleItem(item: JSONObject): RawHomeSong? {
        val listItem = item.optJSONObject("musicResponsiveListItemRenderer")
        if (listItem != null) {
            return parseResponsiveItem(listItem)
        }
        val twoRow = item.optJSONObject("musicTwoRowItemRenderer")
        if (twoRow != null) {
            return parseTwoRowItem(twoRow)
        }
        return null
    }

    private fun parseResponsiveItem(listItem: JSONObject): RawHomeSong? {
        val videoId = listItem.optJSONObject("playlistItemData")?.optString("videoId")
            ?: extractVideoIdFromOverlay(listItem)
            ?: return null

        val flexColumns = listItem.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() == 0) return null

        // Column 0 contains the Title runs
        val title = extractFlexText(flexColumns.optJSONObject(0))
        if (title.isBlank()) return null

        var artists = listOf<String>()
        var albumName: String? = null
        var durationText: String? = null

        // Column 1 contains runs metadata (Artist, Album, Views, Separator, etc.)
        if (flexColumns.length() > 1) {
            val runs = flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")

            if (runs != null && runs.length() > 0) {
                val metadata = parseRunsMetadata(runs)
                artists = metadata.artists
                albumName = metadata.albumName
                durationText = metadata.durationText
            }
        }

        // Fixed column duration fallback if column 1 had no duration run
        val fixedColumns = listItem.optJSONArray("fixedColumns")
        if (fixedColumns != null && fixedColumns.length() > 0 && durationText == null) {
            durationText = fixedColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
        }

        val thumbnails = listItem.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbnailUrl = thumbnails?.optJSONObject(0)?.optString("url") ?: ""

        val videoType = extractVideoType(listItem)

        return RawHomeSong(
            videoId = videoId,
            title = title,
            artists = artists,
            album = albumName,
            thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
            durationText = durationText,
            videoType = videoType
        )
    }

    private fun parseTwoRowItem(twoRow: JSONObject): RawHomeSong? {
        val watchEndpoint = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        val videoId = watchEndpoint?.optString("videoId") ?: return null
        if (videoId.isEmpty()) return null

        val title = extractText(twoRow.optJSONObject("title"))
        if (title.isBlank()) return null

        val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
        var artists = listOf<String>()
        var albumName: String? = null
        var durationText: String? = null

        if (subtitleRuns != null && subtitleRuns.length() > 0) {
            val metadata = parseRunsMetadata(subtitleRuns)
            artists = metadata.artists
            albumName = metadata.albumName
            durationText = metadata.durationText
        }

        val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbnailUrl = thumbnails?.optJSONObject(0)?.optString("url") ?: ""

        val videoType = watchEndpoint
            .optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")
            ?.optString("musicVideoType")

        return RawHomeSong(
            videoId = videoId,
            title = title,
            artists = artists,
            album = albumName,
            thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
            durationText = durationText,
            videoType = videoType
        )
    }

    private data class RunsParsedResult(
        val artists: List<String>,
        val albumName: String?,
        val durationText: String?
    )

    private fun parseRunsMetadata(runs: JSONArray): RunsParsedResult {
        val artists = mutableListOf<String>()
        var albumName: String? = null
        var durationText: String? = null

        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val text = run.optString("text", "").trim()
            if (text.isEmpty() || text == "•" || text == "·" || text == "|") continue

            val browseEndpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val browseId = browseEndpoint?.optString("browseId", "")?.trim().orEmpty()
            val pageType = browseEndpoint
                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType", "")
                ?.lowercase()
                .orEmpty()

            val isArtist = pageType.contains("artist") || pageType.contains("user_channel") || browseId.startsWith("UC")
            val isAlbum = pageType.contains("album") || browseId.startsWith("MPRE") || browseId.startsWith("MPLA")
            val isDuration = text.contains(":") && text.replace(":", "").all { it.isDigit() }
            val isViews = text.lowercase(Locale.US).contains("view")

            when {
                isArtist -> {
                    artists.add(text)
                }
                isAlbum -> {
                    albumName = text
                }
                isDuration -> {
                    durationText = text
                }
                isViews -> {
                    // Ignore view count formatting runs
                }
                else -> {
                    if (artists.isEmpty() && !isDuration && !isViews) {
                        artists.add(text)
                    }
                }
            }
        }

        return RunsParsedResult(
            artists = artists.ifEmpty { listOf("Unknown Artist") },
            albumName = albumName,
            durationText = durationText
        )
    }

    private fun extractFlexText(column: JSONObject?): String {
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

    private fun extractVideoIdFromOverlay(listItem: JSONObject): String? {
        return listItem.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
    }

    private fun extractVideoType(listItem: JSONObject): String? {
        var watchEndpoint = listItem.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        if (watchEndpoint == null) {
            watchEndpoint = listItem.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
        }
        return watchEndpoint
            ?.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")
            ?.optString("musicVideoType")
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
}
