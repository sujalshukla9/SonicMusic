package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ShelfItem
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1. PARSING LAYER: Intermediate data structure containing raw Innertube details.
 */
data class RawShelfItem(
    val videoId: String,
    val title: String,
    val artistName: String,
    val artistId: String? = null,
    val albumName: String? = null,
    val albumId: String? = null,
    val videoType: String? = null, // e.g. "MUSIC_VIDEO_TYPE_ATV" (Audio track), " MUSIC_VIDEO_TYPE_OMV" (Official video)
    val isVerified: Boolean = false,
    val channelId: String? = null,
    val thumbnailUrl: String,
    val durationSeconds: Int = 0,
    val viewCount: Long? = null,
    val playlistId: String? = null,
    val isOfficialArtist: Boolean = false
)

/**
 * 2. FILTER LAYER: Sanitizes incoming items to strip UGC/fanmade uploads and title clickbaits using YtmScopeGuard.
 */
object InnertubeContentFilter {
    /**
     * Filters list of RawShelfItems using YtmScopeGuard 4-step enforcement pipeline.
     */
    fun filterShelfItems(items: List<RawShelfItem>): List<RawShelfItem> {
        return YtmScopeGuard.enforce(items)
    }
}

/**
 * 3. SCORING / RANKING LAYER: Priorities official audio releases above clips or general videos.
 */
object InnertubeContentScorer {
    
    /**
     * Priority-scores each item and sorts them descending (highest quality first).
     */
    fun scoreAndSort(items: List<RawShelfItem>): List<RawShelfItem> {
        return items.map { item ->
            var score = 0

            // ATV (Audio Track Video) is the premium audio format directly from labels, which should rank highest.
            if (item.videoType == "MUSIC_VIDEO_TYPE_ATV") {
                score += 100
            }
            
            // OMV (Official Music Video) is official, but less preferred for audio-only streams.
            if (item.videoType == "MUSIC_VIDEO_TYPE_OMV") {
                score += 80
            }

            // Artist channel verified badge or manually marked official channel
            if (item.isVerified || item.isOfficialArtist) {
                score += 20
            }

            // Topic channels are official distributor channels
            if (item.artistName.endsWith(" - Topic", ignoreCase = true)) {
                score += 15
            }

            // Boost titles containing "Official Audio", "Official Lyric Video" or "Single"
            val titleLower = item.title.lowercase()
            if (titleLower.contains("official audio") || titleLower.contains(" lyric video")) {
                score += 10
            }
            if (titleLower.contains("single") || titleLower.contains(" ep")) {
                score += 5
            }

            // Penalize live bootlegs or fan lyrics if they bypassed the filter
            if (titleLower.contains("live") && !titleLower.contains(" studio")) {
                score -= 30
            }
            if (titleLower.contains("lyrics") && !titleLower.contains(" official")) {
                score -= 20
            }

            item to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
    }
}

/**
 * 4. ORCHESTRATOR & CACHING LAYER: Fetches, parses, filters, scores, and maps shelf content.
 */
@Singleton
class InnertubePipelineOrchestrator @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "InnertubePipeline"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes cache TTL
    }

    // Thread-safe in-memory cache to prevent re-filtering during quick recompositions or scrolls
    private val shelfCache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val items: List<ShelfItem>,
        val timestamp: Long
    )

    /**
     * Executes the end-to-end cleaning pipeline for a given browseId/endpoint.
     *
     * @param browseId The Innertube browseId to request (e.g. FEmusic_new_releases_albums).
     * @param customRegion Optionally override the datastore's default region.
     * @param customCookies Optional cookies for authentication (useful for "Made for You" flow).
     */
    suspend fun getCleanShelf(
        browseId: String,
        customRegion: String? = null,
        customCookies: String? = null
    ): List<ShelfItem> = withContext(Dispatchers.IO) {
        val region = customRegion ?: settingsDataStore.countryCode.first() ?: "US"
        val cacheKey = "$browseId:$region:${customCookies != null}"

        // A. CACHING LAYER: Check if we have valid cached content
        val cached = shelfCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
            Log.d(TAG, "♻️ Cache hit for shelf key: $cacheKey")
            return@withContext cached.items
        }

        try {
            // B. NETWORK LAYER: Fetch raw shelf JSON
            val rawJson = fetchRawJson(browseId, region, customCookies)
            if (rawJson.isNullOrBlank()) {
                return@withContext emptyList()
            }

            // C. PARSING LAYER: Extract raw metadata items
            val rawItems = parseRawShelfJson(rawJson)

            // D. FILTER LAYER: Keep official ATV/OMV content and discard UGC/mashup blacklists
            val filteredItems = InnertubeContentFilter.filterShelfItems(rawItems)

            // E. SCORING LAYER: Sort items by official/topic tier priority
            val rankedItems = InnertubeContentScorer.scoreAndSort(filteredItems)

            // F. MAPPING LAYER: Map raw items to domain-compatible ShelfItem
            val cleanItems = rankedItems.map { raw ->
                ShelfItem(
                    id = raw.videoId,
                    title = raw.title,
                    artist = cleanArtistName(raw.artistName),
                    artistId = raw.artistId,
                    album = raw.albumName,
                    albumId = raw.albumId,
                    duration = raw.durationSeconds,
                    thumbnailUrl = upgradeThumbQuality(raw.thumbnailUrl, raw.videoId),
                    viewCount = raw.viewCount,
                    contentType = if (raw.albumId != null) ContentType.ALBUM else ContentType.SONG
                )
            }

            // Store in cache before returning
            shelfCache[cacheKey] = CacheEntry(cleanItems, System.currentTimeMillis())
            Log.d(TAG, "✅ Cache updated for $cacheKey (${cleanItems.size} items)")
            
            cleanItems
        } catch (e: Exception) {
            Log.e(TAG, "❌ Pipeline failed for browseId $browseId", e)
            emptyList()
        }
    }

    /**
     * Executes the POST request to Innertube BROWSE endpoint.
     */
    private fun fetchRawJson(browseId: String, region: String, cookies: String?): String? {
        val language = "en"
        val requestBody = Innertube.webRemixBody(language, region) {
            put("browseId", browseId)
            
            // Standard country charts require sub-params in FEmusic_charts
            if (browseId == "FEmusic_charts") {
                put("params", Innertube.CHARTS_PARAMS_SONGS)
            }
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
                Log.w(TAG, "HTTP ${response.code} fetching $browseId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network call failed for $browseId", e)
            null
        }
    }

    /**
     * Extracts items recursively from musicCarouselShelfRenderer, musicShelfRenderer, and gridRenderer.
     */
    private fun parseRawShelfJson(jsonString: String): List<RawShelfItem> {
        val items = mutableListOf<RawShelfItem>()
        try {
            val root = JSONObject(jsonString)
            
            // 1. Direct tab content check
            val contents = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val section = contents.optJSONObject(i) ?: continue
                    parseSectionItems(section, items)
                }
            }

            // 2. Tab section continuation check
            val continuationContents = root.optJSONObject("continuationContents")
                ?.optJSONObject("sectionListContinuation")
                ?.optJSONArray("contents")
            if (continuationContents != null) {
                for (i in 0 until continuationContents.length()) {
                    val section = continuationContents.optJSONObject(i) ?: continue
                    parseSectionItems(section, items)
                }
            }

            // 3. Music shelf continuation check
            val musicShelfContinuation = root.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
            if (musicShelfContinuation != null) {
                val listContents = musicShelfContinuation.optJSONArray("contents")
                if (listContents != null) {
                    for (i in 0 until listContents.length()) {
                        val itemObj = listContents.optJSONObject(i) ?: continue
                        parseSingleItem(itemObj)?.let { items.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON response", e)
        }
        return items
    }

    private fun parseSectionItems(section: JSONObject, items: MutableList<RawShelfItem>) {
        // Music Carousel Shelf (horizontal scrolling rows)
        val carousel = section.optJSONObject("musicCarouselShelfRenderer")
        if (carousel != null) {
            val contents = carousel.optJSONArray("contents") ?: return
            for (j in 0 until contents.length()) {
                val item = contents.optJSONObject(j) ?: continue
                parseSingleItem(item)?.let { items.add(it) }
            }
        }

        // Music Shelf (vertical scrolling lists)
        val shelf = section.optJSONObject("musicShelfRenderer")
        if (shelf != null) {
            val contents = shelf.optJSONArray("contents") ?: return
            for (j in 0 until contents.length()) {
                val item = contents.optJSONObject(j) ?: continue
                parseSingleItem(item)?.let { items.add(it) }
            }
        }

        // Grid contents (frequent in New Releases or Album grids)
        val grid = section.optJSONObject("gridRenderer")
        if (grid != null) {
            val gridItems = grid.optJSONArray("items") ?: return
            for (j in 0 until gridItems.length()) {
                val item = gridItems.optJSONObject(j) ?: continue
                parseSingleItem(item)?.let { items.add(it) }
            }
        }
    }

    private fun parseSingleItem(item: JSONObject): RawShelfItem? {
        try {
            val listItem = item.optJSONObject("musicResponsiveListItemRenderer")
            if (listItem != null) {
                return parseResponsiveListItem(listItem)
            }

            val twoRowItem = item.optJSONObject("musicTwoRowItemRenderer")
            if (twoRowItem != null) {
                return parseTwoRowItem(twoRowItem)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing item object", e)
        }
        return null
    }

    /**
     * Parses standard music list item layout.
     */
    private fun parseResponsiveListItem(listItem: JSONObject): RawShelfItem? {
        // Video/Audio ID
        val videoId = listItem.optJSONObject("playlistItemData")?.optString("videoId")
            ?: extractVideoIdFromOverlay(listItem)
            ?: return null

        val flexColumns = listItem.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() == 0) return null

        // Column 0: Title Text
        val title = extractFlexText(flexColumns.optJSONObject(0))
        if (title.isBlank()) return null

        // Column 1: Artist, Album, Duration, Views details
        var artistName = "Unknown Artist"
        var artistId: String? = null
        var albumName: String? = null
        var albumId: String? = null
        var isOfficialArtist = false
        var durationSeconds = 0
        var viewCount: Long? = null

        if (flexColumns.length() > 1) {
            val runs = flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")

            if (runs != null && runs.length() > 0) {
                val parsedRuns = parseRunsMetadata(runs)
                artistName = parsedRuns.artistName
                artistId = parsedRuns.artistId
                albumName = parsedRuns.albumName
                albumId = parsedRuns.albumId
                isOfficialArtist = parsedRuns.isOfficialArtist
                
                if (parsedRuns.durationSeconds > 0) {
                    durationSeconds = parsedRuns.durationSeconds
                }
                viewCount = parsedRuns.viewCount
            }
        }

        // Look for fixed columns duration fallback
        val fixedColumns = listItem.optJSONArray("fixedColumns")
        if (fixedColumns != null && fixedColumns.length() > 0 && durationSeconds == 0) {
            val fixedText = fixedColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
            if (fixedText != null) {
                durationSeconds = parseDuration(fixedText)
            }
        }

        // Verified Badges (Artist profiles have badge arrays)
        val isVerified = checkIfVerified(listItem)

        // Thumbnail extraction
        val thumbnails = listItem.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbnail = thumbnails?.optJSONObject(0)?.optString("url") ?: ""

        // Extract VideoType configuration
        val videoType = extractVideoType(listItem)

        return RawShelfItem(
            videoId = videoId,
            title = title,
            artistName = artistName,
            artistId = artistId,
            albumName = albumName,
            albumId = albumId,
            videoType = videoType,
            isVerified = isVerified,
            thumbnailUrl = thumbnail,
            durationSeconds = durationSeconds,
            viewCount = viewCount,
            isOfficialArtist = isOfficialArtist
        )
    }

    /**
     * Parses the 2-row grid items typically found on home shelves.
     */
    private fun parseTwoRowItem(twoRow: JSONObject): RawShelfItem? {
        val watchEndpoint = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        val videoId = watchEndpoint?.optString("videoId") ?: return null
        if (videoId.isEmpty()) return null

        val title = extractText(twoRow.optJSONObject("title"))
        if (title.isBlank()) return null

        val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
        var artistName = "Unknown Artist"
        var artistId: String? = null
        var isOfficialArtist = false
        var durationSeconds = 0
        var viewCount: Long? = null

        if (subtitleRuns != null && subtitleRuns.length() > 0) {
            val parsedRuns = parseRunsMetadata(subtitleRuns)
            artistName = parsedRuns.artistName
            artistId = parsedRuns.artistId
            isOfficialArtist = parsedRuns.isOfficialArtist
            durationSeconds = parsedRuns.durationSeconds
            viewCount = parsedRuns.viewCount
        }

        val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbnail = thumbnails?.optJSONObject(0)?.optString("url") ?: ""

        val videoType = watchEndpoint
            ?.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")
            ?.optString("musicVideoType")

        return RawShelfItem(
            videoId = videoId,
            title = title,
            artistName = artistName,
            artistId = artistId,
            videoType = videoType,
            thumbnailUrl = thumbnail,
            durationSeconds = durationSeconds,
            viewCount = viewCount,
            isOfficialArtist = isOfficialArtist
        )
    }

    private data class RunsParsedResult(
        val artistName: String,
        val artistId: String?,
        val albumName: String?,
        val albumId: String?,
        val isOfficialArtist: Boolean,
        val durationSeconds: Int,
        val viewCount: Long?
    )

    /**
     * Parses the lists of text runs to extract fields like artist, album, duration.
     */
    private fun parseRunsMetadata(runs: JSONArray): RunsParsedResult {
        var artistName = ""
        var artistId: String? = null
        var albumName: String? = null
        var albumId: String? = null
        var isOfficialArtist = false
        var durationSeconds = 0
        var viewCount: Long? = null

        val artists = mutableListOf<String>()
        val artistIds = mutableListOf<String>()

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

            val isArtist = pageType.contains("artist") || pageType.contains(" user_channel") || browseId.startsWith(" UC")
            val isAlbum = pageType.contains("album") || browseId.startsWith(" MPRE") || browseId.startsWith(" MPLA")

            // Identify views or duration text by shape
            val isDuration = text.contains(":") && text.replace(":", "").all { it.isDigit() }
            val isViews = text.lowercase().contains("view")

            when {
                isArtist -> {
                    artists.add(text)
                    if (browseId.isNotEmpty()) artistIds.add(browseId)
                    isOfficialArtist = true
                }
                isAlbum -> {
                    albumName = text
                    if (browseId.isNotEmpty()) albumId = browseId
                }
                isDuration -> {
                    durationSeconds = parseDuration(text)
                }
                isViews -> {
                    viewCount = parseViewCount(text)
                }
                else -> {
                    // Fallback to first non-metadata text as artist
                    if (artists.isEmpty() && !isDuration && !isViews) {
                        artists.add(text)
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            artistName = artists.joinToString(", ")
        }
        if (artistIds.isNotEmpty()) {
            artistId = artistIds.joinToString(",")
        }

        return RunsParsedResult(
            artistName = artistName.ifEmpty { "Unknown Artist" },
            artistId = artistId,
            albumName = albumName,
            albumId = albumId,
            isOfficialArtist = isOfficialArtist,
            durationSeconds = durationSeconds,
            viewCount = viewCount
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

    private fun checkIfVerified(listItem: JSONObject): Boolean {
        val badges = listItem.optJSONArray("badges")
        if (badges != null) {
            for (i in 0 until badges.length()) {
                val badge = badges.optJSONObject(i)
                val inline = badge?.optJSONObject("musicInlineBadgeRenderer")
                val iconType = inline?.optJSONObject("icon")?.optString("iconType") ?: ""
                if (iconType == "MUSIC_VERIFIED" || iconType.contains(" VERIFIED")) {
                    return true
                }
            }
        }
        return false
    }

    private fun parseDuration(text: String): Int {
        val parts = text.split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toIntOrNull() ?: 0
                2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun parseViewCount(text: String): Long? {
        val cleaned = text.lowercase(Locale.US).trim()
        if (!cleaned.contains("view")) return null
        val numeric = cleaned.replace(Regex("[^0-9a-z.]"), "")
        val multiplier = when {
            numeric.endsWith("b") -> 1_000_000_000.0
            numeric.endsWith("m") -> 1_000_000.0
            numeric.endsWith("k") -> 1_000.0
            else -> 1.0
        }
        val digits = numeric.replace(Regex("[a-z]"), "")
        return try {
            (digits.toDouble() * multiplier).toLong()
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanArtistName(name: String): String {
        return name.replace(Regex("(?i)\\b(vevo|official|topic)\\b"), "").trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun upgradeThumbQuality(url: String, videoId: String): String {
        if (url.isEmpty()) return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        if (url.contains("lh3.googleusercontent.com") || url.contains(" yt3.ggpht.com")) {
            return url.replace(Regex("=w\\d+-h\\d+"), "=w540-h540")
        }
        if (url.contains("i.ytimg.com")) {
            return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        }
        return url
    }
}
