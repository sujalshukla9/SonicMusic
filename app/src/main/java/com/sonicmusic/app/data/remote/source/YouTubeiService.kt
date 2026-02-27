package com.sonicmusic.app.data.remote.source

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTubei API Service - ViTune Style Implementation
 * 
 * Uses music.youtube.com endpoints for music-only results
 * Implements proper music detection with multiple filtering layers:
 * 1. Music metadata detection (musicVideoType, musicMetadataRenderer)
 * 2. Duration filtering (60s - 600s)
 * 3. Channel filtering (Topic, VEVO, Official Artist)
 * 4. Keyword filtering (exclude vlogs, podcasts, etc.)
 * 
 * Default region: United States (US)
 */
@Singleton
class YouTubeiService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) {

    private data class ParsedSongSearchPage(
        val songs: List<Song>,
        val continuationToken: String?
    )

    data class SongSearchPage(
        val songs: List<Song>,
        val continuationToken: String?
    )

    data class ArtistProfileInfo(
        val name: String,
        val browseId: String? = null,
        val imageUrl: String? = null,
        val bannerUrl: String? = null,
        val subscribersText: String? = null,
        val description: String? = null,
        val shufflePlaylistId: String? = null,
        val radioPlaylistId: String? = null,
        val topSongs: List<Song> = emptyList(),
        val albums: List<Song> = emptyList(),
        val singles: List<Song> = emptyList(),
        val videos: List<Song> = emptyList(),
        val featuredOn: List<Song> = emptyList(),
        val relatedArtists: List<Song> = emptyList(),
        val sections: List<ArtistSectionInfo> = emptyList(),
        val topSongsBrowseId: String? = null,
        val songsMoreEndpoint: String? = null,
        val albumsMoreEndpoint: String? = null,
        val singlesMoreEndpoint: String? = null
    )

    enum class ArtistSectionType {
        TOP_SONGS,
        ALBUMS,
        SINGLES,
        VIDEOS,
        FEATURED_ON,
        RELATED_ARTISTS,
        UNKNOWN
    }

    data class ArtistSectionInfo(
        val type: ArtistSectionType = ArtistSectionType.UNKNOWN,
        val title: String = "",
        val browseId: String? = null,
        val moreEndpoint: String? = null,
        val items: List<Song> = emptyList()
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val TAG = "YouTubeiService"
        private const val REGION_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val YTM_SEARCH_DEFAULT_TARGET_RESULTS = 500
        private const val RECOMMENDATION_STAGE_TIMEOUT_MS = 2_500L
        
        // Default region if detection is unavailable
        const val DEFAULT_REGION = "US"
        
        // Duration limits for songs (in seconds)
        private const val MIN_DURATION = 30      // 30 seconds minimum
        private const val MAX_DURATION = 1800     // 30 minutes maximum (allow extended mixes)
        
        // Non-music keywords to filter out
        private val EXCLUDE_KEYWORDS = listOf(
            "vlog", "review", "reaction", "episode", "full movie",
            "podcast", "interview", "tutorial", "gameplay", "trailer",
            "documentary", "news", "unboxing", "haul", "challenge",
            "behind the scenes", "how to"
        )
        
        // Music channel indicators
        private val MUSIC_CHANNEL_PATTERNS = listOf(
            " - Topic",
            " - topic",
            "VEVO",
            "Official",
            "Music",
            "Records",
            "Entertainment"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // REGION DETECTION - Auto-detect user's country for localized content
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Detect user's region from SIM card or system locale
     * Priority: SIM country > Network country > System locale
     */
    private fun detectUserRegion(): String {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            
            // Try SIM country first (most accurate)
            val simCountry = telephonyManager?.simCountryIso?.uppercase()
            if (!simCountry.isNullOrEmpty() && simCountry.length == 2) {
                Log.d(TAG, "🌍 Region from SIM: $simCountry")
                return simCountry
            }
            
            // Try network country
            val networkCountry = telephonyManager?.networkCountryIso?.uppercase()
            if (!networkCountry.isNullOrEmpty() && networkCountry.length == 2) {
                Log.d(TAG, "🌍 Region from network: $networkCountry")
                return networkCountry
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting SIM region", e)
        }
        
        // Fallback to system locale
        val localeCountry = Locale.getDefault().country.uppercase()
        if (localeCountry.isNotEmpty()) {
            Log.d(TAG, "🌍 Region from locale: $localeCountry")
            return localeCountry
        }
        
        Log.d(TAG, "🌍 Using default region: $DEFAULT_REGION")
        return DEFAULT_REGION
    }
    
    /**
     * Get language code for the detected region
     */
    private fun getLanguageForRegion(region: String): String {
        // Always use English for metadata/text labels so artist names,
        // descriptions, and section titles appear in English regardless
        // of the user's region. The `gl` param still controls which
        // regional content (charts, trending) is returned.
        return "en"
    }

    private fun normalizeCountryCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.US)
        if (normalized.isNullOrEmpty() || normalized.length != 2) return null
        if (!normalized.all { it.isLetter() }) return null
        return if (normalized == "UK") "GB" else normalized
    }

    private val detectedRegion: String by lazy { detectUserRegion() }
    @Volatile
    private var cachedRequestRegion: String? = null
    @Volatile
    private var cachedRegionLoadedAtMs: Long = 0L

    private suspend fun getRequestRegion(): String {
        val now = System.currentTimeMillis()
        val stored = normalizeCountryCode(settingsDataStore.countryCode.first())

        // Always prefer the latest persisted country code (IP-detected region).
        // This avoids serving stale fallback regions when home loads before region init finishes.
        if (stored != null) {
            cachedRequestRegion = stored
            cachedRegionLoadedAtMs = now
            return stored
        }

        val cached = cachedRequestRegion
        if (cached != null && now - cachedRegionLoadedAtMs < REGION_CACHE_TTL_MS) {
            return cached
        }

        val resolved = normalizeCountryCode(detectedRegion) ?: DEFAULT_REGION
        cachedRequestRegion = resolved
        cachedRegionLoadedAtMs = now
        return resolved
    }

    private fun getAcceptLanguageHeader(language: String, region: String): String {
        return "$language-$region,$language;q=0.9,en;q=0.8"
    }

    private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    // ═══════════════════════════════════════════════════════════════
    // MIXED SEARCH - Uses YouTube Music API (all types)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Search for mixed content (Songs, Videos, Albums, Artists, Playlists)
     */
    suspend fun searchMixed(query: String, limit: Int = 20): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            val requestBody = Innertube.webRemixBody(language, region) {
                put("query", query)
                // No params = mixed results
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.SEARCH,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("YouTube Music failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            
            // Parse mixed results and preserve content types for browse flows.
            val items = sanitizeMixedResults(
                songs = parseYouTubeMusicMixedResponse(responseBody),
                limit = limit,
                offset = 0
            )
            Result.success(items)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Mixed search error", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN SEARCH - Uses YouTube Music API (music-only)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Search for songs using YouTube Music API
     * Returns music-only results with proper filtering
     */
    suspend fun searchSongs(query: String, limit: Int = 50, offset: Int = 0): Result<List<Song>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Searching for: $query")
        
        // Try YouTube Music API first (music-only results)
        var result = searchYouTubeMusic(query, limit, offset)
        
        if (result.isSuccess && result.getOrNull()?.isNotEmpty() == true) {
            Log.d(TAG, "✅ YouTube Music returned ${result.getOrNull()?.size} songs")
            return@withContext result
        }
        
        Log.d(TAG, "⚠️ YouTube Music failed, trying regular YouTube with filters")
        
        // Fallback to regular YouTube with strict music filtering
        result = searchYouTubeWithMusicFilter(query, limit, offset)
        
        result
    }

    /**
     * Search a single page using continuation-token pagination.
     *
     * - First page: pass null continuationToken
     * - Next page: pass continuation token from previous response
     */
    suspend fun searchSongsPage(
        query: String,
        continuationToken: String? = null,
        limit: Int = 20
    ): Result<SongSearchPage> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return@withContext Result.success(
                SongSearchPage(
                    songs = emptyList(),
                    continuationToken = null
                )
            )
        }

        val safeLimit = limit.coerceAtLeast(1)
        val safeContinuation = continuationToken?.trim()?.takeIf { it.isNotEmpty() }

        val primaryResult = searchYouTubeMusicPage(
            query = normalizedQuery,
            continuationToken = safeContinuation,
            limit = safeLimit
        )

        if (primaryResult.isSuccess) {
            return@withContext primaryResult
        }

        // Fallback is only safe for the first page. Continuation requests
        // must stay on the same backend and token stream.
        if (!safeContinuation.isNullOrBlank()) {
            return@withContext primaryResult
        }

        val fallbackResult = searchYouTubeWithMusicFilter(
            query = normalizedQuery,
            limit = safeLimit,
            offset = 0
        ).map { songs ->
            SongSearchPage(
                songs = songs.take(safeLimit),
                continuationToken = null
            )
        }

        if (fallbackResult.isSuccess) {
            Log.d(TAG, "✅ Fallback page search returned ${fallbackResult.getOrNull()?.songs?.size ?: 0} songs")
            return@withContext fallbackResult
        }

        primaryResult
    }

    private suspend fun searchYouTubeMusicPage(
        query: String,
        continuationToken: String?,
        limit: Int
    ): Result<SongSearchPage> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val safeLimit = limit.coerceAtLeast(1)

            val requestBody = buildSongSearchRequestBody(
                query = query,
                continuationToken = continuationToken,
                language = language,
                region = region
            )

            val request = Innertube.musicPost(
                endpoint = Innertube.SEARCH,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ YouTube Music paged search failed: ${response.code}")
                return@withContext Result.failure(Exception("YouTube Music failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                return@withContext Result.success(
                    SongSearchPage(
                        songs = emptyList(),
                        continuationToken = null
                    )
                )
            }

            val page = parseYouTubeMusicSongPage(responseBody)
            val songs = sanitizeSongResults(
                songs = page.songs,
                limit = 0,
                offset = 0
            ).take(safeLimit)

            Result.success(
                SongSearchPage(
                    songs = songs,
                    continuationToken = page.continuationToken?.takeIf { it.isNotBlank() }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ YouTube Music paged search error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Search using YouTube Music API (music.youtube.com)
     * Returns music-only results
     */
    private suspend fun searchYouTubeMusic(query: String, limit: Int, offset: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val safeOffset = offset.coerceAtLeast(0)
            val safeLimit = limit.coerceAtLeast(0)
            val targetResults = if (safeLimit > 0) {
                safeOffset + safeLimit
            } else {
                (safeOffset + YTM_SEARCH_DEFAULT_TARGET_RESULTS)
                    .coerceAtLeast(YTM_SEARCH_DEFAULT_TARGET_RESULTS)
            }

            val rawSongs = mutableListOf<Song>()
            val seenContinuationTokens = mutableSetOf<String>()
            var continuationToken: String? = null
            var pageIndex = 0
            var keepFetching = true

            Log.d(
                TAG,
                "📡 Calling YouTube Music API (offset=$safeOffset, limit=$safeLimit)"
            )

            while (keepFetching) {
                val requestBody = buildSongSearchRequestBody(
                    query = query,
                    continuationToken = continuationToken,
                    language = language,
                    region = region
                )

                val request = Innertube.musicPost(
                    endpoint = Innertube.SEARCH,
                    body = requestBody,
                    acceptLanguage = getAcceptLanguageHeader(language, region)
                )

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ YouTube Music failed on page ${pageIndex + 1}: ${response.code}")
                    return@withContext Result.failure(Exception("YouTube Music failed: ${response.code}"))
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    break
                }

                val page = parseYouTubeMusicSongPage(responseBody)
                if (page.songs.isNotEmpty()) {
                    rawSongs += page.songs
                }

                val cleanedCount = sanitizeSongResults(
                    songs = rawSongs,
                    limit = 0,
                    offset = 0
                ).size

                continuationToken = page.continuationToken
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { token ->
                        val isNewToken = seenContinuationTokens.add(token)
                        if (!isNewToken) {
                            Log.w(
                                TAG,
                                "⚠️ Repeated continuation token detected for query=\"$query\"; stopping pagination"
                            )
                        }
                        isNewToken
                    }
                pageIndex += 1
                keepFetching = !continuationToken.isNullOrBlank() && cleanedCount < targetResults
            }

            val songs = sanitizeSongResults(
                songs = rawSongs,
                limit = safeLimit,
                offset = safeOffset
            )

            Log.d(TAG, "🎶 Parsed ${songs.size} music tracks (raw=${rawSongs.size}, pages=$pageIndex)")
            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ YouTube Music error", e)
            Result.failure(e)
        }
    }

    private fun buildSongSearchRequestBody(
        query: String,
        continuationToken: String?,
        language: String,
        region: String
    ): JSONObject {
        return Innertube.webRemixBody(language, region) {
            if (continuationToken.isNullOrBlank()) {
                put("query", query)
                put("params", Innertube.SearchFilter.Song.value)
            } else {
                put("continuation", continuationToken)
            }
        }
    }
    
    /**
     * Search regular YouTube with strict music filtering
     */
    private suspend fun searchYouTubeWithMusicFilter(query: String, limit: Int, offset: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            // Add "song" or "music" to query for better results
            val musicQuery = if (!query.lowercase().contains("song") && !query.lowercase().contains("music")) {
                "$query song"
            } else {
                query
            }
            
            val requestBody = Innertube.androidBody(language, region) {
                put("query", musicQuery)
            }
            
            val request = Innertube.youtubeAndroidPost(
                endpoint = Innertube.SEARCH,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region),
                includeClientHeaders = true
            )
            
            Log.d(TAG, "📡 Calling YouTube API with music filter...")

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ YouTube failed: ${response.code}")
                return@withContext Result.failure(Exception("YouTube failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty response"))
            }
            
            // Parse and apply strict music filtering
            val songs = sanitizeSongResults(
                songs = parseYouTubeResponse(responseBody),
                limit = limit,
                offset = offset
            )
            
            Log.d(TAG, "🎶 Filtered to ${songs.size} music tracks")

            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ YouTube error", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MUSIC DETECTION LOGIC (ViTune Style)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Check if content is music using multiple methods:
     * 1. Music metadata presence
     * 2. Duration check
     * 3. Channel type check
     * 4. Keyword filtering
     */
    private fun isMusicContent(song: Song): Boolean {
        // Check duration (1 min - 10 min)
        if (song.duration > 0 && !isValidDuration(song.duration)) {
            Log.d(TAG, "⏱️ Filtered by duration: ${song.title} (${song.duration}s)")
            return false
        }
        
        // Check for excluded keywords
        if (containsExcludedKeywords(song.title)) {
            Log.d(TAG, "🚫 Filtered by keyword: ${song.title}")
            return false
        }
        
        // Check if it's a music channel (Topic, VEVO, etc.)
        if (isMusicChannel(song.artist)) {
            return true
        }
        
        // Default: accept (YouTube Music API already filters)
        return true
    }

    private fun sanitizeSongResults(
        songs: List<Song>,
        limit: Int,
        offset: Int = 0
    ): List<Song> {
        // ViTune style: trust the YouTube Music API results.
        // Only normalize content type, upgrade thumbnails, and deduplicate.
        val cleaned = songs.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .map { song ->
                song.copy(
                    contentType = ContentType.SONG,
                    thumbnailUrl = upgradeThumbQuality(song.thumbnailUrl, song.id)
                )
            }
            .filter { !containsExcludedKeywords(it.title) }
            .distinctBy { it.id }
            .toList()

        val safeOffset = offset.coerceAtLeast(0)
        val sliced = if (safeOffset < cleaned.size) cleaned.drop(safeOffset) else emptyList()
        return if (limit > 0) sliced.take(limit) else sliced
    }

    private fun sanitizeMixedResults(
        songs: List<Song>,
        limit: Int,
        offset: Int = 0
    ): List<Song> {
        val cleaned = songs.asSequence()
            .mapNotNull { song ->
                when (song.contentType) {
                    ContentType.SONG,
                    ContentType.UNKNOWN -> {
                        val normalizedSong = song.copy(
                            contentType = ContentType.SONG,
                            thumbnailUrl = upgradeThumbQuality(song.thumbnailUrl, song.id)
                        )
                        if (isMusicContent(normalizedSong)) normalizedSong else null
                    }
                    ContentType.VIDEO -> {
                        val normalizedVideo = song.copy(
                            contentType = ContentType.VIDEO,
                            thumbnailUrl = upgradeThumbQuality(song.thumbnailUrl, song.id)
                        )
                        if (normalizedVideo.duration == 0 || isValidDuration(normalizedVideo.duration)) {
                            normalizedVideo
                        } else {
                            null
                        }
                    }
                    ContentType.ARTIST,
                    ContentType.ALBUM,
                    ContentType.PLAYLIST -> {
                        if (song.id.isBlank() || song.title.isBlank()) {
                            null
                        } else {
                            song.copy(
                                thumbnailUrl = ThumbnailUrlUtils.toHighQuality(song.thumbnailUrl)
                                    ?: song.thumbnailUrl
                            )
                        }
                    }
                    else -> null
                }
            }
            .distinctBy { it.id }
            .toList()

        val safeOffset = offset.coerceAtLeast(0)
        val sliced = if (safeOffset < cleaned.size) cleaned.drop(safeOffset) else emptyList()
        return if (limit > 0) sliced.take(limit) else sliced
    }
    
    /**
     * Check if duration is within song range
     */
    private fun isValidDuration(duration: Int): Boolean {
        if (duration == 0) return true // Unknown duration, accept
        return duration in MIN_DURATION..MAX_DURATION
    }
    
    /**
     * Check if title contains excluded keywords
     */
    private fun containsExcludedKeywords(title: String): Boolean {
        val titleLower = title.lowercase()
        return EXCLUDE_KEYWORDS.any { titleLower.contains(it) }
    }
    
    /**
     * Check if channel is a music channel
     */
    private fun isMusicChannel(channelName: String): Boolean {
        return MUSIC_CHANNEL_PATTERNS.any { channelName.contains(it, ignoreCase = true) }
    }

    // ═══════════════════════════════════════════════════════════════
    // YOUTUBE MUSIC RESPONSE PARSER
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Parse YouTube Music API response
     * Structure: tabbedSearchResultsRenderer → musicShelfRenderer
     */
    private fun parseYouTubeMusicSongPage(jsonString: String): ParsedSongSearchPage {
        val songs = mutableListOf<Song>()
        var continuationToken: String? = null

        try {
            val json = JSONObject(jsonString)

            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")

            if (tabs != null) {
                for (t in 0 until tabs.length()) {
                    val tabContent = tabs.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?: continue

                    for (i in 0 until tabContent.length()) {
                        val section = tabContent.optJSONObject(i) ?: continue
                        val musicShelf = section.optJSONObject("musicShelfRenderer") ?: continue

                        appendMusicShelfSongs(
                            contents = musicShelf.optJSONArray("contents"),
                            songs = songs
                        )
                        if (continuationToken.isNullOrBlank()) {
                            continuationToken = extractMusicShelfContinuationToken(musicShelf)
                        }
                    }
                }
            }

            val shelfContinuation = json.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
            if (shelfContinuation != null) {
                appendMusicShelfSongs(
                    contents = shelfContinuation.optJSONArray("contents"),
                    songs = songs
                )
                if (continuationToken.isNullOrBlank()) {
                    continuationToken = extractMusicShelfContinuationToken(shelfContinuation)
                }
            }

            val responseCommands = json.optJSONArray("onResponseReceivedCommands")
            if (responseCommands != null) {
                for (i in 0 until responseCommands.length()) {
                    val command = responseCommands.optJSONObject(i) ?: continue
                    val continuationItems = command
                        .optJSONObject("appendContinuationItemsAction")
                        ?.optJSONArray("continuationItems")
                        ?: continue

                    val commandToken = appendContinuationItems(
                        continuationItems = continuationItems,
                        songs = songs
                    )
                    if (continuationToken.isNullOrBlank()) {
                        continuationToken = commandToken
                    }
                }
            }

            if (songs.isEmpty() && continuationToken.isNullOrBlank()) {
                Log.d(TAG, "⚠️ No YTM tabs found, trying alternative structure")
                songs += parseYouTubeResponse(jsonString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Parse YouTube Music error", e)
        }

        Log.d(
            TAG,
            "📊 YouTube Music parsed page: ${songs.size} songs, continuation=${!continuationToken.isNullOrBlank()}"
        )
        return ParsedSongSearchPage(
            songs = songs,
            continuationToken = continuationToken?.takeIf { it.isNotBlank() }
        )
    }

    private fun appendMusicShelfSongs(contents: JSONArray?, songs: MutableList<Song>) {
        if (contents == null) return

        for (j in 0 until contents.length()) {
            val item = contents.optJSONObject(j) ?: continue
            item.optJSONObject("musicResponsiveListItemRenderer")
                ?.let(::parseMusicResponsiveItem)
                ?.let(songs::add)
        }
    }

    private fun appendContinuationItems(
        continuationItems: JSONArray,
        songs: MutableList<Song>
    ): String? {
        var continuationToken: String? = null

        for (j in 0 until continuationItems.length()) {
            val item = continuationItems.optJSONObject(j) ?: continue
            item.optJSONObject("musicResponsiveListItemRenderer")
                ?.let(::parseMusicResponsiveItem)
                ?.let(songs::add)

            if (continuationToken.isNullOrBlank()) {
                continuationToken = item.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotBlank() }
            }
        }

        return continuationToken
    }

    private fun extractMusicShelfContinuationToken(shelf: JSONObject): String? {
        val continuations = shelf.optJSONArray("continuations")
        val next = continuations
            ?.optJSONObject(0)
            ?.optJSONObject("nextContinuationData")
            ?.optString("continuation")
            ?.takeIf { it.isNotBlank() }
        if (next != null) return next

        return continuations
            ?.optJSONObject(0)
            ?.optJSONObject("reloadContinuationData")
            ?.optString("continuation")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Parse YouTube Music Mixed Response (Top Results)
     */
    private fun parseYouTubeMusicMixedResponse(jsonString: String): List<Song> {
        val items = mutableListOf<Song>()
        try {
            val json = JSONObject(jsonString)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return items

            for (t in 0 until tabs.length()) {
                val tabContent = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (i in 0 until tabContent.length()) {
                    val section = tabContent.optJSONObject(i)
                    val musicShelf = section?.optJSONObject("musicShelfRenderer")
                    
                    if (musicShelf != null) {
                        // Regular shelf (Songs, Videos, etc)
                        val contents = musicShelf.optJSONArray("contents") ?: continue
                        for (j in 0 until contents.length()) {
                            val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                            if (item != null) {
                                parseMixedItem(item)?.let { items.add(it) }
                            }
                        }
                    } else {
                        // Top Result (often a CardShelf)
                        val cardShelf = section?.optJSONObject("musicCardShelfRenderer")
                        if (cardShelf != null) {
                            // Header is the item
                             parseCardShelf(cardShelf)?.let { items.add(it) }
                             
                             // Contents inside (usually songs by that artist)
                             val contents = cardShelf.optJSONArray("contents")
                             if (contents != null) {
                                 for (j in 0 until contents.length()) {
                                    val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                                    if (item != null) {
                                        parseMixedItem(item)?.let { items.add(it) }
                                    }
                                 }
                             }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing mixed response", e)
        }
        return items
    }

    private fun parseCardShelf(cardShelf: JSONObject): Song? {
         try {
            val titleText = extractText(cardShelf.optJSONObject("header")?.optJSONObject("musicCardShelfHeaderBasicRenderer")?.optJSONObject("title"))
            val subtitleText = extractText(cardShelf.optJSONObject("header")?.optJSONObject("musicCardShelfHeaderBasicRenderer")?.optJSONObject("subtitle"))
            
            // Get Thumbnail
            val thumbnails = cardShelf.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnailUrl = ThumbnailUrlUtils.toHighQuality(
                extractHighestQualityThumbnail(
                    thumbnails = thumbnails,
                    fallback = ""
                )
            ) ?: ""
            
            // Determine Type
            val contentType = when {
                subtitleText.contains("Artist", true) -> ContentType.ARTIST
                subtitleText.contains("Album", true) -> ContentType.ALBUM
                subtitleText.contains("Playlist", true) -> ContentType.PLAYLIST
                subtitleText.contains("Video", true) -> ContentType.VIDEO
                else -> ContentType.SONG // Fallback
            }
            
            // Artists don't have videoId usually here, they have browseId. 
            // We use browseId as ID for Artist/Album/Playlist
            val navigationEndpoint = cardShelf.optJSONObject("header")
                ?.optJSONObject("musicCardShelfHeaderBasicRenderer")
                ?.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
            
            val id = navigationEndpoint?.optJSONObject("browseEndpoint")?.optString("browseId")
                 ?: navigationEndpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
                 ?: ""
                 
            if (id.isEmpty()) return null

            val cardSubtitleRuns = cardShelf.optJSONObject("header")
                ?.optJSONObject("musicCardShelfHeaderBasicRenderer")
                ?.optJSONObject("subtitle")
                ?.optJSONArray("runs")
            val cardArtist = if (cardSubtitleRuns != null) {
                val refs = extractArtistAndAlbumRefs(cardSubtitleRuns)
                refs.artists.firstOrNull()?.name
                    ?: extractFirstNonSeparatorRun(cardSubtitleRuns)
            } else {
                subtitleText
            }

            return Song(
                id = id,
                title = titleText,
                artist = cleanArtistName(cardArtist),
                duration = 0,
                thumbnailUrl = thumbnailUrl,
                category = "Mixed",
                contentType = contentType
            )
         } catch (e: Exception) {
             return null
         }
    }

    private fun parseMixedItem(item: JSONObject): Song? {
        try {
            val flexColumns = item.optJSONArray("flexColumns")
            if (flexColumns == null || flexColumns.length() == 0) return null

            val title = extractMusicText(flexColumns.optJSONObject(0))
            
            // Second column often contains: Type • Artist • Album • Duration/Views
            val subtitleRuns = flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                
            var subtitle = ""
            var artist = ""
            var typeText = ""
            var id = ""
            
            if (subtitleRuns != null) {
                val fullSubtitle = StringBuilder()
                val artistNames = mutableListOf<String>()
                
                for (k in 0 until subtitleRuns.length()) {
                    val run = subtitleRuns.optJSONObject(k) ?: continue
                    val runText = run.optString("text", "")
                    fullSubtitle.append(runText)
                    
                    // Runs linking to artist channels have browseEndpoint with browseId starting with "UC"
                    val browseId = run.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId", "")
                    if (!browseId.isNullOrBlank() && browseId.startsWith("UC")) {
                        val name = runText.trim()
                        if (name.isNotBlank() && name != "·" && name != "•" && name != ",") {
                            artistNames.add(name)
                        }
                    }
                }
                subtitle = fullSubtitle.toString()
                
                // Extract type text (first run, before first separator)
                typeText = subtitleRuns.optJSONObject(0)?.optString("text", "") ?: ""
                
                if (artistNames.isNotEmpty()) {
                    // Use only the FIRST artist name (ViTune style)
                    // Multiple artist names joined with ", " pollutes display
                    artist = artistNames.first()
                } else {
                    // Fallback: parse subtitle by splitting on ' · '
                    // Format is typically: "Type · Artist · Album · Year"
                    val parts = subtitle.split(Regex("\\s*[·•]\\s*")).map { it.trim() }.filter { it.isNotBlank() }
                    artist = when {
                        parts.size >= 3 -> parts[1] // Second part is typically artist
                        parts.size == 2 -> parts[1] // "Type · Artist"
                        parts.size == 1 -> parts[0]
                        else -> subtitle
                    }
                }
            }
            
            // Get ID
            val navigationEndpoint = item.optJSONObject("navigationEndpoint") 
                ?: item.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    
           id = navigationEndpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
                ?: navigationEndpoint?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?: ""
                
            if (id.isEmpty()) return null

            // Get Thumbnail
            val thumbnails = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnailUrl = ThumbnailUrlUtils.toHighQuality(
                extractHighestQualityThumbnail(
                    thumbnails = thumbnails,
                    fallback = ""
                )
            ) ?: ""

            // Determine Type using typeText (first subtitle run) for reliable detection
            var contentType = ContentType.SONG
            val typeTextLower = typeText.trim().lowercase()
            if (typeTextLower == "video" || typeTextLower.contains("video")) contentType = ContentType.VIDEO
            else if (typeTextLower == "artist" || id.startsWith("UC")) contentType = ContentType.ARTIST
            else if (typeTextLower == "album" || id.startsWith("MPRE")) contentType = ContentType.ALBUM
            else if (typeTextLower == "playlist" || id.startsWith("VL")) contentType = ContentType.PLAYLIST
            
            // Explicit override for known browse ID prefixes
            if (id.startsWith("UC")) contentType = ContentType.ARTIST
            
            return Song(
                id = id,
                title = title,
                artist = cleanArtistName(artist),
                duration = 0,
                thumbnailUrl = thumbnailUrl,
                category = "Mixed",
                contentType = contentType
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Parse YouTube Music Mixed Response (Top Results)
     */

    
    /**
     * Parse musicResponsiveListItemRenderer (YouTube Music song item)
     */
    private fun parseMusicResponsiveItem(item: JSONObject): Song? {
        try {
            // Get video ID
            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId", "")
                ?: item.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "")
                ?: ""
            
            if (videoId.isEmpty()) return null
            
            // Get flex columns (title, artist, album, duration)
            val flexColumns = item.optJSONArray("flexColumns")
            
            var title = ""
            var artist = ""
            var durationText = "0:00"
            
            if (flexColumns != null) {
                // First column: Title
                if (flexColumns.length() > 0) {
                    title = extractMusicText(flexColumns.optJSONObject(0))
                }
                
                // Second column: Artist + other info
                if (flexColumns.length() > 1) {
                    val artistColumn = flexColumns.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    
                    if (artistColumn != null && artistColumn.length() > 0) {
                        // Use extractArtistAndAlbumRefs for proper artist detection (ViTune-style)
                        val refs = extractArtistAndAlbumRefs(artistColumn)
                        artist = refs.artists
                            .firstOrNull()?.name  // ViTune: take only the FIRST artist run
                            ?: refs.artists.joinToString(", ") { it.name }
                                .ifBlank {
                                    // Fallback: use first non-separator run (not ALL runs concatenated)
                                    extractFirstNonSeparatorRun(artistColumn)
                                }
                    }
                }
            }
            
            // Get duration from fixed columns
            val fixedColumns = item.optJSONArray("fixedColumns")
            if (fixedColumns != null && fixedColumns.length() > 0) {
                durationText = fixedColumns.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text", "0:00") ?: "0:00"
            }
            
            // Get thumbnail
            val thumbnails = item.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            
            val thumbnailUrl = extractHighestQualityThumbnail(
                thumbnails = thumbnails,
                fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            )
            
            if (title.isEmpty()) return null

            // Detect content type from title and metadata
            val contentType = detectContentType(title, artist, durationText)

            // Skip non-music content (videos, podcasts, live streams)
            if (contentType != ContentType.SONG && contentType != ContentType.UNKNOWN) {
                Log.d(TAG, "🎬 Skipping non-music content: $title ($contentType)")
                return null
            }

            return Song(
                id = videoId,
                title = title,
                artist = cleanArtistName(artist),
                duration = parseDuration(durationText),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                category = "Music",
                contentType = contentType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing music item", e)
            return null
        }
    }

    /**
     * Detect content type from title, artist, and duration
     * ViTune-style detection to filter only music songs
     */
    private fun detectContentType(title: String, artist: String, durationText: String): ContentType {
        val titleLower = title.lowercase()
        val artistLower = artist.lowercase()
        val durationSeconds = parseDuration(durationText)

        // Check for explicit content type indicators in title
        return when {
            // Live streams
            titleLower.contains("live") && (titleLower.contains("now") || titleLower.contains("streaming")) -> ContentType.LIVE_STREAM
            titleLower.contains("🔴") || titleLower.contains("livestream") -> ContentType.LIVE_STREAM

            // Podcasts
            titleLower.contains("podcast") || titleLower.contains("episode") -> ContentType.PODCAST
            artistLower.contains("podcast") -> ContentType.PODCAST

            // Shorts
            titleLower.contains("#shorts") || titleLower.contains("shorts") -> ContentType.SHORT
            durationSeconds in 1..59 -> ContentType.SHORT  // Less than 1 minute = short

            // Full albums
            titleLower.contains("full album") || titleLower.contains("complete album") -> ContentType.ALBUM
            durationSeconds > 1800 -> ContentType.ALBUM  // More than 30 minutes = album

            // Music videos (visual content)
            titleLower.contains("official video") || titleLower.contains("music video") -> ContentType.VIDEO
            titleLower.contains("(video)") || titleLower.contains("[video]") -> ContentType.VIDEO
            titleLower.contains("vevo") && (titleLower.contains("video") || titleLower.contains("official")) -> ContentType.VIDEO

            // Regular songs (1-10 minutes, no video indicators)
            durationSeconds in 60..600 -> ContentType.SONG

            // Unknown - will be filtered later by duration
            else -> ContentType.UNKNOWN
        }
    }
    
    private fun extractMusicText(column: JSONObject?): String {
        return column?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "") ?: ""
    }

    // ═══════════════════════════════════════════════════════════════
    // REGULAR YOUTUBE RESPONSE PARSER (Fallback)
    // ═══════════════════════════════════════════════════════════════
    
    private fun parseYouTubeResponse(jsonString: String): List<Song> {
        val songs = mutableListOf<Song>()
        
        try {
            val json = JSONObject(jsonString)
            val contents = findContentsArray(json) ?: return songs
            
            for (i in 0 until contents.length()) {
                val section = contents.optJSONObject(i) ?: continue
                val items = findVideoItems(section)
                
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val song = parseVideoRenderer(item)
                    if (song != null) {
                        songs.add(song)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse YouTube error", e)
        }
        
        return songs
    }
    
    private fun findContentsArray(json: JSONObject): JSONArray? {
        // Try different structures
        json.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")?.let { return it }
        
        json.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")?.let { return it }
        
        return null
    }
    
    private fun findVideoItems(section: JSONObject): JSONArray {
        section.optJSONObject("itemSectionRenderer")
            ?.optJSONArray("contents")?.let { return it }
        return JSONArray()
    }
    
    private fun parseVideoRenderer(item: JSONObject): Song? {
        try {
            val renderer = item.optJSONObject("videoRenderer")
                ?: item.optJSONObject("compactVideoRenderer")
                ?: return null

            val videoId = renderer.optString("videoId", "")
            if (videoId.isEmpty()) return null

            val title = extractText(renderer.optJSONObject("title"))
            if (title.isEmpty()) return null

            val artist = extractArtistFromTextObject(renderer.optJSONObject("ownerText"))
                .ifEmpty { extractArtistFromTextObject(renderer.optJSONObject("shortBylineText")) }

            val durationText = renderer.optJSONObject("lengthText")?.optString("simpleText", "0:00") ?: "0:00"

            // Detect content type and filter non-music
            val contentType = detectContentType(title, artist, durationText)
            if (contentType != ContentType.SONG && contentType != ContentType.UNKNOWN) {
                Log.d(TAG, "🎬 Filtering non-music from regular YouTube: $title ($contentType)")
                return null
            }

            val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnailUrl = extractHighestQualityThumbnail(
                thumbnails = thumbnails,
                fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            )

            return Song(
                id = videoId,
                title = title,
                artist = cleanArtistName(artist),
                duration = parseDuration(durationText),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                category = "Music",
                contentType = contentType
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun extractText(textObject: JSONObject?): String {
        if (textObject == null) return ""
        textObject.optString("simpleText", "").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = textObject.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            return buildString {
                for (i in 0 until runs.length()) {
                    append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }
        }
        return ""
    }

    /**
     * Extract ONLY the artist name from a text JSON object (ViTune-style).
     *
     * Instead of joining all runs (which produces "KR$NA • No Cap"),
     * this extracts only artist-type runs identified by:
     * 1. browseEndpoint.pageType containing "ARTIST" or "USER_CHANNEL"
     * 2. browseId starting with "UC" (YouTube channel prefix)
     * 3. Fallback: first non-separator run only
     */
    private fun extractArtistFromTextObject(textObject: JSONObject?): String {
        if (textObject == null) return ""
        textObject.optString("simpleText", "").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = textObject.optJSONArray("runs")
        if (runs == null || runs.length() == 0) return ""

        val artistInfo = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(runs.toRunList())
        if (artistInfo.confidence > 0.0f) {
            return artistInfo.displayName
        }

        // Fallback: use first non-separator run (ViTune approach)
        return extractFirstNonSeparatorRun(runs)
    }

    /**
     * Extract the first non-separator run text from a JSONArray of runs.
     * Skips separators like " • ", " · ", " | ", ", " etc.
     */
    private fun extractFirstNonSeparatorRun(runs: JSONArray): String {
        for (i in 0 until runs.length()) {
            val text = runs.optJSONObject(i)?.optString("text", "")?.trim() ?: ""
            if (text.isNotBlank() && text != "•" && text != "·" && text != "|" && text != "," && text != " ") {
                return text
            }
        }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // OTHER PUBLIC METHODS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Result of a dedicated artist-filtered search.
     */
    private data class ArtistFilterResult(
        val name: String,
        val browseId: String?,
        val thumbnailUrl: String? = null,
        val subscribersText: String? = null
    )

    /**
     * Search for an artist using YTM's dedicated artist search filter.
     *
     * Uses the `params=EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU%3D` filter which returns
     * only artist results — each with a UC* browseId and subscriber count.
     * This is how YouTube Music's own artist resolution works.
     *
     * Returns the best-matching artist, or null if none found.
     */
    private suspend fun searchArtistByFilter(
        query: String,
        language: String,
        region: String
    ): ArtistFilterResult? {
        try {
            val searchBody = Innertube.webRemixBody(language, region) {
                put("query", query)
                put("params", Innertube.SearchFilter.Artist.value)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.SEARCH,
                body = searchBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val raw = response.body?.string() ?: return null
            val json = JSONObject(raw)

            // Parse artist results from the filtered search response.
            // Structure: contents → tabbedSearchResultsRenderer → tabs[0]
            //   → tabRenderer → content → sectionListRenderer → contents[]
            //   → musicShelfRenderer → contents[]
            //   → each item is musicResponsiveListItemRenderer
            val candidates = mutableListOf<ArtistFilterResult>()

            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")

            if (tabs != null) {
                for (t in 0 until tabs.length()) {
                    val sectionContents = tabs.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents") ?: continue

                    for (s in 0 until sectionContents.length()) {
                        val shelfContents = sectionContents.optJSONObject(s)
                            ?.optJSONObject("musicShelfRenderer")
                            ?.optJSONArray("contents") ?: continue

                        for (i in 0 until shelfContents.length()) {
                            val item = shelfContents.optJSONObject(i) ?: continue
                            parseArtistFilterItem(item)?.let(candidates::add)
                        }
                    }
                }
            }

            if (candidates.isEmpty()) return null

            // Select best match by name similarity
            val normalizedQuery = query.lowercase().replace(Regex("\\s+"), " ").trim()
            return candidates.maxByOrNull { candidate ->
                var score = 0
                val name = candidate.name.lowercase().replace(Regex("\\s+"), " ").trim()
                if (name == normalizedQuery) score += 15
                if (name.contains(normalizedQuery) || normalizedQuery.contains(name)) score += 8
                if (!candidate.browseId.isNullOrBlank() && candidate.browseId.startsWith("UC")) score += 5
                if (!candidate.subscribersText.isNullOrBlank()) score += 3
                if (!candidate.thumbnailUrl.isNullOrBlank()) score += 2
                score
            }
        } catch (e: Exception) {
            Log.w(TAG, "Artist filter search failed, will fallback to mixed search", e)
            return null
        }
    }

    /**
     * Parse a single artist result from the artist-filtered search response.
     * These are musicResponsiveListItemRenderer items in the musicShelfRenderer.
     */
    private fun parseArtistFilterItem(item: JSONObject): ArtistFilterResult? {
        val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: return null

        // Extract browseId from navigation endpoint
        val browseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Must be a UC* channel ID for a real artist
        if (!browseId.startsWith("UC")) return null

        // Extract name from first flex column
        val flexColumns = renderer.optJSONArray("flexColumns")
        val name = extractMusicText(flexColumns?.optJSONObject(0)).trim()
        if (name.isBlank()) return null

        // Extract subscriber count from second flex column subtitle runs
        var subscribersText: String? = null
        val subtitleRuns = flexColumns?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        if (subtitleRuns != null) {
            val combined = buildString {
                for (i in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }.trim()
            if (combined.isNotBlank() && looksLikeAudienceStats(combined)) {
                subscribersText = formatArtistStats(combined)
            }
            // Check individual runs
            if (subscribersText == null) {
                for (i in 0 until subtitleRuns.length()) {
                    val runText = subtitleRuns.optJSONObject(i)?.optString("text", "")?.trim() ?: ""
                    if (runText.isNotBlank() && looksLikeAudienceStats(runText)) {
                        subscribersText = formatArtistStats(runText)
                        break
                    }
                }
            }
        }

        // Extract thumbnail
        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbnailUrl = extractHighestQualityThumbnail(thumbnails, "")
            .takeIf { it.isNotBlank() }
            ?.let { normalizeArtistHeaderImage(it) }

        return ArtistFilterResult(
            name = name,
            browseId = browseId,
            thumbnailUrl = thumbnailUrl,
            subscribersText = subscribersText
        )
    }

    suspend fun getArtistProfileFromInnertube(
        artistName: String,
        browseIdHint: String? = null
    ): Result<ArtistProfileInfo> = withContext(Dispatchers.IO) {
        val normalizedName = artistName.trim()
        var browseId = normalizeArtistBrowseId(browseIdHint.orEmpty())
        if (normalizedName.isEmpty() && browseId.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Artist name and browseId are both empty")
            )
        }

        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            var resolvedName = normalizedName
            var finalImage: String? = null
            var finalBannerUrl: String? = null
            var finalSubscribers: String? = null
            var finalDescription: String? = null
            var finalShufflePlaylistId: String? = null
            var finalRadioPlaylistId: String? = null
            var fallbackAlbums: List<Song> = emptyList()

            if (browseId.isNullOrBlank()) {
                // ── Step 1: Dedicated artist-filtered search (YTM-style) ──
                // Uses the Artist search filter to get only artist results with
                // proper UC* browseIds, exactly how YouTube Music resolves artists.
                val artistFilterResult = searchArtistByFilter(
                    query = normalizedName,
                    language = language,
                    region = region
                )
                if (artistFilterResult != null) {
                    resolvedName = artistFilterResult.name.takeIf { it.isNotBlank() } ?: normalizedName
                    browseId = artistFilterResult.browseId
                    finalImage = artistFilterResult.thumbnailUrl
                    finalSubscribers = artistFilterResult.subscribersText
                    Log.d(TAG, "🎯 Artist found via filter search: $resolvedName (browseId=$browseId)")
                }

                // ── Step 2: Fallback to mixed search if filter found nothing ──
                if (browseId.isNullOrBlank()) {
                    Log.d(TAG, "🔍 Artist filter search missed, falling back to mixed search")
                    val searchBody = Innertube.webRemixBody(language, region) {
                        put("query", normalizedName)
                    }

                    val searchRequest = Innertube.musicPost(
                        endpoint = Innertube.SEARCH,
                        body = searchBody,
                        acceptLanguage = getAcceptLanguageHeader(language, region)
                    )

                    val searchResponse = client.newCall(searchRequest).execute()
                    if (!searchResponse.isSuccessful) {
                        return@withContext Result.failure(InvalidHttpCodeException(searchResponse.code))
                    }

                    val searchBodyRaw = searchResponse.body?.string()
                        ?: return@withContext Result.failure(
                            Exception("Empty Innertube artist search response")
                        )
                    val searchJson = JSONObject(searchBodyRaw)
                    val mixedCandidates = parseYouTubeMusicMixedResponse(searchBodyRaw)
                    val strictArtistCandidates = mixedCandidates.filter { it.contentType == ContentType.ARTIST }
                    val searchArtist = selectBestArtistCandidate(normalizedName, strictArtistCandidates)
                        ?: selectBestLooseArtistCandidate(normalizedName, mixedCandidates)

                    resolvedName = searchArtist?.title?.trim()?.takeIf { it.isNotBlank() } ?: normalizedName
                    browseId = searchArtist
                        ?.id
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::normalizeArtistBrowseId)

                    finalImage = searchArtist?.thumbnailUrl?.takeIf { it.isNotBlank() }
                    val searchSubscribers = extractArtistSubscribersFromBrowse(searchJson)
                    finalSubscribers = listOfNotNull(
                        searchArtist?.artist?.let { extractSubscriberSnippet(it) },
                        searchSubscribers
                    )
                        .maxByOrNull(::artistStatsScore)
                        ?.takeIf { artistStatsScore(it) > 0 }
                    fallbackAlbums = extractArtistAlbumsFromSearch(normalizedName, mixedCandidates)
                }
            }

            var topSongs: List<Song> = emptyList()
            var albums: List<Song> = fallbackAlbums
            var singles: List<Song> = emptyList()
            var videos: List<Song> = emptyList()
            var featuredOn: List<Song> = emptyList()
            var relatedArtists: List<Song> = emptyList()
            var sections: List<ArtistSectionInfo> = emptyList()
            var topSongsBrowseId: String? = null
            var songsMoreEndpoint: String? = null
            var albumsMoreEndpoint: String? = null
            var singlesMoreEndpoint: String? = null

            if (!browseId.isNullOrBlank()) {
                val browseBody = Innertube.webRemixBody(language, region) {
                    put("browseId", browseId)
                }

                // Use field-masked browse to reduce payload (~200KB → ~30KB)
                val browseRequest = Innertube.musicBrowsePost(
                    body = browseBody,
                    acceptLanguage = getAcceptLanguageHeader(language, region)
                )

                val browseResponse = client.newCall(browseRequest).execute()
                if (browseResponse.isSuccessful) {
                    val browseRaw = browseResponse.body?.string()
                    if (!browseRaw.isNullOrBlank()) {
                        val browseJson = JSONObject(browseRaw)

                        // Debug: log header renderer type
                        val headerObj = browseJson.optJSONObject("header")
                        val headerType = listOf(
                            "musicImmersiveHeaderRenderer",
                            "musicVisualHeaderRenderer",
                            "musicDetailHeaderRenderer",
                            "musicResponsiveHeaderRenderer",
                            "c4TabbedHeaderRenderer"
                        ).firstOrNull { headerObj?.has(it) == true } ?: "UNKNOWN"
                        Log.d(TAG, "🎨 Artist browse header type: $headerType (region=$region)")

                        if (resolvedName.isBlank()) {
                            resolvedName = extractArtistTitleFromBrowse(browseJson).orEmpty()
                        }
                        extractBestArtistHeaderImage(browseJson)?.let { image ->
                            Log.d(TAG, "🎨 Artist image from browse: ${image.take(80)}...")
                            finalImage = image
                        } ?: Log.w(TAG, "⚠️ No artist image found from browse response")

                        // Try responsive header first (newer YTM format), then
                        // fall back to the generic deep-scan subscriber extraction.
                        val responsiveSubscribers = extractResponsiveHeaderSubscribers(browseJson)
                        if (!responsiveSubscribers.isNullOrBlank()) {
                            Log.d(TAG, "🎨 Artist subscribers from responsive header: $responsiveSubscribers")
                            finalSubscribers = responsiveSubscribers
                        } else {
                            extractArtistSubscribersFromBrowse(browseJson)?.let { subscribers ->
                                Log.d(TAG, "🎨 Artist subscribers from browse: $subscribers")
                                finalSubscribers = subscribers
                            } ?: Log.w(TAG, "⚠️ No subscriber count found from browse response")
                        }

                        finalDescription = extractArtistDescription(browseJson)
                        finalBannerUrl = extractArtistBannerUrl(browseJson)
                        finalShufflePlaylistId = extractShufflePlaylistId(browseJson)
                        finalRadioPlaylistId = extractRadioPlaylistId(browseJson)

                        val parsedSections = parseArtistSectionsFromBrowse(
                            root = browseJson,
                            fallbackArtistName = resolvedName.ifBlank { normalizedName }
                        )
                        Log.d(TAG, "🎨 Parsed ${parsedSections.size} sections: ${parsedSections.map { "${it.type}(${it.items.size} items)" }}")

                        if (parsedSections.isNotEmpty()) {
                            sections = parsedSections
                            topSongs = parsedSections
                                .filter { it.type == ArtistSectionType.TOP_SONGS }
                                .flatMap { it.items }
                                .distinctBy { it.id }
                                .take(5)
                            albums = parsedSections
                                .filter { it.type == ArtistSectionType.ALBUMS }
                                .flatMap { it.items }
                                .distinctBy { it.id }
                                .ifEmpty { albums }
                            singles = parsedSections
                                .filter { it.type == ArtistSectionType.SINGLES }
                                .flatMap { it.items }
                                .distinctBy { it.id }
                            videos = parsedSections
                                .filter { it.type == ArtistSectionType.VIDEOS }
                                .flatMap { it.items }
                                .distinctBy { it.id }
                            featuredOn = parsedSections
                                .filter { it.type == ArtistSectionType.FEATURED_ON }
                                .flatMap { it.items }
                                .distinctBy { it.id }
                            relatedArtists = parsedSections
                                .filter { it.type == ArtistSectionType.RELATED_ARTISTS }
                                .flatMap { it.items }
                                .distinctBy { it.id }
                            topSongsBrowseId = parsedSections
                                .firstOrNull {
                                    it.type == ArtistSectionType.TOP_SONGS &&
                                        !it.browseId.isNullOrBlank()
                                }
                                ?.browseId
                            songsMoreEndpoint = parsedSections.firstOrNull {
                                it.type == ArtistSectionType.TOP_SONGS
                            }?.moreEndpoint
                            albumsMoreEndpoint = parsedSections.firstOrNull {
                                it.type == ArtistSectionType.ALBUMS
                            }?.moreEndpoint
                            singlesMoreEndpoint = parsedSections.firstOrNull {
                                it.type == ArtistSectionType.SINGLES
                            }?.moreEndpoint
                        }

                        val albumsFromBrowse = extractArtistAlbumsFromBrowse(
                            root = browseJson,
                            fallbackArtistName = resolvedName.ifBlank { normalizedName }
                        )
                        if (albumsFromBrowse.isNotEmpty()) {
                            val merged = linkedMapOf<String, Song>()
                            (albums + albumsFromBrowse).forEach { album ->
                                merged.putIfAbsent(album.id, album)
                            }
                            albums = merged.values.toList()
                        }
                    }
                }
            }

            // Fallback for artist channels where WEB_REMIX browse omits avatar/subscriber details.
            if (isChannelBrowseId(browseId) && (finalImage.isNullOrBlank() || finalSubscribers.isNullOrBlank())) {
                val channelBrowseId = browseId ?: ""
                fetchChannelProfileFromYouTube(
                    browseId = channelBrowseId,
                    language = language,
                    region = region
                ).onSuccess { channelProfile ->
                    if (finalImage.isNullOrBlank()) {
                        finalImage = channelProfile.imageUrl ?: finalImage
                    }
                    if (finalSubscribers.isNullOrBlank()) {
                        finalSubscribers = channelProfile.subscribersText ?: finalSubscribers
                    }
                }
            }

            val finalName = resolvedName.ifBlank {
                normalizedName.ifBlank { browseId.orEmpty() }
            }

            Result.success(
                ArtistProfileInfo(
                    name = finalName,
                    browseId = browseId,
                    imageUrl = finalImage,
                    bannerUrl = finalBannerUrl,
                    subscribersText = formatArtistStats(finalSubscribers),
                    description = finalDescription,
                    shufflePlaylistId = finalShufflePlaylistId,
                    radioPlaylistId = finalRadioPlaylistId,
                    topSongs = topSongs,
                    albums = albums,
                    singles = singles,
                    videos = videos,
                    featuredOn = featuredOn,
                    relatedArtists = relatedArtists,
                    sections = sections,
                    topSongsBrowseId = topSongsBrowseId,
                    songsMoreEndpoint = songsMoreEndpoint,
                    albumsMoreEndpoint = albumsMoreEndpoint,
                    singlesMoreEndpoint = singlesMoreEndpoint
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch artist profile from Innertube", e)
            Result.failure(e)
        }
    }

    suspend fun getArtistSongsFromInnertube(
        artistName: String,
        browseId: String? = null,
        limit: Int = 240
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(600)
        val profileResult = getArtistProfileFromInnertube(
            artistName = artistName,
            browseIdHint = browseId
        )

        profileResult.fold(
            onSuccess = { profile ->
                val merged = linkedMapOf<String, Song>()

                fun appendSongs(candidates: List<Song>) {
                    candidates.asSequence()
                        .filter(::isPlayableArtistSong)
                        .forEach { song -> merged.putIfAbsent(song.id, song) }
                }

                appendSongs(profile.topSongs)
                appendSongs(profile.videos)

                val topSongsBrowseId = profile.topSongsBrowseId ?: profile.browseId
                val shouldTryTopSongsEndpoint = merged.size < safeLimit &&
                    (!topSongsBrowseId.isNullOrBlank() || !profile.songsMoreEndpoint.isNullOrBlank())
                if (shouldTryTopSongsEndpoint) {
                    fetchSongsByBrowseWithContinuation(
                        browseId = topSongsBrowseId,
                        limit = safeLimit - merged.size,
                        initialMoreEndpoint = profile.songsMoreEndpoint
                    ).onSuccess(::appendSongs)
                }

                if (merged.size < safeLimit && !profile.browseId.isNullOrBlank()) {
                    val alreadyTriedSameBrowseId =
                        profile.browseId == topSongsBrowseId &&
                            profile.songsMoreEndpoint.isNullOrBlank()
                    if (!alreadyTriedSameBrowseId) {
                        fetchSongsByBrowseWithContinuation(
                            browseId = profile.browseId,
                            limit = safeLimit - merged.size
                        ).onSuccess(::appendSongs)
                    }
                }

                val songs = merged.values.take(safeLimit)
                if (songs.isNotEmpty()) {
                    Result.success(songs)
                } else {
                    Result.failure(Exception("No playable artist songs found"))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun getArtistSectionItemsFromInnertube(
        artistBrowseId: String,
        sectionType: ArtistSectionType,
        moreEndpoint: String? = null,
        limit: Int = 120
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val safeBrowseId = artistBrowseId.trim()
        if (safeBrowseId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Artist browseId is empty"))
        }
        if (sectionType == ArtistSectionType.UNKNOWN) {
            return@withContext Result.failure(IllegalArgumentException("Artist section type is unknown"))
        }
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(600)
        val safeMoreEndpoint = moreEndpoint?.trim()?.takeIf { it.isNotEmpty() }

        if (sectionType == ArtistSectionType.TOP_SONGS) {
            return@withContext fetchSongsByBrowseWithContinuation(
                browseId = safeBrowseId,
                limit = safeLimit,
                initialMoreEndpoint = safeMoreEndpoint
            )
        }

        if (sectionType == ArtistSectionType.ALBUMS || sectionType == ArtistSectionType.SINGLES) {
            return@withContext fetchAlbumsByBrowseWithContinuation(
                browseId = safeBrowseId,
                fallbackArtistName = "",
                limit = safeLimit,
                initialMoreEndpoint = safeMoreEndpoint,
                desiredSectionType = sectionType
            )
        }

        return@withContext fetchGenericArtistSectionWithContinuation(
            browseId = safeBrowseId,
            sectionType = sectionType,
            limit = safeLimit,
            initialMoreEndpoint = safeMoreEndpoint
        )
    }

    private enum class InitialArtistBrowseMode {
        NONE,
        PARAMS,
        CONTINUATION
    }

    private suspend fun fetchGenericArtistSectionWithContinuation(
        browseId: String,
        sectionType: ArtistSectionType,
        limit: Int,
        initialMoreEndpoint: String? = null
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(600)
        val safeMoreEndpoint = initialMoreEndpoint?.trim()?.takeIf { it.isNotEmpty() }
        if (browseId.isBlank() && safeMoreEndpoint == null) {
            return@withContext Result.success(emptyList())
        }

        val initialModes = when {
            safeMoreEndpoint == null -> listOf(InitialArtistBrowseMode.NONE)
            browseId.isBlank() -> listOf(InitialArtistBrowseMode.CONTINUATION)
            else -> listOf(
                InitialArtistBrowseMode.PARAMS,
                InitialArtistBrowseMode.CONTINUATION
            )
        }

        var lastError: Throwable? = null
        for ((index, mode) in initialModes.withIndex()) {
            val attempt = fetchGenericArtistSectionAttempt(
                browseId = browseId,
                sectionType = sectionType,
                limit = safeLimit,
                initialMoreEndpoint = safeMoreEndpoint,
                initialMode = mode
            )
            if (attempt.isFailure) {
                lastError = attempt.exceptionOrNull()
                continue
            }
            val items = attempt.getOrNull().orEmpty()
            if (items.isNotEmpty() || index == initialModes.lastIndex) {
                return@withContext Result.success(items)
            }
        }

        Result.failure(lastError ?: Exception("Failed to load artist section"))
    }

    private suspend fun fetchGenericArtistSectionAttempt(
        browseId: String,
        sectionType: ArtistSectionType,
        limit: Int,
        initialMoreEndpoint: String?,
        initialMode: InitialArtistBrowseMode
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (limit <= 0) {
            return@withContext Result.success(emptyList())
        }

        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val seenTokens = mutableSetOf<String>()
            val collected = linkedMapOf<String, Song>()
            var continuationToken: String? = null
            var rounds = 0
            var initialModeConsumed = false

            while (collected.size < limit && rounds < 8) {
                val requestBody = Innertube.webRemixBody(language, region) {
                    if (!continuationToken.isNullOrBlank()) {
                        put("continuation", continuationToken)
                    } else if (!initialModeConsumed) {
                        when (initialMode) {
                            InitialArtistBrowseMode.NONE -> {
                                if (browseId.isNotBlank()) {
                                    put("browseId", browseId)
                                }
                            }
                            InitialArtistBrowseMode.PARAMS -> {
                                if (browseId.isNotBlank()) {
                                    put("browseId", browseId)
                                    initialMoreEndpoint?.let { put("params", it) }
                                }
                            }
                            InitialArtistBrowseMode.CONTINUATION -> {
                                initialMoreEndpoint?.let { put("continuation", it) }
                            }
                        }
                        initialModeConsumed = true
                    } else if (browseId.isNotBlank()) {
                        put("browseId", browseId)
                    }
                }

                if (
                    requestBody.optString("browseId", "").isBlank() &&
                    requestBody.optString("continuation", "").isBlank()
                ) {
                    break
                }

                val request = Innertube.musicPost(
                    endpoint = Innertube.BROWSE,
                    body = requestBody,
                    acceptLanguage = getAcceptLanguageHeader(language, region)
                )

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (collected.isEmpty()) {
                        return@withContext Result.failure(InvalidHttpCodeException(response.code))
                    }
                    break
                }

                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) break
                val json = JSONObject(raw)

                val parsedSections = parseArtistSectionsFromBrowse(
                    root = json,
                    fallbackArtistName = ""
                )
                val parsedItems = parsedSections
                    .asSequence()
                    .filter { section -> section.type == sectionType }
                    .flatMap { section -> section.items.asSequence() }
                    .toList()
                val continuationItems = parseArtistContinuationBySectionType(json, sectionType)

                (parsedItems + continuationItems)
                    .filterArtistSectionItems(sectionType)
                    .forEach { item -> collected.putIfAbsent(item.id, item) }

                val nextToken = extractFirstContinuationToken(json)?.takeIf { it.isNotBlank() }
                if (nextToken.isNullOrBlank() || !seenTokens.add(nextToken)) {
                    break
                }
                continuationToken = nextToken
                rounds += 1
            }

            Result.success(collected.values.take(limit))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchSongsByBrowseWithContinuation(
        browseId: String?,
        limit: Int,
        initialMoreEndpoint: String? = null
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val safeBrowseId = browseId?.trim().orEmpty()
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(600)
        val safeMoreEndpoint = initialMoreEndpoint?.trim()?.takeIf { it.isNotEmpty() }
        if (safeBrowseId.isEmpty() && safeMoreEndpoint == null) {
            return@withContext Result.success(emptyList())
        }

        val initialModes = when {
            safeMoreEndpoint == null -> listOf(InitialArtistBrowseMode.NONE)
            safeBrowseId.isBlank() -> listOf(InitialArtistBrowseMode.CONTINUATION)
            else -> listOf(
                InitialArtistBrowseMode.PARAMS,
                InitialArtistBrowseMode.CONTINUATION
            )
        }

        var lastError: Throwable? = null
        for ((index, mode) in initialModes.withIndex()) {
            val attempt = fetchSongsByBrowseAttempt(
                browseId = safeBrowseId,
                limit = safeLimit,
                initialMoreEndpoint = safeMoreEndpoint,
                initialMode = mode
            )
            if (attempt.isFailure) {
                lastError = attempt.exceptionOrNull()
                continue
            }
            val songs = attempt.getOrNull().orEmpty()
            if (songs.isNotEmpty() || index == initialModes.lastIndex) {
                return@withContext Result.success(songs)
            }
        }

        Result.failure(lastError ?: Exception("Failed to load artist songs"))
    }

    private suspend fun fetchSongsByBrowseAttempt(
        browseId: String,
        limit: Int,
        initialMoreEndpoint: String?,
        initialMode: InitialArtistBrowseMode
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (limit <= 0) {
            return@withContext Result.success(emptyList())
        }

        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val seenTokens = mutableSetOf<String>()
            val collected = linkedMapOf<String, Song>()
            var continuationToken: String? = null
            var rounds = 0
            var initialModeConsumed = false

            while (collected.size < limit && rounds < 8) {
                val requestBody = Innertube.webRemixBody(language, region) {
                    if (!continuationToken.isNullOrBlank()) {
                        put("continuation", continuationToken)
                    } else if (!initialModeConsumed) {
                        when (initialMode) {
                            InitialArtistBrowseMode.NONE -> {
                                if (browseId.isNotBlank()) {
                                    put("browseId", browseId)
                                }
                            }
                            InitialArtistBrowseMode.PARAMS -> {
                                if (browseId.isNotBlank()) {
                                    put("browseId", browseId)
                                    initialMoreEndpoint?.let { put("params", it) }
                                }
                            }
                            InitialArtistBrowseMode.CONTINUATION -> {
                                initialMoreEndpoint?.let { put("continuation", it) }
                            }
                        }
                        initialModeConsumed = true
                    } else if (browseId.isNotBlank()) {
                        put("browseId", browseId)
                    }
                }
                if (
                    requestBody.optString("browseId", "").isBlank() &&
                    requestBody.optString("continuation", "").isBlank()
                ) {
                    break
                }

                val request = Innertube.musicPost(
                    endpoint = Innertube.BROWSE,
                    body = requestBody,
                    acceptLanguage = getAcceptLanguageHeader(language, region)
                )

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (collected.isEmpty()) {
                        return@withContext Result.failure(InvalidHttpCodeException(response.code))
                    }
                    break
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) break

                val json = JSONObject(raw)
                val songs = sanitizeSongResults(
                    songs = parseBrowseResponse(raw) + parseArtistContinuationSongs(json),
                    limit = 0,
                    offset = 0
                )
                songs.filter(::isPlayableArtistSong).forEach { song ->
                    collected.putIfAbsent(song.id, song)
                }

                val nextToken = extractFirstContinuationToken(json)?.takeIf { it.isNotBlank() }
                if (nextToken.isNullOrBlank() || !seenTokens.add(nextToken)) {
                    break
                }
                continuationToken = nextToken
                rounds += 1
            }

            Result.success(collected.values.take(limit))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchAlbumsByBrowseWithContinuation(
        browseId: String?,
        fallbackArtistName: String,
        limit: Int,
        initialMoreEndpoint: String? = null,
        desiredSectionType: ArtistSectionType? = null
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val safeBrowseId = browseId?.trim().orEmpty()
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(400)
        val safeMoreEndpoint = initialMoreEndpoint?.trim()?.takeIf { it.isNotEmpty() }
        if (safeBrowseId.isEmpty() && safeMoreEndpoint == null) {
            return@withContext Result.success(emptyList())
        }

        val initialModes = when {
            safeMoreEndpoint == null -> listOf(InitialArtistBrowseMode.NONE)
            safeBrowseId.isBlank() -> listOf(InitialArtistBrowseMode.CONTINUATION)
            else -> listOf(
                InitialArtistBrowseMode.PARAMS,
                InitialArtistBrowseMode.CONTINUATION
            )
        }

        var lastError: Throwable? = null
        for ((index, mode) in initialModes.withIndex()) {
            val attempt = fetchAlbumSectionBrowseAttempt(
                browseId = safeBrowseId,
                fallbackArtistName = fallbackArtistName,
                limit = safeLimit,
                initialMoreEndpoint = safeMoreEndpoint,
                initialMode = mode,
                desiredSectionType = desiredSectionType
            )
            if (attempt.isFailure) {
                lastError = attempt.exceptionOrNull()
                continue
            }
            val items = attempt.getOrNull().orEmpty()
            if (items.isNotEmpty() || index == initialModes.lastIndex) {
                return@withContext Result.success(items)
            }
        }

        Result.failure(lastError ?: Exception("Failed to load artist albums"))
    }

    private suspend fun fetchAlbumSectionBrowseAttempt(
        browseId: String,
        fallbackArtistName: String,
        limit: Int,
        initialMoreEndpoint: String?,
        initialMode: InitialArtistBrowseMode,
        desiredSectionType: ArtistSectionType?
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (limit <= 0) {
            return@withContext Result.success(emptyList())
        }

        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val seenTokens = mutableSetOf<String>()
            val collected = linkedMapOf<String, Song>()
            var continuationToken: String? = null
            var rounds = 0
            var initialModeConsumed = false

            while (collected.size < limit && rounds < 8) {
                val requestBody = Innertube.webRemixBody(language, region) {
                    if (!continuationToken.isNullOrBlank()) {
                        put("continuation", continuationToken)
                    } else if (!initialModeConsumed) {
                        when (initialMode) {
                            InitialArtistBrowseMode.NONE -> {
                                if (browseId.isNotBlank()) {
                                    put("browseId", browseId)
                                }
                            }
                            InitialArtistBrowseMode.PARAMS -> {
                                if (browseId.isNotBlank()) {
                                    put("browseId", browseId)
                                    initialMoreEndpoint?.let { put("params", it) }
                                }
                            }
                            InitialArtistBrowseMode.CONTINUATION -> {
                                initialMoreEndpoint?.let { put("continuation", it) }
                            }
                        }
                        initialModeConsumed = true
                    } else if (browseId.isNotBlank()) {
                        put("browseId", browseId)
                    }
                }
                if (
                    requestBody.optString("browseId", "").isBlank() &&
                    requestBody.optString("continuation", "").isBlank()
                ) {
                    break
                }

                val request = Innertube.musicPost(
                    endpoint = Innertube.BROWSE,
                    body = requestBody,
                    acceptLanguage = getAcceptLanguageHeader(language, region)
                )

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (collected.isEmpty()) {
                        return@withContext Result.failure(InvalidHttpCodeException(response.code))
                    }
                    break
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) break

                val json = JSONObject(raw)
                val parsedSections = parseArtistSectionsFromBrowse(
                    root = json,
                    fallbackArtistName = fallbackArtistName
                )
                val parsedFromSections = parsedSections
                    .asSequence()
                    .filter { section ->
                        when (desiredSectionType) {
                            ArtistSectionType.ALBUMS -> section.type == ArtistSectionType.ALBUMS
                            ArtistSectionType.SINGLES -> section.type == ArtistSectionType.SINGLES
                            else -> section.type == ArtistSectionType.ALBUMS || section.type == ArtistSectionType.SINGLES
                        }
                    }
                    .flatMap { section -> section.items.asSequence() }
                    .toList()
                val fallbackSectionItems = if (parsedFromSections.isEmpty()) {
                    parsedSections
                        .asSequence()
                        .filter { section ->
                            section.type == ArtistSectionType.ALBUMS ||
                                section.type == ArtistSectionType.SINGLES
                        }
                        .flatMap { section -> section.items.asSequence() }
                        .toList()
                } else {
                    emptyList()
                }
                val parsedFromContinuation = parseArtistContinuationAlbums(
                    root = json,
                    fallbackArtistName = fallbackArtistName
                )
                (parsedFromSections + fallbackSectionItems + parsedFromContinuation)
                    .filterArtistSectionItems(desiredSectionType ?: ArtistSectionType.ALBUMS)
                    .forEach { album -> collected.putIfAbsent(album.id, album) }

                val nextToken = extractFirstContinuationToken(json)?.takeIf { it.isNotBlank() }
                if (nextToken.isNullOrBlank() || !seenTokens.add(nextToken)) {
                    break
                }
                continuationToken = nextToken
                rounds += 1
            }

            Result.success(collected.values.take(limit))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun Iterable<Song>.filterArtistSectionItems(
        sectionType: ArtistSectionType
    ): List<Song> {
        return when (sectionType) {
            ArtistSectionType.TOP_SONGS -> asSequence()
                .filter(::isPlayableArtistSong)
                .toList()
            ArtistSectionType.ALBUMS,
            ArtistSectionType.SINGLES -> asSequence()
                .filter { item ->
                    item.id.isNotBlank() &&
                        !isLikelyVideoId(item.id) &&
                        item.contentType == ContentType.ALBUM
                }
                .toList()
            ArtistSectionType.VIDEOS -> asSequence()
                .filter { item ->
                    item.id.isNotBlank() &&
                        isLikelyVideoId(item.id) &&
                        (item.contentType == ContentType.VIDEO || item.contentType == ContentType.SONG)
                }
                .toList()
            ArtistSectionType.FEATURED_ON -> asSequence()
                .filter { item ->
                    item.id.isNotBlank() && item.contentType == ContentType.PLAYLIST
                }
                .toList()
            ArtistSectionType.RELATED_ARTISTS -> asSequence()
                .filter { item ->
                    item.id.isNotBlank() && item.contentType == ContentType.ARTIST
                }
                .toList()
            ArtistSectionType.UNKNOWN -> asSequence()
                .filter { item -> item.id.isNotBlank() }
                .toList()
        }
    }

    suspend fun getAlbumSongsFromInnertube(
        albumBrowseId: String,
        limit: Int = 300
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val browseId = albumBrowseId.trim()
        if (browseId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Album browseId is empty"))
        }

        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val browseBody = Innertube.webRemixBody(language, region) {
                put("browseId", browseId)
            }

            val browseRequest = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = browseBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val browseResponse = client.newCall(browseRequest).execute()
            if (!browseResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Innertube album browse failed: ${browseResponse.code}"))
            }

            val browseRaw = browseResponse.body?.string()
                ?: return@withContext Result.failure(Exception("Empty Innertube album browse response"))

            val tracks = sanitizeSongResults(
                songs = parseBrowseResponse(browseRaw),
                limit = limit,
                offset = 0
            )
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch album tracks from Innertube", e)
            Result.failure(e)
        }
    }
    
    suspend fun getSongDetails(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            val requestBody = Innertube.androidBody(language, region) {
                put("videoId", videoId)
            }
            
            val request = Innertube.youtubeAndroidPost(
                endpoint = Innertube.PLAYER_MUSIC,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JSONObject(responseBody)
            val videoDetails = json.optJSONObject("videoDetails") 
                ?: return@withContext Result.failure(Exception("No details"))

            val thumbnails = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnailUrl = extractHighestQualityThumbnail(
                thumbnails = thumbnails,
                fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            )

            Result.success(Song(
                id = videoId,
                title = videoDetails.optString("title", "Unknown"),
                artist = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                    runs = null,
                    playerAuthor = videoDetails.optString("author", "Unknown"),
                    playerChannelId = videoDetails.optString("channelId", "")
                ).displayName.let { cleanArtistName(it) },
                duration = videoDetails.optInt("lengthSeconds", 0),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                viewCount = videoDetails.optString("viewCount", "0").toLongOrNull()
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Get details error", e)
            Result.failure(e)
        }
    }

    /**
     * Get Trending Songs using YouTube Music Charts API
     * Uses the browse endpoint with charts browse ID
     */
    suspend fun getTrendingSongs(limit: Int = 30): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val year = currentYear()
            Log.d(TAG, "🔥 Fetching real-time trending songs for region: $region")
            
            // YouTube Music Charts browse ID - region-specific
            val requestBody = Innertube.webRemixBody(language, region) {
                // Browse ID for "Top Songs" chart
                put("browseId", "FEmusic_charts")
                put("params", "sgYJQGVuZ2luZQ%3D%3D") // Chart type params
            }
            
            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Charts API failed, falling back to search")
                return@withContext searchSongs("trending songs $region $year", limit)
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext searchSongs("top songs $region $year", limit)
            }
            
            val songs = sanitizeSongResults(
                songs = parseChartsResponse(responseBody),
                limit = limit,
                offset = 0
            )
            
            if (songs.isEmpty()) {
                Log.w(TAG, "No songs from charts, using search fallback")
                return@withContext searchSongs("viral songs $region $year", limit)
            }
            
            Log.d(TAG, "✅ Got ${songs.size} trending songs for $region")
            Result.success(songs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Trending error, using fallback", e)
            val region = getRequestRegion()
            searchSongs("popular songs $region ${currentYear()}", limit)
        }
    }

    /**
     * Get New Releases using YouTube Music New Releases API
     */
    suspend fun getNewReleases(limit: Int = 25): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val year = currentYear()
            Log.d(TAG, "🆕 Fetching real-time new releases for region: $region")
            
            val requestBody = Innertube.webRemixBody(language, region) {
                // Browse ID for New Releases
                put("browseId", "FEmusic_new_releases")
            }
            
            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "New releases API failed, falling back to search")
                return@withContext searchSongs("new songs $region $year", limit)
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext searchSongs("latest songs $region $year", limit)
            }
            
            val songs = sanitizeSongResults(
                songs = parseBrowseResponse(responseBody),
                limit = limit,
                offset = 0
            )
            
            if (songs.isEmpty()) {
                Log.w(TAG, "No songs from new releases, using search fallback")
                return@withContext searchSongs("new releases $region $year", limit)
            }
            
            Log.d(TAG, "✅ Got ${songs.size} new releases for $region")
            Result.success(songs)
            
        } catch (e: Exception) {
            Log.e(TAG, "New releases error, using fallback", e)
            val region = getRequestRegion()
            searchSongs("new music $region ${currentYear()}", limit)
        }
    }

    suspend fun getEnglishHits(limit: Int = 25): Result<List<Song>> = withContext(Dispatchers.IO) {
        // Use specific search for English songs with current year
        searchSongs("top English songs ${currentYear()} trending", limit)
    }

    /**
     * Get real-time autocomplete suggestions from YouTube Music
     * Uses the music/get_search_suggestions endpoint
     */
    suspend fun getSearchSuggestions(query: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return@withContext Result.success(emptyList())

        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            val requestBody = Innertube.webRemixBody(language, region) {
                put("input", normalizedQuery)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.SEARCH_SUGGESTIONS,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val fallback = fetchFallbackSearchSuggestions(normalizedQuery)
                return@withContext Result.success(fallback)
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                val fallback = fetchFallbackSearchSuggestions(normalizedQuery)
                return@withContext Result.success(fallback)
            }

            val suggestions = parseSearchSuggestions(responseBody)
            if (suggestions.isNotEmpty()) {
                return@withContext Result.success(suggestions.take(15))
            }

            val fallback = fetchFallbackSearchSuggestions(normalizedQuery)
            Result.success(fallback)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Search suggestions error", e)
            Result.success(fetchFallbackSearchSuggestions(normalizedQuery))
        }
    }

    /**
     * Parse YouTube Music search suggestions response
     */
    private fun parseSearchSuggestions(jsonString: String): List<String> {
        val suggestions = linkedSetOf<String>()

        try {
            val json = JSONObject(jsonString)
            val roots = mutableListOf<JSONObject>()
            roots.add(json)
            json.optJSONObject("contents")?.let { roots.add(it) }

            roots.forEach { root ->
                collectSuggestionEntries(root, suggestions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing suggestions", e)
        }

        return suggestions.toList()
    }

    private fun collectSuggestionEntries(node: JSONObject, output: MutableSet<String>) {
        val suggestionRenderer = node.optJSONObject("searchSuggestionRenderer")
        if (suggestionRenderer != null) {
            val suggestion = extractText(suggestionRenderer.optJSONObject("suggestion")).trim()
            if (suggestion.isNotEmpty()) {
                output.add(suggestion)
            }
        }

        val musicItem = node.optJSONObject("musicResponsiveListItemRenderer")
        if (musicItem != null) {
            val flexColumns = musicItem.optJSONArray("flexColumns")
            if (flexColumns != null && flexColumns.length() > 0) {
                val text = extractMusicText(flexColumns.optJSONObject(0)).trim()
                if (text.isNotEmpty()) {
                    output.add(text)
                }
            }
        }

        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = node.opt(key)) {
                is JSONObject -> collectSuggestionEntries(value, output)
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) {
                            collectSuggestionEntries(item, output)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchFallbackSearchSuggestions(query: String): List<String> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encodedQuery")
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val array = JSONArray(body)
            val suggestionsArray = array.optJSONArray(1) ?: return emptyList()
            buildList {
                for (i in 0 until suggestionsArray.length()) {
                    val suggestion = suggestionsArray.optString(i).trim()
                    if (suggestion.isNotEmpty()) add(suggestion)
                }
            }.distinct().take(15)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parse charts browse response
     */
    private fun parseChartsResponse(jsonString: String): List<Song> {
        val songs = mutableListOf<Song>()
        
        try {
            val json = JSONObject(jsonString)
            
            // Navigate to chart contents
            val contents = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
            
            if (contents != null) {
                for (t in 0 until contents.length()) {
                    val tabContent = contents.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?: continue
                    
                    songs.addAll(parseSectionContents(tabContent))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing charts", e)
        }
        
        return songs.distinctBy { it.id }
    }
    
    /**
     * Parse browse response (new releases, etc.)
     */
    private fun parseBrowseResponse(jsonString: String): List<Song> {
        val songs = mutableListOf<Song>()
        
        try {
            val json = JSONObject(jsonString)
            
            // Try different browse structures
            val contents = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
            
            if (contents != null) {
                for (t in 0 until contents.length()) {
                    val sectionList = contents.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?: continue
                    
                    songs.addAll(parseSectionContents(sectionList))
                }
            }
            
            // Alternative structure
            val gridContents = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            
            if (gridContents != null) {
                songs.addAll(parseSectionContents(gridContents))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing browse response", e)
        }
        
        return songs.distinctBy { it.id }
    }
    
    /**
     * Parse section contents to extract songs
     */
    private fun parseSectionContents(sections: JSONArray): List<Song> {
        val songs = mutableListOf<Song>()
        
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            
            // Try musicCarouselShelfRenderer
            val carouselContents = section.optJSONObject("musicCarouselShelfRenderer")
                ?.optJSONArray("contents")
            
            if (carouselContents != null) {
                for (j in 0 until carouselContents.length()) {
                    val item = carouselContents.optJSONObject(j) ?: continue
                    parseMusicItem(item)?.let { songs.add(it) }
                }
            }
            
            // Try musicShelfRenderer
            val shelfContents = section.optJSONObject("musicShelfRenderer")
                ?.optJSONArray("contents")
            
            if (shelfContents != null) {
                for (j in 0 until shelfContents.length()) {
                    val item = shelfContents.optJSONObject(j) ?: continue
                    parseMusicItem(item)?.let { songs.add(it) }
                }
            }
            
            // Try musicPlaylistShelfRenderer
            val playlistContents = section.optJSONObject("musicPlaylistShelfRenderer")
                ?.optJSONArray("contents")
            
            if (playlistContents != null) {
                for (j in 0 until playlistContents.length()) {
                    val item = playlistContents.optJSONObject(j) ?: continue
                    parseMusicItem(item)?.let { songs.add(it) }
                }
            }
        }
        
        return songs
    }
    
    /**
     * Parse individual music item from browse response
     */
    private fun parseMusicItem(item: JSONObject): Song? {
        try {
            // Try musicResponsiveListItemRenderer
            val listItem = item.optJSONObject("musicResponsiveListItemRenderer")
            if (listItem != null) {
                return parseMusicResponsiveItem(listItem)
            }
            
            // Try musicTwoRowItemRenderer
            val twoRow = item.optJSONObject("musicTwoRowItemRenderer")
            if (twoRow != null) {
                val videoId = twoRow.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "")
                    ?: return null
                
                if (videoId.isEmpty()) return null
                
                val title = extractText(twoRow.optJSONObject("title"))
                // ViTune-style: extract only artist runs from subtitle, not full text
                val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                val artist = if (subtitleRuns != null) {
                    val refs = extractArtistAndAlbumRefs(subtitleRuns)
                    refs.artists.firstOrNull()?.name
                        ?: extractFirstNonSeparatorRun(subtitleRuns)
                } else {
                    extractText(twoRow.optJSONObject("subtitle"))
                }
                
                val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                
                val thumbnailUrl = extractHighestQualityThumbnail(
                    thumbnails = thumbnails,
                    fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                )
                
                // Detect content type (assume SONG since from YouTube Music browse)
                val contentType = detectContentType(title, artist, "3:00") // Default 3 min

                if (contentType != ContentType.SONG && contentType != ContentType.UNKNOWN) {
                    Log.d(TAG, "🎬 Filtering non-music from browse: $title ($contentType)")
                    return null
                }

                return Song(
                    id = videoId,
                    title = title,
                    artist = cleanArtistName(artist),
                    duration = 0,
                    thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                    category = "Music",
                    contentType = contentType
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing music item", e)
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // UP NEXT / QUEUE ENDPOINT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get "Up Next" songs (Queue) from YouTube Music
     * This is the fastest and most relevant way to get recommendations
     */
    suspend fun getUpNext(videoId: String): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "🎵 Fetching Up Next for: $videoId")
            
            val requestBody = Innertube.webRemixBody(language, region) {
                put("videoId", videoId)
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
            }
            
            val request = Innertube.musicPost(
                endpoint = Innertube.NEXT,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JSONObject(responseBody)
            
            // Parse queue from playlistPanelRenderer
            val queueItems = sanitizeSongResults(
                songs = parseQueueResponse(json),
                limit = 0,
                offset = 0
            )
            
            Log.d(TAG, "✅ Got ${queueItems.size} songs from Up Next")
            Result.success(queueItems)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Get Up Next error", e)
            Result.failure(e)
        }
    }

    /**
     * Get Radio Mix (RDAMVM) from YouTube Music
     * This creates a proper "Radio" station based on the song
     */
    suspend fun getRadioMix(videoId: String): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "📻 Fetching Radio Mix for: $videoId")
            
            // RDAMVM is the prefix for "Radio Mix" playlists in YouTube Music
            // It stands for "Radio Mix" + videoId
            val playlistId = "RDAMVM$videoId"
            
            val requestBody = Innertube.webRemixBody(language, region) {
                put("playlistId", playlistId)
                put("isAudioOnly", true)
            }
            
            val request = Innertube.musicPost(
                endpoint = Innertube.NEXT,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JSONObject(responseBody)
            
            // Parse queue from playlistPanelRenderer (same structure as Up Next)
            val queueItems = sanitizeSongResults(
                songs = parseQueueResponse(json),
                limit = 0,
                offset = 0
            )
            
            // Filter out the seed song if it appears first
            val filteredItems = if (queueItems.isNotEmpty() && queueItems[0].id == videoId) {
                queueItems.drop(1)
            } else {
                queueItems
            }
            
            Log.d(TAG, "✅ Got ${filteredItems.size} songs from Radio Mix")
            Result.success(filteredItems)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Get Radio Mix error", e)
            Result.failure(e)
        }
    }

    /**
     * Get song recommendations from Innertube by combining:
     * 1. YouTube Music Up Next
     * 2. YouTube Watch Next (regular YouTube related videos)
     * 3. YouTube Music Radio mix fallback
     */
    suspend fun getSongRecommendationsFromInnertube(
        videoId: String,
        limit: Int = 40
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        val seedVideoId = videoId.trim()
        val safeLimit = limit.coerceAtLeast(1)
        if (seedVideoId.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        try {
            val merged = linkedMapOf<String, Song>()

            fun collect(items: List<Song>) {
                val sanitized = sanitizeSongResults(
                    songs = items,
                    limit = 0,
                    offset = 0
                )
                sanitized.asSequence()
                    .filter { it.id != seedVideoId }
                    .forEach { candidate ->
                        merged.putIfAbsent(candidate.id, candidate)
                    }
            }

            val (upNextSongs, watchNextSongs) = coroutineScope {
                val upNextDeferred = async {
                    withTimeoutOrNull(RECOMMENDATION_STAGE_TIMEOUT_MS) {
                        getUpNext(seedVideoId).getOrNull().orEmpty()
                    }.orEmpty()
                }
                val watchNextDeferred = async {
                    withTimeoutOrNull(RECOMMENDATION_STAGE_TIMEOUT_MS) {
                        getYouTubeWatchNextRecommendations(seedVideoId).getOrNull().orEmpty()
                    }.orEmpty()
                }
                upNextDeferred.await() to watchNextDeferred.await()
            }

            collect(upNextSongs)
            collect(watchNextSongs)
            if (merged.size < safeLimit) {
                val radioSongs = withTimeoutOrNull(RECOMMENDATION_STAGE_TIMEOUT_MS) {
                    getRadioMix(seedVideoId).getOrNull().orEmpty()
                }.orEmpty()
                collect(radioSongs)
            }

            Result.success(merged.values.take(safeLimit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Song recommendation failed for seed=$seedVideoId: ${e.message}")
            Result.success(emptyList())
        }
    }

    private suspend fun getYouTubeWatchNextRecommendations(videoId: String): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val requestBody = Innertube.androidBody(language, region) {
                put("videoId", videoId)
                put("racyCheckOk", true)
                put("contentCheckOk", true)
            }

            val request = Innertube.youtubeAndroidPost(
                endpoint = Innertube.NEXT,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region),
                includeClientHeaders = true
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("YouTube watch next failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty YouTube watch next response"))

            Result.success(parseYouTubeWatchNextResponse(responseBody, videoId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseYouTubeWatchNextResponse(jsonString: String, seedVideoId: String): List<Song> {
        val songs = mutableListOf<Song>()

        try {
            val root = JSONObject(jsonString)
            val candidateArrays = mutableListOf<JSONArray>()

            root.optJSONObject("contents")
                ?.optJSONObject("twoColumnWatchNextResults")
                ?.optJSONObject("secondaryResults")
                ?.optJSONObject("secondaryResults")
                ?.optJSONArray("results")
                ?.let(candidateArrays::add)

            root.optJSONObject("contents")
                ?.optJSONObject("singleColumnWatchNextResults")
                ?.optJSONObject("results")
                ?.optJSONObject("results")
                ?.optJSONArray("contents")
                ?.let(candidateArrays::add)

            root.optJSONArray("onResponseReceivedEndpoints")?.let { endpoints ->
                for (i in 0 until endpoints.length()) {
                    endpoints.optJSONObject(i)
                        ?.optJSONObject("appendContinuationItemsAction")
                        ?.optJSONArray("continuationItems")
                        ?.let(candidateArrays::add)
                }
            }

            root.optJSONArray("onResponseReceivedCommands")?.let { commands ->
                for (i in 0 until commands.length()) {
                    commands.optJSONObject(i)
                        ?.optJSONObject("appendContinuationItemsAction")
                        ?.optJSONArray("continuationItems")
                        ?.let(candidateArrays::add)
                }
            }

            candidateArrays.forEach { items ->
                collectWatchNextSongs(items, songs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YouTube watch next response", e)
        }

        return songs
            .asSequence()
            .filter { it.id != seedVideoId }
            .distinctBy { it.id }
            .toList()
    }

    private fun collectWatchNextSongs(items: JSONArray?, output: MutableList<Song>) {
        if (items == null) return

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue

            parseVideoRenderer(item)?.let(output::add)

            collectWatchNextSongs(
                item.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents"),
                output
            )

            collectWatchNextSongs(
                item.optJSONObject("richItemRenderer")
                    ?.optJSONObject("content")
                    ?.let { content ->
                        JSONArray().put(content)
                    },
                output
            )

            collectWatchNextSongs(
                item.optJSONObject("compactAutoplayRenderer")
                    ?.optJSONArray("contents"),
                output
            )

            collectWatchNextSongs(
                item.optJSONObject("shelfRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("verticalListRenderer")
                    ?.optJSONArray("items"),
                output
            )
        }
    }

    private fun parseQueueResponse(json: JSONObject): List<Song> {
        val songs = mutableListOf<Song>()
        
        try {
            // Locate playlistPanelRenderer (Queue)
            // path: contents -> singleColumnMusicWatchNextResultsRenderer -> tabbedRenderer -> 
            // watchNextTabbedResultsRenderer -> tabs -> [0] -> tabRenderer -> content -> 
            // musicQueueRenderer -> content -> playlistPanelRenderer
            
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs")
                
            var playlistPanel: JSONObject? = null
            
            if (tabs != null) {
                // Usually the first tab "Up Next"
                for (i in 0 until tabs.length()) {
                    val tabContent = tabs.optJSONObject(i)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                    
                    val queue = tabContent?.optJSONObject("musicQueueRenderer")
                    
                    if (queue != null) {
                        playlistPanel = queue.optJSONObject("content")?.optJSONObject("playlistPanelRenderer")
                        break
                    }
                }
            }
            
            if (playlistPanel == null) return songs
            
            val contents = playlistPanel.optJSONArray("contents") ?: return songs
            
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i)
                    ?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                
                val videoId = item.optString("videoId", "")
                if (videoId.isEmpty()) continue
                
                val title = extractText(item.optJSONObject("title"))
                val longBylineRuns = item.optJSONObject("longBylineText")?.optJSONArray("runs")
                val shortBylineRuns = item.optJSONObject("shortBylineText")?.optJSONArray("runs")
                
                var artistInfo = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                    runs = longBylineRuns.toRunList(),
                    shortBylineRuns = shortBylineRuns.toRunList()
                )
                val artist = artistInfo.displayName
                
                val durationText = extractText(item.optJSONObject("lengthText"))
                val contentType = detectContentType(title, artist, durationText)
                if (contentType != ContentType.SONG && contentType != ContentType.UNKNOWN) {
                    Log.d(TAG, "🎬 Filtering non-song queue item: $title ($contentType)")
                    continue
                }
                
                val thumbnails = item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbnailUrl = extractHighestQualityThumbnail(
                    thumbnails = thumbnails,
                    fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                )
                
                songs.add(Song(
                    id = videoId,
                    title = title,
                    artist = cleanArtistName(artist),
                    duration = parseDuration(durationText),
                    thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                    category = "Music",
                    contentType = ContentType.SONG
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing queue", e)
        }
        
        return songs
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════

    private fun extractHighestQualityThumbnail(thumbnails: JSONArray?, fallback: String): String {
        if (thumbnails == null || thumbnails.length() == 0) return fallback

        var bestUrl: String? = null
        var bestScore = Long.MIN_VALUE

        for (i in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(i) ?: continue
            val url = item.optString("url", "").trim()
            if (url.isEmpty()) continue

            val width = item.optInt("width", 0)
            val height = item.optInt("height", 0)
            val score = if (width > 0 && height > 0) {
                width.toLong() * height.toLong()
            } else {
                estimateThumbnailScore(url)
            }

            if (score >= bestScore) {
                bestScore = score
                bestUrl = url
            }
        }

        return bestUrl ?: fallback
    }

    private fun estimateThumbnailScore(url: String): Long {
        val lower = url.lowercase(Locale.US)
        val explicitSize = Regex("w(\\d+)-h(\\d+)").find(lower)
        if (explicitSize != null) {
            val width = explicitSize.groupValues[1].toLongOrNull() ?: 0L
            val height = explicitSize.groupValues[2].toLongOrNull() ?: 0L
            if (width > 0 && height > 0) return width * height
        }

        return when {
            "maxresdefault" in lower -> 1280L * 720L
            "hq720" in lower -> 1280L * 720L
            "sddefault" in lower -> 640L * 480L
            "hqdefault" in lower -> 480L * 360L
            "mqdefault" in lower -> 320L * 180L
            "default" in lower -> 120L * 90L
            else -> 0L
        }
    }
    
    /**
     * Clean artist name by stripping YouTube channel artifacts.
     *
     * YouTube Music shows clean artist names (e.g. "Arijit Singh") but the
     * raw API often returns channel-style names like "Arijit Singh - Topic",
     * "ArijitSinghVEVO", or "Arijit Singh Official". This function removes
     * those suffixes so the UI matches what YTM actually displays.
     */
    private fun cleanArtistName(artist: String): String {
        if (artist.isBlank()) return artist

        var cleaned = artist.trim()

        // 1. Strip " - Topic" (auto-generated YT Music channels, all casing variants)
        cleaned = cleaned
            .replace(Regex("\\s*-\\s*[Tt]opic$"), "")

        // 2. Strip VEVO suffix (e.g. "ArijitSinghVEVO", "Arijit Singh VEVO")
        cleaned = cleaned
            .replace(Regex("\\s*VEVO$", RegexOption.IGNORE_CASE), "")

        // 3. Strip trailing "Official" / "Music" channel suffixes
        //    e.g. "Arijit Singh Official", "Arijit Singh - Official", "T-Series Music"
        cleaned = cleaned
            .replace(Regex("\\s*-?\\s*Official\\s*(Artist|Channel|Audio|Music)?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-?\\s*Music\\s*$", RegexOption.IGNORE_CASE), "")

        // 4. Strip view/subscriber count strings that leak into artist field
        //    e.g. "3.2M subscribers", "1.5B views"
        cleaned = cleaned
            .replace(Regex("[\\d.,]+[KMBkmb]?\\s*(subscribers?|views?).*$", RegexOption.IGNORE_CASE), "")

        // 5. Strip trailing type metadata that sometimes leaks from subtitle parsing
        //    e.g. trailing " · Song", " · Album", " · 2024"
        cleaned = cleaned
            .replace(Regex("\\s*[·•]\\s*(Song|Video|Album|Playlist|Single|EP|\\d{4}).*$", RegexOption.IGNORE_CASE), "")

        // 6. Remove duplicate artist names from multi-run concatenation
        //    e.g. "Arijit Singh, Arijit Singh" → "Arijit Singh"
        val parts = cleaned.split(Regex(",\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size > 1) {
            cleaned = parts.distinct().joinToString(", ")
        }

        // 7. Final trim of leftover whitespace / separators
        cleaned = cleaned
            .replace(Regex("^[,·•\\s]+|[,·•\\s]+$"), "")
            .trim()

        return cleaned.ifBlank { artist.trim() }
    }
    
    private fun upgradeThumbQuality(url: String, videoId: String): String {
        return ThumbnailUrlUtils.toHighQuality(url, videoId)
            ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
    }

    private fun parseDuration(durationText: String): Int {
        if (durationText.isEmpty() || durationText == "0:00") return 0
        val parts = durationText.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }

    private fun selectBestArtistCandidate(query: String, candidates: List<Song>): Song? {
        if (candidates.isEmpty()) return null
        val normalizedQuery = query.lowercase().replace(Regex("\\s+"), " ").trim()

        return candidates.maxByOrNull { candidate ->
            var score = 0
            val title = candidate.title.lowercase().replace(Regex("\\s+"), " ").trim()
            if (title == normalizedQuery) score += 10
            if (title.contains(normalizedQuery) || normalizedQuery.contains(title)) score += 6
            if (!candidate.thumbnailUrl.isNullOrBlank()) score += 2
            if (!extractSubscriberSnippet(candidate.artist).isNullOrBlank()) score += 3
            score
        }
    }

    private fun selectBestLooseArtistCandidate(query: String, candidates: List<Song>): Song? {
        if (candidates.isEmpty()) return null
        val normalizedQuery = normalizeArtistToken(query)
        if (normalizedQuery.isBlank()) return null

        return candidates
            .filter { candidate ->
                val candidateId = candidate.id.trim()
                candidateId.isNotEmpty() && !isLikelyVideoId(candidateId)
            }
            .maxByOrNull { candidate ->
                var score = 0
                val title = normalizeArtistToken(candidate.title)
                val subtitle = normalizeArtistToken(candidate.artist)
                val id = candidate.id.trim()

                if (title == normalizedQuery) score += 14
                if (title.contains(normalizedQuery) || normalizedQuery.contains(title)) score += 10
                if (subtitle.contains(normalizedQuery)) score += 4
                if (id.startsWith("UC")) score += 8
                if (id.startsWith("MPLA")) score += 4
                if (!extractSubscriberSnippet(candidate.artist).isNullOrBlank()) score += 5
                if (candidate.contentType == ContentType.ARTIST) score += 6
                if (candidate.contentType == ContentType.SONG || candidate.contentType == ContentType.VIDEO) score -= 5
                if (candidate.title.isBlank()) score -= 10
                score
            }
    }

    private fun normalizeArtistBrowseId(rawId: String): String? {
        val cleaned = rawId.trim()
        if (cleaned.isBlank()) return null
        if (isLikelyVideoId(cleaned)) return null
        if (cleaned.startsWith("VL")) return null
        return cleaned
    }

    private fun isChannelBrowseId(browseId: String?): Boolean {
        return !browseId.isNullOrBlank() && browseId.startsWith("UC")
    }

    private fun normalizeArtistToken(value: String): String {
        return value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractArtistTitleFromBrowse(root: JSONObject): String? {
        val header = root.optJSONObject("header") ?: return null
        val candidates = listOf(
            extractText(
                header.optJSONObject("musicImmersiveHeaderRenderer")
                    ?.optJSONObject("title")
            ),
            extractText(
                header.optJSONObject("musicVisualHeaderRenderer")
                    ?.optJSONObject("title")
            ),
            extractText(
                header.optJSONObject("musicDetailHeaderRenderer")
                    ?.optJSONObject("title")
            ),
            extractText(
                header.optJSONObject("musicResponsiveHeaderRenderer")
                    ?.optJSONObject("title")
            ),
            extractText(
                header.optJSONObject("c4TabbedHeaderRenderer")
                    ?.optJSONObject("title")
            )
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun parseArtistSectionsFromBrowse(
        root: JSONObject,
        fallbackArtistName: String
    ): List<ArtistSectionInfo> {
        val sections = mutableListOf<ArtistSectionInfo>()
        val seen = mutableSetOf<String>()

        collectArtistSectionContainers(root).forEach { section ->
            val parsed = when {
                section.has("musicShelfRenderer") -> parseArtistShelfSection(
                    renderer = section.optJSONObject("musicShelfRenderer"),
                    fallbackArtistName = fallbackArtistName
                )
                section.has("musicCarouselShelfRenderer") -> parseArtistCarouselSection(
                    renderer = section.optJSONObject("musicCarouselShelfRenderer"),
                    fallbackArtistName = fallbackArtistName
                )
                section.has("gridRenderer") -> parseArtistGridSection(
                    renderer = section.optJSONObject("gridRenderer"),
                    fallbackArtistName = fallbackArtistName
                )
                section.has("carouselRenderer") -> parseArtistCarouselRendererSection(
                    renderer = section.optJSONObject("carouselRenderer"),
                    fallbackArtistName = fallbackArtistName
                )
                else -> null
            } ?: return@forEach

            if (parsed.items.isEmpty()) return@forEach
            val dedupedItems = parsed.items.distinctBy { it.id }
            val key = buildString {
                append(parsed.type.name)
                append("|")
                append(parsed.title.lowercase())
                append("|")
                append(parsed.browseId.orEmpty())
            }
            if (seen.add(key)) {
                sections += parsed.copy(items = dedupedItems)
            }
        }

        return sections
    }

    private fun collectArtistSectionContainers(root: JSONObject): List<JSONObject> {
        val output = mutableListOf<JSONObject>()
        val seenHashes = mutableSetOf<Int>()

        fun append(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val hash = item.toString().hashCode()
                if (seenHashes.add(hash)) output += item
            }
        }

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
        if (tabs != null) {
            for (i in 0 until tabs.length()) {
                val sectionList = tabs.optJSONObject(i)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                append(sectionList)
            }
        }

        append(
            root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
        )

        append(
            root.optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
        )

        append(
            root.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
        )

        return output
    }

    private fun parseArtistShelfSection(
        renderer: JSONObject?,
        fallbackArtistName: String
    ): ArtistSectionInfo? {
        if (renderer == null) return null
        val title = extractText(renderer.optJSONObject("title")).trim()
        val browseId = extractSectionBrowseId(renderer)
        val moreEndpoint = extractSectionParams(renderer)
        val items = mutableListOf<Song>()
        val contents = renderer.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                parseArtistSectionItem(
                    item = item,
                    fallbackArtistName = fallbackArtistName,
                    sectionTitle = title
                )?.let(items::add)
            }
        }

        if (items.isEmpty()) return null
        val type = classifyArtistSectionType(title, items)
        return ArtistSectionInfo(
            type = type,
            title = title,
            browseId = browseId,
            moreEndpoint = moreEndpoint,
            items = items
        )
    }

    private fun parseArtistCarouselSection(
        renderer: JSONObject?,
        fallbackArtistName: String
    ): ArtistSectionInfo? {
        if (renderer == null) return null
        val title = extractText(
            renderer.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")
        ).trim()
        val browseId = extractSectionBrowseId(renderer)
        val moreEndpoint = extractSectionParams(renderer)
        val items = mutableListOf<Song>()
        val contents = renderer.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                parseArtistSectionItem(
                    item = item,
                    fallbackArtistName = fallbackArtistName,
                    sectionTitle = title
                )?.let(items::add)
            }
        }

        if (items.isEmpty()) return null
        val type = classifyArtistSectionType(title, items)
        return ArtistSectionInfo(
            type = type,
            title = title,
            browseId = browseId,
            moreEndpoint = moreEndpoint,
            items = items
        )
    }

    private fun parseArtistGridSection(
        renderer: JSONObject?,
        fallbackArtistName: String
    ): ArtistSectionInfo? {
        if (renderer == null) return null
        val title = extractText(renderer.optJSONObject("title")).trim()
        val browseId = extractSectionBrowseId(renderer)
        val moreEndpoint = extractSectionParams(renderer)
        val items = mutableListOf<Song>()
        val entries = renderer.optJSONArray("items") ?: renderer.optJSONArray("contents")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                parseArtistSectionItem(
                    item = item,
                    fallbackArtistName = fallbackArtistName,
                    sectionTitle = title
                )?.let(items::add)
            }
        }

        if (items.isEmpty()) return null
        val type = classifyArtistSectionType(title, items)
        return ArtistSectionInfo(
            type = type,
            title = title,
            browseId = browseId,
            moreEndpoint = moreEndpoint,
            items = items
        )
    }

    private fun parseArtistCarouselRendererSection(
        renderer: JSONObject?,
        fallbackArtistName: String
    ): ArtistSectionInfo? {
        if (renderer == null) return null
        val title = extractText(renderer.optJSONObject("header")).trim()
        val browseId = extractSectionBrowseId(renderer)
        val moreEndpoint = extractSectionParams(renderer)
        val items = mutableListOf<Song>()
        val entries = renderer.optJSONArray("items") ?: renderer.optJSONArray("contents")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                parseArtistSectionItem(
                    item = item,
                    fallbackArtistName = fallbackArtistName,
                    sectionTitle = title
                )?.let(items::add)
            }
        }

        if (items.isEmpty()) return null
        val type = classifyArtistSectionType(title, items)
        return ArtistSectionInfo(
            type = type,
            title = title,
            browseId = browseId,
            moreEndpoint = moreEndpoint,
            items = items
        )
    }

    private fun parseArtistSectionItem(
        item: JSONObject,
        fallbackArtistName: String,
        sectionTitle: String
    ): Song? {
        item.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
            return parseArtistSectionResponsiveItem(
                renderer = renderer,
                fallbackArtistName = fallbackArtistName,
                sectionTitle = sectionTitle
            )
        }
        item.optJSONObject("musicTwoRowItemRenderer")?.let { renderer ->
            return parseArtistSectionTwoRowItem(
                renderer = renderer,
                fallbackArtistName = fallbackArtistName,
                sectionTitle = sectionTitle
            )
        }
        return null
    }

    private fun parseArtistSectionResponsiveItem(
        renderer: JSONObject,
        fallbackArtistName: String,
        sectionTitle: String
    ): Song? {
        parseAlbumFromResponsiveItem(
            renderer = renderer,
            fallbackArtistName = fallbackArtistName,
            shelfTitle = sectionTitle
        )?.let { return it }

        val endpoint = renderer.optJSONObject("navigationEndpoint")
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")

        val browseId = endpoint.extractBrowseId()
        val watchId = endpoint?.optJSONObject("watchEndpoint")?.optString("videoId", "").orEmpty().trim()
        val playlistVideoId = renderer.optJSONObject("playlistItemData")?.optString("videoId", "").orEmpty().trim()
        val resolvedId = when {
            watchId.isNotEmpty() -> watchId
            playlistVideoId.isNotEmpty() -> playlistVideoId
            !browseId.isNullOrBlank() -> browseId
            else -> ""
        }
        if (resolvedId.isBlank()) return null

        val flexColumns = renderer.optJSONArray("flexColumns")
        val title = extractMusicText(flexColumns?.optJSONObject(0)).ifBlank {
            extractText(renderer.optJSONObject("text"))
        }.trim()
        if (title.isBlank()) return null

        val subtitleRuns = flexColumns
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        val subtitleRefs = extractArtistAndAlbumRefs(subtitleRuns)
        val subtitle = subtitleRefs.subtitle.ifBlank { parseArtistSectionSubtitle(subtitleRuns) }
        val pageType = endpoint.extractBrowsePageType().lowercase()
        val contentType = parseArtistSectionContentType(
            id = resolvedId,
            pageType = pageType,
            sectionTitle = sectionTitle,
            subtitle = subtitle
        )

        val durationText = renderer.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text", "0:00")
            .orEmpty()

        val thumbnailUrl = extractHighestQualityThumbnail(
            thumbnails = renderer.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            fallback = if (isLikelyVideoId(resolvedId)) {
                "https://i.ytimg.com/vi/$resolvedId/maxresdefault.jpg"
            } else {
                ""
            }
        )
        val artist = subtitleRefs.artists
            .map { it.name }
            .joinToString(", ")
            .ifBlank { parseArtistFromSubtitle(subtitle) }
            .ifBlank {
                fallbackArtistName.ifBlank { title }
            }
        val artistBrowseId = subtitleRefs.artists.firstOrNull()?.browseId
        val albumName = subtitleRefs.album?.name
        val albumBrowseId = subtitleRefs.album?.browseId

        val normalizedThumb = if (isLikelyVideoId(resolvedId)) {
            upgradeThumbQuality(thumbnailUrl, resolvedId)
        } else {
            normalizeAlbumThumbnail(thumbnailUrl, resolvedId)
        }

        return if (contentType == ContentType.ALBUM) {
            Song(
                id = resolvedId,
                title = title,
                artist = artist,
                artistId = artistBrowseId,
                album = title,
                albumId = resolvedId,
                duration = 0,
                thumbnailUrl = normalizedThumb,
                year = parseYearToken(subtitle),
                category = "Album",
                contentType = ContentType.ALBUM
            )
        } else {
            Song(
                id = resolvedId,
                title = title,
                artist = artist,
                artistId = artistBrowseId,
                album = albumName,
                albumId = albumBrowseId,
                duration = parseDuration(durationText),
                thumbnailUrl = normalizedThumb,
                category = "Artist",
                contentType = contentType
            )
        }
    }

    private fun parseArtistSectionTwoRowItem(
        renderer: JSONObject,
        fallbackArtistName: String,
        sectionTitle: String
    ): Song? {
        parseAlbumFromTwoRowItem(
            renderer = renderer,
            fallbackArtistName = fallbackArtistName,
            shelfTitle = sectionTitle
        )?.let { return it }

        val endpoint = renderer.optJSONObject("navigationEndpoint")
        val browseId = endpoint.extractBrowseId()
        val watchId = endpoint?.optJSONObject("watchEndpoint")?.optString("videoId", "").orEmpty().trim()
        val resolvedId = when {
            watchId.isNotEmpty() -> watchId
            !browseId.isNullOrBlank() -> browseId
            else -> ""
        }
        if (resolvedId.isBlank()) return null

        val title = extractText(renderer.optJSONObject("title")).trim()
        if (title.isBlank()) return null
        val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
        val subtitleRefs = extractArtistAndAlbumRefs(subtitleRuns)
        val subtitle = subtitleRefs.subtitle.ifBlank {
            extractText(renderer.optJSONObject("subtitle")).trim()
        }
        val pageType = endpoint.extractBrowsePageType().lowercase()
        val contentType = parseArtistSectionContentType(
            id = resolvedId,
            pageType = pageType,
            sectionTitle = sectionTitle,
            subtitle = subtitle
        )

        val thumbnailUrl = extractHighestQualityThumbnail(
            thumbnails = renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            fallback = if (isLikelyVideoId(resolvedId)) {
                "https://i.ytimg.com/vi/$resolvedId/maxresdefault.jpg"
            } else {
                ""
            }
        )
        val artist = subtitleRefs.artists
            .map { it.name }
            .joinToString(", ")
            .ifBlank { parseArtistFromSubtitle(subtitle) }
            .ifBlank {
                fallbackArtistName.ifBlank { title }
            }
        val artistBrowseId = subtitleRefs.artists.firstOrNull()?.browseId
        val albumName = subtitleRefs.album?.name
        val albumBrowseId = subtitleRefs.album?.browseId
        val normalizedThumb = if (isLikelyVideoId(resolvedId)) {
            upgradeThumbQuality(thumbnailUrl, resolvedId)
        } else {
            normalizeAlbumThumbnail(thumbnailUrl, resolvedId)
        }

        return Song(
            id = resolvedId,
            title = title,
            artist = artist,
            artistId = artistBrowseId,
            album = albumName,
            albumId = albumBrowseId,
            duration = parseDuration(subtitle),
            thumbnailUrl = normalizedThumb,
            category = "Artist",
            contentType = contentType
        )
    }

    private data class ArtistRunRef(
        val name: String,
        val browseId: String?
    )

    private data class AlbumRunRef(
        val name: String,
        val browseId: String?
    )

    private data class ArtistSubtitleRefs(
        val artists: List<ArtistRunRef> = emptyList(),
        val album: AlbumRunRef? = null,
        val subtitle: String = ""
    )

    private fun extractArtistAndAlbumRefs(subtitleRuns: JSONArray?): ArtistSubtitleRefs {
        if (subtitleRuns == null || subtitleRuns.length() == 0) {
            return ArtistSubtitleRefs()
        }

        val artists = mutableListOf<ArtistRunRef>()
        var album: AlbumRunRef? = null
        val subtitleBuilder = StringBuilder()

        for (i in 0 until subtitleRuns.length()) {
            val run = subtitleRuns.optJSONObject(i) ?: continue
            val text = run.optString("text", "").trim()
            subtitleBuilder.append(text)
            if (text.isBlank() || text == "•" || text == "·" || text == "|") continue

            val browseEndpoint = run.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
            val browseId = browseEndpoint?.optString("browseId", "")?.trim().orEmpty()
            val pageType = browseEndpoint
                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType", "")
                ?.lowercase()
                .orEmpty()

            val isArtist = pageType.contains("artist") ||
                pageType.contains("user_channel") ||
                browseId.startsWith("UC")
            val isAlbum = pageType.contains("album") ||
                browseId.startsWith("MPRE") ||
                browseId.startsWith("MPLA")

            when {
                isArtist -> artists += ArtistRunRef(
                    name = text,
                    browseId = browseId.takeIf { it.isNotBlank() }
                )
                isAlbum && album == null -> album = AlbumRunRef(
                    name = text,
                    browseId = browseId.takeIf { it.isNotBlank() }
                )
            }
        }

        return ArtistSubtitleRefs(
            artists = artists.distinctBy { it.browseId to it.name },
            album = album,
            subtitle = subtitleBuilder.toString().trim()
        )
    }

    private fun parseArtistSectionSubtitle(subtitleRuns: JSONArray?): String {
        if (subtitleRuns == null) return ""
        return buildString {
            for (i in 0 until subtitleRuns.length()) {
                append(subtitleRuns.optJSONObject(i)?.optString("text", ""))
            }
        }.trim()
    }

    private fun parseArtistSectionContentType(
        id: String,
        pageType: String,
        sectionTitle: String,
        subtitle: String
    ): ContentType {
        val lowerSection = sectionTitle.lowercase()
        val lowerSubtitle = subtitle.lowercase()

        if (pageType.contains("music_page_type_artist")) return ContentType.ARTIST
        if (pageType.contains("music_page_type_album")) return ContentType.ALBUM
        if (pageType.contains("music_page_type_playlist")) return ContentType.PLAYLIST
        if (
            pageType.contains("music_page_type_video") ||
            pageType.contains("non_music_audio_track_page")
        ) {
            return ContentType.VIDEO
        }

        if (id.startsWith("UC")) return ContentType.ARTIST
        if (id.startsWith("MPRE") || id.startsWith("MPLA")) return ContentType.ALBUM
        if (id.startsWith("VL") || id.startsWith("PL") || id.startsWith("RD")) return ContentType.PLAYLIST

        if (
            lowerSection.contains("related") ||
            lowerSection.contains("fans might also like") ||
            lowerSection.contains("fans also like") ||
            lowerSection.contains("fans also")
        ) {
            return ContentType.ARTIST
        }
        if (lowerSection.contains("featured on")) return ContentType.PLAYLIST
        if (lowerSection.contains("album") || lowerSection.contains("single") || lowerSection.contains("ep")) {
            return ContentType.ALBUM
        }
        if (lowerSection.contains("video")) return ContentType.VIDEO

        if (lowerSubtitle.contains("artist")) return ContentType.ARTIST
        if (lowerSubtitle.contains("album") || lowerSubtitle.contains("single") || lowerSubtitle.contains("ep")) {
            return ContentType.ALBUM
        }
        if (lowerSubtitle.contains("playlist")) return ContentType.PLAYLIST
        if (lowerSubtitle.contains("video")) return ContentType.VIDEO

        return if (isLikelyVideoId(id)) ContentType.SONG else ContentType.UNKNOWN
    }

    private fun classifyArtistSectionType(title: String, items: List<Song>): ArtistSectionType {
        val normalizedTitle = title.lowercase()
            .replace("&", " and ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun String.containsAny(keywords: List<String>): Boolean {
            return keywords.any { keyword -> contains(keyword) }
        }

        val songsKeywords = listOf("songs", "top songs", "popular songs", "노래", "曲", "canciones")
        val albumsKeywords = listOf("album", "albums", "앨범", "アルバム", "álbum")
        val singlesKeywords = listOf("single", "singles", "ep", "싱글", "シングル", "sencillo")
        val videosKeywords = listOf("video", "videos", "동영상", "動画", "vídeo")
        val featuredOnKeywords = listOf("featured on", "참여", "フィーチャー")
        val relatedArtistsKeywords = listOf(
            "related artist",
            "related",
            "fans might also like",
            "fans also like",
            "fans also",
            "관련 아티스트",
            "似ているアーティスト"
        )
        val playlistsKeywords = listOf("playlist", "playlists", "재생목록", "プレイリスト", "lista")

        if (normalizedTitle.containsAny(songsKeywords)) {
            return ArtistSectionType.TOP_SONGS
        }
        if (normalizedTitle.containsAny(featuredOnKeywords)) {
            return ArtistSectionType.FEATURED_ON
        }
        if (normalizedTitle.containsAny(relatedArtistsKeywords)) {
            return ArtistSectionType.RELATED_ARTISTS
        }
        if (normalizedTitle.containsAny(singlesKeywords)) {
            return ArtistSectionType.SINGLES
        }
        if (normalizedTitle.containsAny(albumsKeywords)) {
            return ArtistSectionType.ALBUMS
        }
        if (normalizedTitle.containsAny(videosKeywords)) {
            return ArtistSectionType.VIDEOS
        }
        if (normalizedTitle.containsAny(playlistsKeywords)) {
            return ArtistSectionType.FEATURED_ON
        }

        val dominantType = items.groupingBy { it.contentType }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        return when (dominantType) {
            ContentType.ARTIST -> ArtistSectionType.RELATED_ARTISTS
            ContentType.ALBUM -> ArtistSectionType.ALBUMS
            ContentType.PLAYLIST -> ArtistSectionType.FEATURED_ON
            ContentType.VIDEO -> ArtistSectionType.VIDEOS
            ContentType.SONG, ContentType.UNKNOWN -> ArtistSectionType.TOP_SONGS
            else -> ArtistSectionType.UNKNOWN
        }
    }

    private fun extractSectionBrowseId(renderer: JSONObject): String? {
        renderer.optJSONObject("bottomEndpoint").extractBrowseId()?.let { return it }

        val header = renderer.optJSONObject("header")
        val basicHeader = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        basicHeader?.optJSONObject("moreContentButton")
            ?.optJSONObject("buttonRenderer")
            ?.optJSONObject("navigationEndpoint")
            .extractBrowseId()
            ?.let { return it }
        basicHeader?.optJSONObject("moreContentButton")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            .extractBrowseId()
            ?.let { return it }

        val titleCandidates = listOf(
            renderer.optJSONObject("title"),
            basicHeader?.optJSONObject("title"),
            header?.optJSONObject("title"),
            header?.optJSONObject("musicShelfHeaderRenderer")?.optJSONObject("title")
        )
        for (candidate in titleCandidates) {
            extractBrowseIdFromTextRuns(candidate)?.let { return it }
        }

        return null
    }

    private fun extractBrowseIdFromTextRuns(textObject: JSONObject?): String? {
        val runs = textObject?.optJSONArray("runs") ?: return null
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val browseId = run.optJSONObject("navigationEndpoint")
                .extractBrowseId()
                ?.takeIf { it.isNotBlank() }
            if (browseId != null) return browseId
        }
        return null
    }

    private fun extractSectionParams(renderer: JSONObject): String? {
        val endpointCandidates = mutableListOf<JSONObject>()

        fun appendEndpoint(endpoint: JSONObject?) {
            if (endpoint != null) endpointCandidates += endpoint
        }

        appendEndpoint(renderer.optJSONObject("bottomEndpoint"))

        val header = renderer.optJSONObject("header")
        val basicHeader = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        appendEndpoint(
            basicHeader?.optJSONObject("moreContentButton")
                ?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")
        )
        appendEndpoint(
            basicHeader?.optJSONObject("moreContentButton")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
        )

        val shelfHeader = header?.optJSONObject("musicShelfHeaderRenderer")
        appendEndpoint(shelfHeader?.optJSONObject("navigationEndpoint"))
        appendEndpoint(
            shelfHeader?.optJSONObject("moreContentButton")
                ?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")
        )
        appendEndpoint(
            shelfHeader?.optJSONObject("moreContentButton")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
        )

        endpointCandidates.forEach { endpoint ->
            extractEndpointMoreToken(endpoint)?.let { return it }
        }

        val titleCandidates = listOf(
            renderer.optJSONObject("title"),
            basicHeader?.optJSONObject("title"),
            header?.optJSONObject("title"),
            shelfHeader?.optJSONObject("title")
        )
        for (candidate in titleCandidates) {
            val runs = candidate?.optJSONArray("runs") ?: continue
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                extractEndpointMoreToken(run.optJSONObject("navigationEndpoint"))?.let { return it }
            }
        }
        return extractFirstContinuationToken(renderer)?.takeIf { it.isNotBlank() }
    }

    private fun extractEndpointMoreToken(endpoint: JSONObject?): String? {
        if (endpoint == null) return null

        endpoint.optJSONObject("browseEndpoint")
            ?.optString("params", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        endpoint.optJSONObject("watchEndpoint")
            ?.optString("params", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        endpoint.optString("continuation", "")
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        return extractFirstContinuationToken(endpoint)?.takeIf { it.isNotBlank() }
    }

    private fun isPlayableArtistSong(song: Song): Boolean {
        if (song.id.isBlank() || !isLikelyVideoId(song.id)) return false
        return when (song.contentType) {
            ContentType.SONG,
            ContentType.VIDEO,
            ContentType.UNKNOWN -> true
            else -> false
        }
    }

    private fun parseArtistContinuationBySectionType(
        root: JSONObject,
        sectionType: ArtistSectionType
    ): List<Song> {
        val sectionTitle = when (sectionType) {
            ArtistSectionType.TOP_SONGS -> "Songs"
            ArtistSectionType.ALBUMS -> "Albums"
            ArtistSectionType.SINGLES -> "Singles"
            ArtistSectionType.VIDEOS -> "Videos"
            ArtistSectionType.FEATURED_ON -> "Featured on"
            ArtistSectionType.RELATED_ARTISTS -> "Fans might also like"
            ArtistSectionType.UNKNOWN -> ""
        }
        if (sectionTitle.isEmpty()) return emptyList()

        val items = mutableListOf<Song>()
        val shelfContents = root.optJSONObject("continuationContents")
            ?.optJSONObject("musicShelfContinuation")
            ?.optJSONArray("contents")
        if (shelfContents != null) {
            for (i in 0 until shelfContents.length()) {
                val item = shelfContents.optJSONObject(i) ?: continue
                parseArtistSectionItem(
                    item = item,
                    fallbackArtistName = "",
                    sectionTitle = sectionTitle
                )?.let(items::add)
            }
        }

        val commands = root.optJSONArray("onResponseReceivedCommands")
        if (commands != null) {
            for (i in 0 until commands.length()) {
                val continuationItems = commands.optJSONObject(i)
                    ?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")
                    ?: continue
                for (j in 0 until continuationItems.length()) {
                    val item = continuationItems.optJSONObject(j) ?: continue
                    parseArtistSectionItem(
                        item = item,
                        fallbackArtistName = "",
                        sectionTitle = sectionTitle
                    )?.let(items::add)
                }
            }
        }
        return items.distinctBy { it.id }
    }

    private fun parseArtistContinuationSongs(root: JSONObject): List<Song> {
        val songs = mutableListOf<Song>()

        val shelfContents = root.optJSONObject("continuationContents")
            ?.optJSONObject("musicShelfContinuation")
            ?.optJSONArray("contents")
        if (shelfContents != null) {
            for (i in 0 until shelfContents.length()) {
                val item = shelfContents.optJSONObject(i) ?: continue
                parseArtistSectionItem(
                    item = item,
                    fallbackArtistName = "",
                    sectionTitle = "Top songs"
                )?.let(songs::add)
            }
        }

        val commands = root.optJSONArray("onResponseReceivedCommands")
        if (commands != null) {
            for (i in 0 until commands.length()) {
                val continuationItems = commands.optJSONObject(i)
                    ?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")
                    ?: continue
                for (j in 0 until continuationItems.length()) {
                    val item = continuationItems.optJSONObject(j) ?: continue
                    parseArtistSectionItem(
                        item = item,
                        fallbackArtistName = "",
                        sectionTitle = "Top songs"
                    )?.let(songs::add)
                }
            }
        }

        return songs.distinctBy { it.id }
    }

    private fun parseArtistContinuationAlbums(
        root: JSONObject,
        fallbackArtistName: String
    ): List<Song> {
        val albums = mutableListOf<Song>()

        val shelfContents = root.optJSONObject("continuationContents")
            ?.optJSONObject("musicShelfContinuation")
            ?.optJSONArray("contents")
        if (shelfContents != null) {
            for (i in 0 until shelfContents.length()) {
                val item = shelfContents.optJSONObject(i) ?: continue
                parseAlbumFromBrowseItem(
                    item = item,
                    fallbackArtistName = fallbackArtistName,
                    shelfTitle = "Albums"
                )?.let(albums::add)
            }
        }

        return albums
            .filter { album ->
                album.id.isNotBlank() &&
                    !isLikelyVideoId(album.id) &&
                    album.contentType == ContentType.ALBUM
            }
            .distinctBy { it.id }
    }

    private fun extractFirstContinuationToken(node: Any?): String? {
        return when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationCommand")
                    ?.optString("token", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: node.optJSONObject("nextContinuationData")
                        ?.optString("continuation", "")
                        ?.takeIf { it.isNotBlank() }
                    ?: node.optJSONObject("reloadContinuationData")
                        ?.optString("continuation", "")
                        ?.takeIf { it.isNotBlank() }
                    ?: run {
                        var token: String? = null
                        val keys = node.keys()
                        while (keys.hasNext() && token.isNullOrBlank()) {
                            token = extractFirstContinuationToken(node.opt(keys.next()))
                        }
                        token
                    }
            }
            is JSONArray -> {
                var token: String? = null
                for (i in 0 until node.length()) {
                    token = extractFirstContinuationToken(node.opt(i))
                    if (!token.isNullOrBlank()) break
                }
                token
            }
            else -> null
        }
    }

    private fun extractArtistAlbumsFromSearch(
        artistName: String,
        candidates: List<Song>
    ): List<Song> {
        if (candidates.isEmpty()) return emptyList()
        val normalizedArtist = normalizeArtistToken(artistName)
        return candidates
            .asSequence()
            .filter { candidate ->
                candidate.contentType == ContentType.ALBUM &&
                    candidate.id.isNotBlank() &&
                    !isLikelyVideoId(candidate.id)
            }
            .filter { candidate ->
                val albumArtist = normalizeArtistToken(candidate.artist)
                albumArtist.contains(normalizedArtist) || normalizedArtist.contains(albumArtist)
            }
            .map { candidate ->
                candidate.copy(
                    albumId = candidate.id,
                    contentType = ContentType.ALBUM
                )
            }
            .distinctBy { it.id }
            .take(20)
            .toList()
    }

    private fun extractArtistAlbumsFromBrowse(
        root: JSONObject,
        fallbackArtistName: String
    ): List<Song> {
        val albums = linkedMapOf<String, Song>()
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue

            val carousel = section.optJSONObject("musicCarouselShelfRenderer")
            val carouselTitle = extractText(
                carousel
                    ?.optJSONObject("header")
                    ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")
            )
            val carouselContents = carousel?.optJSONArray("contents")
            if (carouselContents != null) {
                for (j in 0 until carouselContents.length()) {
                    val item = carouselContents.optJSONObject(j) ?: continue
                    val album = parseAlbumFromBrowseItem(
                        item = item,
                        fallbackArtistName = fallbackArtistName,
                        shelfTitle = carouselTitle
                    ) ?: continue
                    albums.putIfAbsent(album.id, album)
                }
            }

            val shelf = section.optJSONObject("musicShelfRenderer")
            val shelfTitle = extractText(
                shelf
                    ?.optJSONObject("title")
            )
            val shelfContents = shelf?.optJSONArray("contents")
            if (shelfContents != null) {
                for (j in 0 until shelfContents.length()) {
                    val item = shelfContents.optJSONObject(j) ?: continue
                    val album = parseAlbumFromBrowseItem(
                        item = item,
                        fallbackArtistName = fallbackArtistName,
                        shelfTitle = shelfTitle
                    ) ?: continue
                    albums.putIfAbsent(album.id, album)
                }
            }
        }

        return albums.values.take(40)
    }

    private fun parseAlbumFromBrowseItem(
        item: JSONObject,
        fallbackArtistName: String,
        shelfTitle: String
    ): Song? {
        parseAlbumFromTwoRowItem(
            renderer = item.optJSONObject("musicTwoRowItemRenderer"),
            fallbackArtistName = fallbackArtistName,
            shelfTitle = shelfTitle
        )?.let { return it }

        parseAlbumFromResponsiveItem(
            renderer = item.optJSONObject("musicResponsiveListItemRenderer"),
            fallbackArtistName = fallbackArtistName,
            shelfTitle = shelfTitle
        )?.let { return it }

        return null
    }

    private fun parseAlbumFromTwoRowItem(
        renderer: JSONObject?,
        fallbackArtistName: String,
        shelfTitle: String
    ): Song? {
        if (renderer == null) return null
        val endpoint = renderer.optJSONObject("navigationEndpoint")
        val browseId = endpoint.extractBrowseId() ?: return null
        val pageType = endpoint.extractBrowsePageType().lowercase()
        val subtitle = extractText(renderer.optJSONObject("subtitle"))
        val isAlbum = browseId.startsWith("MPRE") ||
            pageType.contains("music_page_type_album") ||
            subtitle.lowercase().contains("album") ||
            shelfTitle.lowercase().contains("album")
        if (!isAlbum) return null

        val title = extractText(renderer.optJSONObject("title")).ifBlank { return null }
        val thumbnails = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumb = extractHighestQualityThumbnail(thumbnails, "")
        
        val subtitleRuns = renderer.optJSONObject("subtitle")?.optJSONArray("runs")
        val artist = if (subtitleRuns != null && subtitleRuns.length() > 0) {
            com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(subtitleRuns.toRunList()).displayName
        } else {
            parseArtistFromSubtitle(subtitle)
        }.ifBlank { fallbackArtistName }
        
        val year = parseYearToken(subtitle)

        return Song(
            id = browseId,
            title = title,
            artist = artist,
            album = title,
            albumId = browseId,
            duration = 0,
            thumbnailUrl = normalizeAlbumThumbnail(thumb, browseId),
            year = year,
            category = "Album",
            contentType = ContentType.ALBUM
        )
    }

    private fun parseAlbumFromResponsiveItem(
        renderer: JSONObject?,
        fallbackArtistName: String,
        shelfTitle: String
    ): Song? {
        if (renderer == null) return null
        val endpoint = renderer.optJSONObject("navigationEndpoint")
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
        val browseId = endpoint.extractBrowseId() ?: return null
        val pageType = endpoint.extractBrowsePageType().lowercase()

        val flexColumns = renderer.optJSONArray("flexColumns")
        val title = extractMusicText(flexColumns?.optJSONObject(0)).ifBlank { return null }
        val subtitleRuns = flexColumns
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        val subtitle = buildString {
            if (subtitleRuns != null) {
                for (i in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(i)?.optString("text", ""))
                }
            }
        }.trim()

        val isAlbum = browseId.startsWith("MPRE") ||
            pageType.contains("music_page_type_album") ||
            subtitle.lowercase().contains("album") ||
            shelfTitle.lowercase().contains("album")
        if (!isAlbum) return null

        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumb = extractHighestQualityThumbnail(thumbnails, "")
        
        val artist = if (subtitleRuns != null && subtitleRuns.length() > 0) {
            com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(subtitleRuns.toRunList()).displayName
        } else {
            parseArtistFromSubtitle(subtitle)
        }.ifBlank { fallbackArtistName }
        
        val year = parseYearToken(subtitle)

        return Song(
            id = browseId,
            title = title,
            artist = artist,
            album = title,
            albumId = browseId,
            duration = 0,
            thumbnailUrl = normalizeAlbumThumbnail(thumb, browseId),
            year = year,
            category = "Album",
            contentType = ContentType.ALBUM
        )
    }

    private fun JSONObject?.extractBrowseId(): String? {
        if (this == null) return null
        val browseId = this.optJSONObject("browseEndpoint")?.optString("browseId", "").orEmpty().trim()
        return browseId.takeIf { it.isNotBlank() }
    }

    private fun JSONObject?.extractBrowsePageType(): String {
        if (this == null) return ""
        return this.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType", "")
            .orEmpty()
    }

    private fun org.json.JSONArray?.toRunList(): List<com.sonicmusic.app.data.remote.model.Run> {
        if (this == null) return emptyList()
        val list = mutableListOf<com.sonicmusic.app.data.remote.model.Run>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val text = obj.optString("text", "")
            val endpoint = obj.optJSONObject("navigationEndpoint")
            val browseId = endpoint.extractBrowseId()
            val pageType = endpoint.extractBrowsePageType()
            list.add(com.sonicmusic.app.data.remote.model.Run(text, browseId, pageType))
        }
        return list
    }

    private fun parseArtistFromSubtitle(subtitle: String): String {
        if (subtitle.isBlank()) return ""
        return subtitle
            .split(Regex("\\s*[•·|]\\s*"))
            .firstOrNull { token ->
                val trimmed = token.trim()
                trimmed.isNotEmpty() && parseYearToken(trimmed) == null &&
                    !trimmed.equals("album", ignoreCase = true) &&
                    !trimmed.equals("single", ignoreCase = true) &&
                    !trimmed.equals("ep", ignoreCase = true)
            }
            ?.trim()
            .orEmpty()
    }

    private fun parseYearToken(value: String): Int? {
        val match = Regex("\\b(19|20)\\d{2}\\b").find(value) ?: return null
        return match.value.toIntOrNull()
    }

    private fun normalizeAlbumThumbnail(url: String, browseId: String): String {
        if (url.isBlank()) return ""
        return ThumbnailUrlUtils.toHighQuality(url, browseId) ?: url
    }

    private fun isLikelyVideoId(value: String): Boolean {
        return Regex("^[A-Za-z0-9_-]{11}$").matches(value)
    }

    private fun extractSubscriberSnippet(text: String): String? {
        if (text.isBlank()) return null
        val parts = text.split(Regex("\\s*[•·|]\\s*"))
        return parts.firstOrNull { part ->
            val lower = part.lowercase()
            lower.contains("subscriber") ||
                lower.contains("followers") ||
                lower.contains("monthly listener")
        }
    }

    private data class ThumbnailCandidate(
        val url: String,
        val width: Int,
        val height: Int,
        val path: String
    )

    private fun extractBestArtistHeaderImage(root: JSONObject): String? {
        extractKnownArtistHeaderImage(root)?.let { return it }

        val candidates = mutableListOf<ThumbnailCandidate>()
        collectThumbnailCandidates(root, "root", candidates)
        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { thumbnailScore(it) } ?: return null
        return normalizeArtistHeaderImage(best.url)
    }

    private fun extractKnownArtistHeaderImage(root: JSONObject): String? {
        val header = root.optJSONObject("header") ?: return null
        val avatarCandidates = linkedSetOf<String>()
        val headerCandidates = linkedSetOf<String>()

        fun appendThumbArray(thumbnails: JSONArray?, target: MutableSet<String>) {
            val best = extractHighestQualityThumbnail(thumbnails, "")
            if (best.isNotBlank()) target.add(best)
        }

        appendThumbArray(
            header.optJSONObject("c4TabbedHeaderRenderer")
                ?.optJSONObject("avatar")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        appendThumbArray(
            root.optJSONObject("metadata")
                ?.optJSONObject("channelMetadataRenderer")
                ?.optJSONObject("avatar")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicDetailHeaderRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONObject("croppedSquareThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicImmersiveHeaderRenderer")
                ?.optJSONObject("foregroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicVisualHeaderRenderer")
                ?.optJSONObject("foregroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicImmersiveHeaderRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            headerCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicVisualHeaderRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            headerCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicImmersiveHeaderRenderer")
                ?.optJSONObject("backgroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            headerCandidates
        )

        appendThumbArray(
            header.optJSONObject("musicVisualHeaderRenderer")
                ?.optJSONObject("backgroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            headerCandidates
        )

        // musicResponsiveHeaderRenderer (newer YouTube Music format)
        appendThumbArray(
            header.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        // musicResponsiveHeaderRenderer straplineThumbnail (larger image)
        appendThumbArray(
            header.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("straplineThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails"),
            avatarCandidates
        )

        return (avatarCandidates + headerCandidates)
            .asSequence()
            .map(::normalizeArtistHeaderImage)
            .firstOrNull { !it.isNullOrBlank() }
    }

    private fun normalizeArtistHeaderImage(url: String?): String? {
        val cleaned = url?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return null
        val absolute = if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
        return ThumbnailUrlUtils.toHighQuality(absolute) ?: absolute
    }

    private fun collectThumbnailCandidates(
        node: JSONObject,
        path: String,
        output: MutableList<ThumbnailCandidate>
    ) {
        if (node.has("thumbnails")) {
            val thumbs = node.optJSONArray("thumbnails")
            if (thumbs != null) {
                for (i in 0 until thumbs.length()) {
                    val item = thumbs.optJSONObject(i) ?: continue
                    val url = item.optString("url", "").trim()
                    if (url.isBlank()) continue
                    output.add(
                        ThumbnailCandidate(
                            url = url,
                            width = item.optInt("width", 0),
                            height = item.optInt("height", 0),
                            path = path
                        )
                    )
                }
            }
        }

        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val childPath = "$path/$key"
            when (val value = node.opt(key)) {
                is JSONObject -> collectThumbnailCandidates(value, childPath, output)
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.opt(i)
                        if (child is JSONObject) {
                            collectThumbnailCandidates(child, "$childPath[$i]", output)
                        }
                    }
                }
            }
        }
    }

    private fun thumbnailScore(candidate: ThumbnailCandidate): Long {
        val pathLower = candidate.path.lowercase()
        val width = candidate.width.coerceAtLeast(0)
        val height = candidate.height.coerceAtLeast(0)
        val baseArea = if (width > 0 && height > 0) {
            width.toLong() * height.toLong()
        } else {
            estimateThumbnailScore(candidate.url)
        }

        var score = baseArea
        if (pathLower.contains("avatar") || pathLower.contains("channelmetadata")) {
            score += 7_000_000_000L
        }
        if (pathLower.contains("croppedsquare") || pathLower.contains("foregroundthumbnail")) {
            score += 3_500_000_000L
        }
        if (
            pathLower.contains("musicresponsivelistitem") ||
            pathLower.contains("playlistpanel") ||
            pathLower.contains("shelfrenderer")
        ) {
            score -= 4_000_000_000L
        }
        if (
            pathLower.contains("banner") ||
            pathLower.contains("backgroundthumbnail") ||
            pathLower.contains("hero")
        ) {
            score -= 1_500_000_000L
        }
        if (width > 0 && height > 0) {
            val ratio = width.toDouble() / height.toDouble()
            if (ratio in 0.78..1.28) {
                score += 1_800_000_000L
            } else if (ratio > 1.6 || ratio < 0.6) {
                score -= 1_100_000_000L
            }
        }
        return score
    }

    private fun extractArtistDescription(root: JSONObject): String? {
        val header = root.optJSONObject("header")
        
        val descRuns = header?.optJSONObject("musicImmersiveHeaderRenderer")
            ?.optJSONObject("description")
            ?.optJSONArray("runs")
            
        if (descRuns != null && descRuns.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until descRuns.length()) {
                sb.append(descRuns.optJSONObject(i)?.optString("text", ""))
            }
            if (sb.isNotEmpty()) return sb.toString()
        }

        // musicResponsiveHeaderRenderer: description may be nested
        // inside a description shelf within the header itself
        val responsiveDesc = header?.optJSONObject("musicResponsiveHeaderRenderer")
            ?.optJSONObject("description")
        if (responsiveDesc != null) {
            // Direct runs in the description object
            val directRuns = responsiveDesc.optJSONArray("runs")
            if (directRuns != null && directRuns.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until directRuns.length()) {
                    sb.append(directRuns.optJSONObject(i)?.optString("text", ""))
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            // Nested inside musicDescriptionShelfRenderer
            val shelfRuns = responsiveDesc
                .optJSONObject("musicDescriptionShelfRenderer")
                ?.optJSONObject("description")
                ?.optJSONArray("runs")
            if (shelfRuns != null && shelfRuns.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until shelfRuns.length()) {
                    sb.append(shelfRuns.optJSONObject(i)?.optString("text", ""))
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }

        val sections = collectArtistSectionContainers(root)
        for (section in sections) {
            val descRenderer = section.optJSONObject("musicDescriptionShelfRenderer")
            if (descRenderer != null) {
                val runs = descRenderer.optJSONObject("description")?.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until runs.length()) {
                        sb.append(runs.optJSONObject(i)?.optString("text", ""))
                    }
                    if (sb.isNotEmpty()) return sb.toString()
                }
            }
        }
        return null
    }

    private fun extractArtistBannerUrl(root: JSONObject): String? {
        val header = root.optJSONObject("header") ?: return null
        
        val bannerCandidates = mutableListOf<String>()
        
        fun appendThumb(thumbnails: JSONArray?) {
            val url = extractHighestQualityThumbnail(thumbnails, "")
            if (url.isNotBlank()) bannerCandidates.add(url)
        }

        appendThumb(
            header.optJSONObject("musicImmersiveHeaderRenderer")
                ?.optJSONObject("backgroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        )

        appendThumb(
            header.optJSONObject("musicVisualHeaderRenderer")
                ?.optJSONObject("backgroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        )

        // musicResponsiveHeaderRenderer: background image
        appendThumb(
            header.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("backgroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        )
        
        return bannerCandidates.map(::normalizeArtistHeaderImage).firstOrNull()
    }

    private fun extractShufflePlaylistId(root: JSONObject): String? {
        val header = root.optJSONObject("header") ?: return null
        
        val buttonCandidates = listOfNotNull(
            header.optJSONObject("musicImmersiveHeaderRenderer")?.optJSONObject("playButton"),
            header.optJSONObject("musicVisualHeaderRenderer")?.optJSONObject("playButton"),
            header.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("buttons")
                ?.optJSONArray("buttons")
                ?.optJSONObject(0)
        )

        for (button in buttonCandidates) {
            val playlistId = extractButtonWatchPlaylistId(button)
            if (!playlistId.isNullOrBlank()) {
                return playlistId
            }
        }
        return null
    }

    private fun extractRadioPlaylistId(root: JSONObject): String? {
        val header = root.optJSONObject("header") ?: return null

        val buttonCandidates = listOfNotNull(
            header.optJSONObject("musicImmersiveHeaderRenderer")?.optJSONObject("startRadioButton"),
            header.optJSONObject("musicVisualHeaderRenderer")?.optJSONObject("startRadioButton"),
            header.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("buttons")
                ?.optJSONArray("buttons")
                ?.optJSONObject(1)
        )

        for (button in buttonCandidates) {
            val playlistId = extractButtonWatchPlaylistId(button)
            if (!playlistId.isNullOrBlank()) {
                return playlistId
            }
        }
        return null
    }

    private fun extractButtonWatchPlaylistId(button: JSONObject?): String? {
        if (button == null) return null
        val endpoint = button.optJSONObject("buttonRenderer")?.optJSONObject("navigationEndpoint")
            ?: button.optJSONObject("toggleButtonRenderer")?.optJSONObject("defaultNavigationEndpoint")
            ?: button.optJSONObject("musicPlayButtonRenderer")?.optJSONObject("playNavigationEndpoint")

        return endpoint?.optJSONObject("watchEndpoint")
            ?.optString("playlistId", "")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extract subscriber/listener count specifically from the
     * `musicResponsiveHeaderRenderer` header format.
     *
     * In this newer YTM format, subscriber count is stored in
     * `subtitle.runs` (e.g. ["5.17M", " ", "subscribers"]) or in
     * `straplineTextOne.runs`. This method provides a targeted
     * extraction before falling back to the generic deep scan.
     */
    private fun extractResponsiveHeaderSubscribers(root: JSONObject): String? {
        val header = root.optJSONObject("header") ?: return null
        val responsive = header.optJSONObject("musicResponsiveHeaderRenderer") ?: return null

        // Priority 1: subtitle.runs — most common location
        val subtitleRuns = responsive.optJSONObject("subtitle")?.optJSONArray("runs")
        if (subtitleRuns != null && subtitleRuns.length() > 0) {
            val combined = buildString {
                for (i in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }.trim()
            if (combined.isNotBlank() && looksLikeAudienceStats(combined)) {
                return formatArtistStats(combined)
            }
            // Check individual runs for audience stats
            for (i in 0 until subtitleRuns.length()) {
                val runText = subtitleRuns.optJSONObject(i)?.optString("text", "")?.trim() ?: ""
                if (runText.isNotBlank() && looksLikeAudienceStats(runText)) {
                    return formatArtistStats(runText)
                }
            }
        }

        // Priority 2: straplineTextOne.runs
        val straplineRuns = responsive.optJSONObject("straplineTextOne")?.optJSONArray("runs")
        if (straplineRuns != null && straplineRuns.length() > 0) {
            val combined = buildString {
                for (i in 0 until straplineRuns.length()) {
                    append(straplineRuns.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }.trim()
            if (combined.isNotBlank() && looksLikeAudienceStats(combined)) {
                return formatArtistStats(combined)
            }
        }

        // Priority 3: straplineTextTwo (some artists have it here)
        val straplineTwoRuns = responsive.optJSONObject("straplineTextTwo")?.optJSONArray("runs")
        if (straplineTwoRuns != null && straplineTwoRuns.length() > 0) {
            val combined = buildString {
                for (i in 0 until straplineTwoRuns.length()) {
                    append(straplineTwoRuns.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }.trim()
            if (combined.isNotBlank() && looksLikeAudienceStats(combined)) {
                return formatArtistStats(combined)
            }
        }

        return null
    }

    private fun extractArtistSubscribersFromBrowse(root: JSONObject): String? {
        val header = root.optJSONObject("header")

        // ── Step 1: Direct extraction from known header renderers ──
        // These are the definitive locations for subscriber count.
        val headerRenderers = listOfNotNull(
            header?.optJSONObject("musicImmersiveHeaderRenderer"),
            header?.optJSONObject("musicVisualHeaderRenderer"),
            header?.optJSONObject("musicDetailHeaderRenderer"),
            header?.optJSONObject("musicResponsiveHeaderRenderer"),
            header?.optJSONObject("c4TabbedHeaderRenderer")
        )

        // Priority 1: subscriberCountText — the canonical field
        for (hdr in headerRenderers) {
            val subCountObj = hdr.optJSONObject("subscriberCountText")
            if (subCountObj != null) {
                val text = extractText(subCountObj).trim()
                if (text.isNotBlank() && looksLikeAudienceStats(text)) return text
            }
            // Also try subscriptionButton → subscriberCountText (c4TabbedHeader)
            val subButton = hdr.optJSONObject("subscriptionButton")
                ?.optJSONObject("subscribeButtonRenderer")
            val subBtnObj = subButton?.optJSONObject("subscriberCountText")
            if (subBtnObj != null) {
                val text = extractText(subBtnObj).trim()
                if (text.isNotBlank() && looksLikeAudienceStats(text)) return text
            }
            val subBtnStr = subButton?.optString("subscriberCountText", "")?.trim()
            if (!subBtnStr.isNullOrBlank() && looksLikeAudienceStats(subBtnStr)) return subBtnStr
        }

        // Priority 2: subtitle runs from header renderers
        // YouTube Music puts "5.17M subscribers" in subtitle.runs
        for (hdr in headerRenderers) {
            val subtitleObj = hdr.optJSONObject("subtitle")
            if (subtitleObj != null) {
                val runs = subtitleObj.optJSONArray("runs")
                if (runs != null) {
                    val combined = buildString {
                        for (i in 0 until runs.length()) {
                            append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                        }
                    }
                    if (combined.isNotBlank() && looksLikeAudienceStats(combined)) return combined.trim()
                    // Check individual runs
                    for (i in 0 until runs.length()) {
                        val runText = runs.optJSONObject(i)?.optString("text", "")?.trim() ?: ""
                        if (runText.isNotBlank() && looksLikeAudienceStats(runText)) return runText
                    }
                }
                val subtitleText = extractText(subtitleObj).trim()
                if (subtitleText.isNotBlank() && looksLikeAudienceStats(subtitleText)) return subtitleText
            }

            // straplineTextOne / straplineTextTwo / secondSubtitle
            for (key in listOf("straplineTextOne", "straplineTextTwo", "secondSubtitle")) {
                val obj = hdr.optJSONObject(key)
                if (obj != null) {
                    val text = extractText(obj).trim()
                    if (text.isNotBlank() && looksLikeAudienceStats(text)) return text
                }
            }
        }

        // ── Step 2: Narrow keyed search ONLY within the header object ──
        // Avoid scanning the entire JSON which picks up false positives from sections
        if (header != null) {
            val headerSnippets = mutableListOf<String>()
            collectTextByKeys(
                node = header,
                targetKeys = setOf("subscriberCountText", "subscriptionsCountText"),
                output = headerSnippets
            )
            val bestHeader = selectBestArtistStatsSnippet(headerSnippets)
            if (!bestHeader.isNullOrBlank()) return bestHeader
        }

        // ── Step 3: Last resort — broad deep scan for subscriberCountText only ──
        // Do NOT scan for "subtitle" or "description" which yield false positives
        val keyedSnippets = mutableListOf<String>()
        collectTextByKeys(
            node = root,
            targetKeys = setOf("subscriberCountText", "subscriptionsCountText"),
            output = keyedSnippets
        )
        val bestKeyed = selectBestArtistStatsSnippet(keyedSnippets)
        if (!bestKeyed.isNullOrBlank()) return bestKeyed

        // Final fallback
        val snippets = mutableListOf<String>()
        collectTextSnippets(root, snippets)
        return selectBestArtistStatsSnippet(snippets)
    }

    private fun artistStatsScore(value: String): Int {
        if (value.isBlank()) return Int.MIN_VALUE
        val lower = value.lowercase()
        val hasKeyword = lower.contains("subscriber") ||
            lower.contains("follower") ||
            lower.contains("listener") ||
            lower.contains("monthly") ||
            lower.contains("views")
        val hasDigits = value.any { it.isDigit() }
        val compactCount = Regex("\\b\\d+[\\d.,]*\\s*[kmbt]\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
        if (!hasKeyword) {
            if (!compactCount || value.length > 20) return Int.MIN_VALUE
        }
        if (!hasKeyword && !hasDigits) return Int.MIN_VALUE

        var score = 0
        if (lower.contains("subscriber")) score += 12
        if (lower.contains("follower")) score += 10
        if (lower.contains("listener")) score += 10
        if (lower.contains("monthly")) score += 4
        if (lower.contains("views")) score += 2
        if (hasDigits) score += 8
        if (Regex("\\b\\d+[\\d.,]*\\s*[kmbt]\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)) score += 6
        if (value.length in 4..48) score += 2 else score -= 4
        return score
    }

    private fun selectBestArtistStatsSnippet(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val cleaned = candidates
            .asSequence()
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .mapNotNull(::formatArtistStats)
            .distinct()
            .toList()
        if (cleaned.isEmpty()) return null

        val preferred = cleaned.filter(::looksLikeAudienceStats)
        val source = if (preferred.isNotEmpty()) preferred else cleaned
        return source
            .maxByOrNull(::artistStatsScore)
            ?.takeIf { artistStatsScore(it) > 0 }
    }

    private fun looksLikeAudienceStats(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("subscriber") ||
            lower.contains("follower") ||
            lower.contains("listener") ||
            lower.contains("monthly")
    }

    private fun collectTextByKeys(
        node: JSONObject,
        targetKeys: Set<String>,
        output: MutableList<String>
    ) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = node.opt(key)

            if (targetKeys.contains(key)) {
                when (child) {
                    is JSONObject -> {
                        val text = extractText(child).trim()
                        if (text.isNotEmpty()) output.add(text)
                        collectTextSnippets(child, output)
                    }
                    is JSONArray -> {
                        for (i in 0 until child.length()) {
                            val item = child.opt(i)
                            when (item) {
                                is JSONObject -> {
                                    val text = extractText(item).trim()
                                    if (text.isNotEmpty()) output.add(text)
                                    collectTextSnippets(item, output)
                                }
                                is String -> if (item.isNotBlank()) output.add(item.trim())
                            }
                        }
                    }
                    is String -> if (child.isNotBlank()) output.add(child.trim())
                }
            }

            when (child) {
                is JSONObject -> collectTextByKeys(child, targetKeys, output)
                is JSONArray -> {
                    for (i in 0 until child.length()) {
                        val item = child.opt(i)
                        if (item is JSONObject) {
                            collectTextByKeys(item, targetKeys, output)
                        }
                    }
                }
            }
        }
    }

    private data class ChannelProfileInfo(
        val imageUrl: String? = null,
        val subscribersText: String? = null
    )

    private suspend fun fetchChannelProfileFromYouTube(
        browseId: String,
        language: String,
        region: String
    ): Result<ChannelProfileInfo> = withContext(Dispatchers.IO) {
        try {
            val requestBody = Innertube.androidBody(language, region) {
                put("browseId", browseId)
            }

            val request = Innertube.youtubeAndroidPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("YouTube channel browse failed: ${response.code}"))
            }

            val raw = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty YouTube channel browse response"))
            val json = JSONObject(raw)

            val image = extractChannelAvatarFromBrowse(json)
            val subscribers = extractChannelSubscribersFromBrowse(json)
            Result.success(
                ChannelProfileInfo(
                    imageUrl = image,
                    subscribersText = subscribers
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractChannelAvatarFromBrowse(root: JSONObject): String? {
        val header = root.optJSONObject("header")
        val thumbs = header
            ?.optJSONObject("c4TabbedHeaderRenderer")
            ?.optJSONObject("avatar")
            ?.optJSONArray("thumbnails")
            ?: root.optJSONObject("metadata")
                ?.optJSONObject("channelMetadataRenderer")
                ?.optJSONObject("avatar")
                ?.optJSONArray("thumbnails")
        val best = extractHighestQualityThumbnail(thumbs, "")
        return normalizeArtistHeaderImage(best)
    }

    private fun extractChannelSubscribersFromBrowse(root: JSONObject): String? {
        val header = root.optJSONObject("header")
        val direct = extractText(
            header
                ?.optJSONObject("c4TabbedHeaderRenderer")
                ?.optJSONObject("subscriberCountText")
        ).trim()
        formatArtistStats(direct)?.let { formatted ->
            if (formatted.isNotBlank()) return formatted
        }

        val metadata = root.optJSONObject("metadata")
            ?.optJSONObject("channelMetadataRenderer")
        val metadataDescription = metadata?.optString("description", "").orEmpty().trim()
        formatArtistStats(metadataDescription)?.let { formatted ->
            if (artistStatsScore(formatted) > 0) return formatted
        }

        val snippets = mutableListOf<String>()
        collectTextSnippets(root, snippets)
        return selectBestArtistStatsSnippet(snippets)
    }

    private fun formatArtistStats(text: String?): String? {
        val normalized = text?.replace(Regex("\\s+"), " ")?.trim()
        if (normalized.isNullOrBlank()) return null
        if (normalized.length <= 64) return normalized

        val keywordMatch = Regex(
            "\\b\\d+[\\d.,]*\\s*[kmbt]?\\s*(subscribers?|followers?|monthly listeners?|listeners?)\\b",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.value?.trim()
        if (!keywordMatch.isNullOrBlank()) return keywordMatch

        val compact = Regex("\\b\\d+[\\d.,]*\\s*[kmbt]\\b", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.value
            ?.trim()
        return compact
    }

    private fun collectTextSnippets(node: JSONObject, output: MutableList<String>) {
        val simpleText = node.optString("simpleText", "").trim()
        if (simpleText.isNotEmpty()) output.add(simpleText)

        val runs = node.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val joined = buildString {
                for (i in 0 until runs.length()) {
                    append(runs.optJSONObject(i)?.optString("text", ""))
                }
            }.trim()
            if (joined.isNotEmpty()) output.add(joined)
        }

        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val child = node.opt(key)) {
                is JSONObject -> collectTextSnippets(child, output)
                is JSONArray -> {
                    for (i in 0 until child.length()) {
                        val item = child.opt(i)
                        if (item is JSONObject) {
                            collectTextSnippets(item, output)
                        }
                    }
                }
            }
        }
    }
}
