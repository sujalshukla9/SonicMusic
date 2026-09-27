package com.sonicmusic.app.data.remote.source

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ArtistRef
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
        private const val MAX_DURATION = InnertubeConfig.MAX_INDIVIDUAL_SONG_DURATION_SECONDS // 11 minutes max for single tracks
        
        // Non-music keywords to filter out
        private val EXCLUDE_KEYWORDS = listOf(
            "vlog", " review", " reaction", " episode", " full movie",
            "podcast", " interview", " tutorial", " gameplay", " trailer",
            "documentary", " news", " unboxing", " haul", " challenge",
            "behind the scenes", " how to"
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
                Log.d(TAG, "Region from SIM: $simCountry")
                return simCountry
            }
            
            // Try network country
            val networkCountry = telephonyManager?.networkCountryIso?.uppercase()
            if (!networkCountry.isNullOrEmpty() && networkCountry.length == 2) {
                Log.d(TAG, "Region from network: $networkCountry")
                return networkCountry
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting SIM region", e)
        }
        
        // Fallback to system locale
        val localeCountry = Locale.getDefault().country.uppercase()
        if (localeCountry.isNotEmpty()) {
            Log.d(TAG, "Region from locale: $localeCountry")
            return localeCountry
        }
        
        Log.d(TAG, "Using default region: $DEFAULT_REGION")
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
        return if (normalized == "UK") " GB" else normalized
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
            Log.e(TAG, "Mixed search error", e)
            Result.failure(e)
        }
    }

    /**
     * Search for videos only using YouTube Music Video filter.
     * Supports continuation-token pagination for load-more.
     *
     * @param query Search query
     * @param limit Max results per page
     * @param continuationToken Token from previous page for pagination (null = first page)
     * @return SongSearchPage with video results and next continuation token
     */
    suspend fun searchVideos(
        query: String,
        limit: Int = 20,
        continuationToken: String? = null
    ): Result<SongSearchPage> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val safeContinuation = continuationToken?.trim()?.takeIf { it.isNotEmpty() }

            val requestBody = Innertube.webRemixBody(language, region) {
                if (safeContinuation.isNullOrBlank()) {
                    put("query", query)
                    put("params", Innertube.SearchFilter.Video.value)
                } else {
                    put("continuation", safeContinuation)
                }
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.SEARCH,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("YouTube Music video search failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            val page = parseYouTubeMusicSongPage(responseBody)
            val videos = page.songs
                .map { it.copy(contentType = ContentType.VIDEO, thumbnailUrl = upgradeThumbQuality(it.thumbnailUrl, it.id)) }
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .filter { it.duration == 0 || isValidDuration(it.duration) }
                .distinctBy { it.id }
                .take(limit)

            Log.d(TAG, "Video search returned ${videos.size} videos for: $query (continuation=${!page.continuationToken.isNullOrBlank()})")
            Result.success(
                SongSearchPage(
                    songs = videos,
                    continuationToken = page.continuationToken?.takeIf { it.isNotBlank() }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Video search error", e)
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
        Log.d(TAG, "Searching for: $query")
        
        // Try YouTube Music API first (music-only results)
        var result = searchYouTubeMusic(query, limit, offset)
        
        if (result.isSuccess && result.getOrNull()?.isNotEmpty() == true) {
            Log.d(TAG, "YouTube Music returned ${result.getOrNull()?.size} songs")
            return@withContext result
        }
        
        Log.d(TAG, "YouTube Music failed, trying regular YouTube with filters")
        
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
            Log.d(TAG, "Fallback page search returned ${fallbackResult.getOrNull()?.songs?.size ?: 0} songs")
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
                Log.e(TAG, "YouTube Music paged search failed: ${response.code}")
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
            Log.e(TAG, "YouTube Music paged search error", e)
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
                "Calling YouTube Music API (offset=$safeOffset, limit=$safeLimit)"
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
                    Log.e(TAG, "YouTube Music failed on page ${pageIndex + 1}: ${response.code}")
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
                                "Repeated continuation token detected for query=\"$query\"; stopping pagination"
                            )
                        }
                        isNewToken
                    }
                pageIndex += 1
                keepFetching = !continuationToken.isNullOrBlank() && cleanedCount < targetResults && pageIndex < 10
            }

            val songs = sanitizeSongResults(
                songs = rawSongs,
                limit = safeLimit,
                offset = safeOffset
            )

            Log.d(TAG, "Parsed ${songs.size} music tracks (raw=${rawSongs.size}, pages=$pageIndex)")
            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music error", e)
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
            val musicQuery = if (!query.lowercase().contains("song") && !query.lowercase().contains(" music")) {
                "$query song"
            } else {
                query
            }
            
            val requestBody = Innertube.buildYtmContext(language, region) {
                put("query", query)
                put("params", Innertube.SearchFilter.Song.value)
            }
            
            val request = Innertube.musicPost(
                endpoint = Innertube.SEARCH,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )
            
            Log.d(TAG, "Calling YouTube Music Search API with Song filter...")

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "YouTube Music search failed: ${response.code}")
                return@withContext Result.failure(Exception("YouTube Music search failed: ${response.code}"))
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
            
            Log.d(TAG, "Filtered to ${songs.size} music tracks")

            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music search error", e)
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
            Log.d(TAG, "Filtered by duration: ${song.title} (${song.duration}s)")
            return false
        }
        
        // Check for excluded keywords
        if (containsExcludedKeywords(song.title)) {
            Log.d(TAG, "Filtered by keyword: ${song.title}")
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
            .filter { InnertubeConfig.isAuthenticSong(it.title, it.artist, it.duration) }
            .map { song ->
                song.copy(
                    contentType = ContentType.SONG,
                    thumbnailUrl = upgradeThumbQuality(song.thumbnailUrl, song.id)
                )
            }
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
        return EXCLUDE_KEYWORDS.any { titleLower.contains(it) } || !InnertubeConfig.isAuthenticSong(title)
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
                Log.d(TAG, "No YTM tabs found, trying alternative structure")
                songs += parseYouTubeResponse(jsonString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse YouTube Music error", e)
        }

        Log.d(
            TAG,
            "YouTube Music parsed page: ${songs.size} songs, continuation=${!continuationToken.isNullOrBlank()}"
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

            val cardSubtitleRefs = cardSubtitleRuns?.let { extractArtistAndAlbumRefs(it) }
            val cardArtistId = if (cardSubtitleRefs != null && cardSubtitleRefs.artists.isNotEmpty()) {
                cardSubtitleRefs.artists.mapNotNull { it.browseId }.joinToString(",").takeIf { it.isNotBlank() }
            } else null

            val cardArtistRefs = if (cardSubtitleRefs != null && cardSubtitleRefs.artists.isNotEmpty()) {
                cardSubtitleRefs.artists.map { ArtistRef(it.name, it.browseId) }
            } else if (cardArtist.isNotBlank()) {
                listOf(ArtistRef(cleanArtistName(cardArtist), null))
            } else emptyList()

            var cardAlbumName: String? = null
            var cardAlbumBrowseId: String? = null
            if (cardSubtitleRefs != null) {
                cardAlbumName = cardSubtitleRefs.album?.name
                cardAlbumBrowseId = cardSubtitleRefs.album?.browseId
            }

            return Song(
                id = id,
                title = titleText,
                artist = cleanArtistName(cardArtist),
                artistId = cardArtistId,
                album = cardAlbumName,
                albumId = cardAlbumBrowseId,
                duration = 0,
                thumbnailUrl = thumbnailUrl,
                category = "Mixed",
                contentType = contentType,
                albumBrowseId = cardAlbumBrowseId,
                albumName = cardAlbumName,
                artists = cardArtistRefs
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
            var artistBrowseId: String? = null
            val artistNames = mutableListOf<String>()
            val artistIds = mutableListOf<String>()
            
            if (subtitleRuns != null) {
                val fullSubtitle = StringBuilder()
                
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
                            artistIds.add(browseId)
                        }
                    }
                }
                subtitle = fullSubtitle.toString()
                
                // Extract type text (first run, before first separator)
                typeText = subtitleRuns.optJSONObject(0)?.optString("text", "") ?: ""
                
                if (artistNames.isNotEmpty()) {
                    artist = artistNames.joinToString(", ")
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
                artistBrowseId = if (artistIds.isNotEmpty()) artistIds.joinToString(",") else null
            }
            
            var albumName: String? = null
            var albumBrowseId: String? = null
            if (subtitleRuns != null) {
                val refs = extractArtistAndAlbumRefs(subtitleRuns)
                albumName = refs.album?.name
                albumBrowseId = refs.album?.browseId
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
            if (typeTextLower == "video" || typeTextLower.contains(" video")) contentType = ContentType.VIDEO
            else if (typeTextLower == "artist" || id.startsWith(" UC")) contentType = ContentType.ARTIST
            else if (typeTextLower == "album" || id.startsWith(" MPRE")) contentType = ContentType.ALBUM
            else if (typeTextLower == "playlist" || id.startsWith(" VL")) contentType = ContentType.PLAYLIST
            
            // Explicit override for known browse ID prefixes
            if (id.startsWith("UC")) contentType = ContentType.ARTIST

            val mixedArtistRefs = when {
                contentType == ContentType.ARTIST -> {
                    listOf(ArtistRef(title, id.takeIf { it.startsWith("UC") } ?: artistBrowseId))
                }
                artistNames.isNotEmpty() -> {
                    artistNames.mapIndexed { idx, name ->
                        ArtistRef(name, artistIds.getOrNull(idx))
                    }
                }
                artist.isNotBlank() -> {
                    listOf(ArtistRef(cleanArtistName(artist), artistBrowseId))
                }
                else -> emptyList()
            }

            return Song(
                id = id,
                title = title,
                artist = cleanArtistName(artist),
                artistId = if (contentType == ContentType.ARTIST && id.startsWith("UC")) id else artistBrowseId,
                album = albumName,
                albumId = albumBrowseId,
                duration = 0,
                thumbnailUrl = thumbnailUrl,
                category = "Mixed",
                contentType = contentType,
                albumBrowseId = albumBrowseId,
                albumName = albumName,
                artists = mixedArtistRefs
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
            var artistId: String? = null
            var parsedArtistRefs = emptyList<ArtistRef>()
            var durationText = "0:00"
            var albumName: String? = null
            var albumBrowseId: String? = null
            
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
                        parsedArtistRefs = refs.artists.map { ArtistRef(it.name, it.browseId) }
                        artistId = if (refs.artists.isNotEmpty()) {
                            refs.artists.mapNotNull { it.browseId }.joinToString(",").takeIf { it.isNotBlank() }
                        } else null
                        artist = if (refs.artists.isNotEmpty()) {
                            refs.artists.joinToString(", ") { it.name }
                        } else {
                            // Fallback: use first non-separator run
                            extractFirstNonSeparatorRun(artistColumn)
                        }
                        
                        if (refs.album != null) {
                            albumName = refs.album.name
                            albumBrowseId = refs.album.browseId
                        }
                    }
                }
                
                // Fallback to third column if album not found in the second column
                if (albumName.isNullOrBlank() && flexColumns.length() > 2) {
                    val albumColumnRuns = flexColumns.optJSONObject(2)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    if (albumColumnRuns != null && albumColumnRuns.length() > 0) {
                        val albumRefs = extractArtistAndAlbumRefs(albumColumnRuns)
                        if (albumRefs.album != null) {
                            albumName = albumRefs.album.name
                            albumBrowseId = albumRefs.album.browseId
                        } else {
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
                Log.d(TAG, "Skipping non-music content: $title ($contentType)")
                return null
            }

            return Song(
                id = videoId,
                title = title,
                artist = cleanArtistName(artist),
                artistId = artistId,
                album = albumName,
                albumId = albumBrowseId,
                duration = parseDuration(durationText),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                category = "Music",
                contentType = contentType,
                albumBrowseId = albumBrowseId,
                albumName = albumName,
                artists = if (parsedArtistRefs.isNotEmpty()) parsedArtistRefs else {
                    if (artist.isNotBlank()) listOf(ArtistRef(cleanArtistName(artist), artistId)) else emptyList()
                }
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

        // Non-single compilations, spam channels, full albums, DJ mixes, and jukeboxes
        if (!InnertubeConfig.isAuthenticSong(title, artist, durationSeconds)) {
            return ContentType.ALBUM
        }

        // Check for explicit content type indicators in title
        return when {
            // Live streams
            titleLower.contains("live") && (titleLower.contains(" now") || titleLower.contains(" streaming")) -> ContentType.LIVE_STREAM
            titleLower.contains("live stream") || titleLower.contains(" livestream") -> ContentType.LIVE_STREAM

            // Podcasts
            titleLower.contains("podcast") || titleLower.contains(" episode") -> ContentType.PODCAST
            artistLower.contains("podcast") -> ContentType.PODCAST

            // Shorts
            titleLower.contains("#shorts") || titleLower.contains(" shorts") -> ContentType.SHORT
            durationSeconds in 1..59 -> ContentType.SHORT  // Less than 1 minute = short

            // Full albums
            titleLower.contains("full album") || titleLower.contains(" complete album") -> ContentType.ALBUM
            durationSeconds > InnertubeConfig.MAX_INDIVIDUAL_SONG_DURATION_SECONDS -> ContentType.ALBUM  // Over 11 minutes = album/mix

            // Music videos (visual content)
            titleLower.contains("official video") || titleLower.contains(" music video") -> ContentType.VIDEO
            titleLower.contains("(video)") || titleLower.contains("[video]") -> ContentType.VIDEO
            titleLower.contains("vevo") && (titleLower.contains(" video") || titleLower.contains(" official")) -> ContentType.VIDEO

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

            val ownerRuns = renderer.optJSONObject("ownerText")?.optJSONArray("runs")
                ?: renderer.optJSONObject("shortBylineText")?.optJSONArray("runs")
            val artistInfo = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                runs = ownerRuns.toRunList()
            )
            val artist = cleanArtistName(
                artistInfo.displayName.ifBlank {
                    extractArtistFromTextObject(renderer.optJSONObject("ownerText"))
                        .ifEmpty { extractArtistFromTextObject(renderer.optJSONObject("shortBylineText")) }
                }
            )
            val videoArtistRefs = if (artistInfo.individuals.isNotEmpty()) {
                artistInfo.individuals.map { ArtistRef(it.name, it.browseId) }
            } else if (artist.isNotBlank()) {
                listOf(ArtistRef(artist, null))
            } else emptyList()
            val videoArtistId = videoArtistRefs.mapNotNull { it.browseId }.joinToString(",").takeIf { it.isNotBlank() }

            val durationText = renderer.optJSONObject("lengthText")?.optString("simpleText", "0:00") ?: "0:00"

            // Detect content type and filter non-music
            val contentType = detectContentType(title, artist, durationText)
            if (contentType != ContentType.SONG && contentType != ContentType.UNKNOWN) {
                Log.d(TAG, "Filtering non-music from regular YouTube: $title ($contentType)")
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
                artistId = videoArtistId,
                duration = parseDuration(durationText),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                category = "Music",
                contentType = contentType,
                artists = videoArtistRefs
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
    private fun extractFirstNonSeparatorRun(runs: JSONArray?): String {
        if (runs == null) return ""
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

            // Select best match by name similarity; require a genuine name match (score >= 15)
            val normalizedQuery = query.lowercase().replace(Regex("\\s+"), " ").trim()
            val best = candidates.maxByOrNull { candidate ->
                var score = 0
                val name = candidate.name.lowercase().replace(Regex("\\s+"), " ").trim()
                if (name == normalizedQuery) score += 15
                if (name.contains(normalizedQuery) || normalizedQuery.contains(name)) score += 8
                if (!candidate.browseId.isNullOrBlank() && candidate.browseId.startsWith("UC")) score += 5
                if (!candidate.subscribersText.isNullOrBlank()) score += 3
                if (!candidate.thumbnailUrl.isNullOrBlank()) score += 2
                score
            } ?: return null
            // Require at least a partial name match to avoid returning random artists
            val bestName = best.name.lowercase().replace(Regex("\\s+"), " ").trim()
            val hasNameMatch = bestName == normalizedQuery ||
                bestName.contains(normalizedQuery) ||
                normalizedQuery.contains(bestName)
            if (!hasNameMatch) return null
            return best
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
                    Log.d(TAG, "Artist found via filter search: $resolvedName (browseId=$browseId)")
                }

                // ── Step 2: Fallback to mixed search if filter found nothing ──
                if (browseId.isNullOrBlank()) {
                    Log.d(TAG, "Artist filter search missed, falling back to mixed search")
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
                        Log.d(TAG, "Artist browse header type: $headerType (region=$region)")

                        if (resolvedName.isBlank()) {
                            resolvedName = extractArtistTitleFromBrowse(browseJson).orEmpty()
                        }
                        extractBestArtistHeaderImage(browseJson)?.let { image ->
                            Log.d(TAG, "Artist image from browse: ${image.take(80)}...")
                            finalImage = image
                        } ?: Log.w(TAG, "No artist image found from browse response")

                        // Try responsive header first (newer YTM format), then
                        // fall back to the generic deep-scan subscriber extraction.
                        val responsiveSubscribers = extractResponsiveHeaderSubscribers(browseJson)
                        if (!responsiveSubscribers.isNullOrBlank()) {
                            Log.d(TAG, "Artist subscribers from responsive header: $responsiveSubscribers")
                            finalSubscribers = responsiveSubscribers
                        } else {
                            extractArtistSubscribersFromBrowse(browseJson)?.let { subscribers ->
                                Log.d(TAG, "Artist subscribers from browse: $subscribers")
                                finalSubscribers = subscribers
                            } ?: Log.w(TAG, "No subscriber count found from browse response")
                        }

                        finalDescription = extractArtistDescription(browseJson)
                        finalBannerUrl = extractArtistBannerUrl(browseJson)
                        finalShufflePlaylistId = extractShufflePlaylistId(browseJson)
                        finalRadioPlaylistId = extractRadioPlaylistId(browseJson)

                        val parsedSections = parseArtistSectionsFromBrowse(
                            root = browseJson,
                            fallbackArtistName = resolvedName.ifBlank { normalizedName }
                        )
                        Log.d(TAG, "Parsed ${parsedSections.size} sections: ${parsedSections.map { "${it.type}(${it.items.size} items)" }}")

                        if (parsedSections.isNotEmpty()) {
                            sections = parsedSections
                            topSongs = parsedSections
                                .filter { it.type == ArtistSectionType.TOP_SONGS }
                                .flatMap { it.items }
                                .distinctBy { it.id }
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
            Log.e(TAG, "Failed to fetch artist profile from Innertube", e)
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
            Result.failure(e)
        }
    }

    suspend fun getPlaylistDetailsFromInnertube(
        playlistId: String,
        limit: Int = 500
    ): Result<Pair<com.sonicmusic.app.domain.model.Playlist, List<Song>>> = withContext(Dispatchers.IO) {
        val rawPlaylistId = playlistId.trim()
        val browseId = if (rawPlaylistId.startsWith("PL") && !rawPlaylistId.startsWith(" VL")) " VL$rawPlaylistId" else rawPlaylistId
        if (browseId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Playlist ID is empty"))
        }

        try {
            val rawResult = browseRaw(browseId)
            if (rawResult.isFailure) {
                return@withContext Result.failure(rawResult.exceptionOrNull() ?: Exception("Failed to fetch playlist raw browse"))
            }
            val browseRaw = rawResult.getOrThrow()
            val json = JSONObject(browseRaw)

            val twoColumn = json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
            val headerObj = twoColumn?.optJSONObject("header") 
                ?: json.optJSONObject("header")

            val responsiveHeader = headerObj?.optJSONObject("musicResponsiveHeaderRenderer")
            val detailHeader = headerObj?.optJSONObject("musicDetailHeaderRenderer")
            val musicHeader = headerObj?.optJSONObject("musicHeaderRenderer")

            fun extractTextFromRuns(runs: JSONArray?): String? {
                if (runs == null || runs.length() == 0) return null
                val sb = java.lang.StringBuilder()
                for (i in 0 until runs.length()) {
                    runs.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
                }
                return sb.toString().trim().takeIf { it.isNotEmpty() }
            }

            val title = responsiveHeader?.optJSONObject("title")?.optJSONArray("runs")?.let { extractTextFromRuns(it) }
                ?: detailHeader?.optJSONObject("title")?.optJSONArray("runs")?.let { extractTextFromRuns(it) }
                ?: musicHeader?.optJSONObject("title")?.optJSONArray("runs")?.let { extractTextFromRuns(it) }
                ?: "YouTube Music Playlist"

            val description = responsiveHeader?.optJSONObject("description")?.optJSONArray("runs")?.let { extractTextFromRuns(it) }
                ?: detailHeader?.optJSONObject("description")?.optJSONArray("runs")?.let { extractTextFromRuns(it) }
                ?: detailHeader?.optJSONObject("subtitle")?.optJSONArray("runs")?.let { extractTextFromRuns(it) }

            val thumbnailObj = responsiveHeader?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?: detailHeader?.optJSONObject("thumbnail")?.optJSONObject("croppedSquareThumbnailRenderer")
                ?: detailHeader?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            val thumbnailsArray = thumbnailObj?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            
            var coverArtUrl: String? = null
            if (thumbnailsArray != null && thumbnailsArray.length() > 0) {
                coverArtUrl = thumbnailsArray.optJSONObject(thumbnailsArray.length() - 1)?.optString("url")
            }

            val songs = sanitizeSongResults(
                songs = parseBrowseResponse(browseRaw),
                limit = limit,
                offset = 0
            )

            val playlist = com.sonicmusic.app.domain.model.Playlist(
                id = 0,
                name = title,
                description = description,
                coverArtUrl = coverArtUrl,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                songCount = songs.size,
                songs = songs,
                isLocal = false,
                remoteId = rawPlaylistId
            )

            Result.success(Pair(playlist, songs))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playlist details from Innertube", e)
            Result.failure(e)
        }
    }

    suspend fun browseRaw(browseId: String): Result<String> = withContext(Dispatchers.IO) {
        val normalizedBrowseId = browseId.trim()
        if (normalizedBrowseId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Browse ID is empty"))
        }
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val browseBody = Innertube.webRemixBody(language, region) {
                put("browseId", normalizedBrowseId)
            }
            val browseRequest = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = browseBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )
            val browseResponse = client.newCall(browseRequest).execute()
            if (!browseResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Innertube browse failed: ${browseResponse.code}"))
            }
            val browseRaw = browseResponse.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))
            Result.success(browseRaw)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbumDetailsFromInnertube(
        albumBrowseId: String
    ): Result<com.sonicmusic.app.domain.model.AlbumDetails> = withContext(Dispatchers.IO) {
        val browseId = albumBrowseId.trim()
        Log.d(TAG, "getAlbumDetailsFromInnertube: ID = $browseId")
        val rawResult = browseRaw(browseId)
        if (rawResult.isFailure) {
            return@withContext Result.failure(rawResult.exceptionOrNull() ?: Exception("Failed to fetch album raw browse"))
        }
        val browseRaw = rawResult.getOrThrow()
        
        try {
            val json = JSONObject(browseRaw)
            
            // 1. Header parsing (musicResponsiveHeaderRenderer or musicDetailHeaderRenderer)
            var headerTitle = ""
            val headerArtists = mutableListOf<String>()
            val headerArtistRefs = mutableListOf<ArtistRef>()
            var headerArtistBrowseId: String? = null
            var headerYear: String? = null
            var headerType: String? = null
            var headerThumbnail = ""
            var headerTrackCount = 0

            val twoColumn = json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")

            val sectionLists = mutableListOf<JSONArray>()
            twoColumn?.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?.let(sectionLists::add)

            twoColumn?.optJSONArray("tabs")?.let { tabs ->
                for (tabIndex in 0 until tabs.length()) {
                    tabs.optJSONObject(tabIndex)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?.let(sectionLists::add)
                }
            }

            json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.let { tabs ->
                    for (tabIndex in 0 until tabs.length()) {
                        tabs.optJSONObject(tabIndex)
                            ?.optJSONObject("tabRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents")
                            ?.let(sectionLists::add)
                    }
                }

            val headerFromSectionList = sectionLists.asSequence()
                .flatMap { sections ->
                    (0 until sections.length()).asSequence().mapNotNull { sections.optJSONObject(it) }
                }
                .firstOrNull { it.has("musicResponsiveHeaderRenderer") || it.has("musicDetailHeaderRenderer") }
                ?: sectionLists.firstOrNull()?.optJSONObject(0)
            
            val headerObj = twoColumn?.optJSONObject("header") 
                ?: json.optJSONObject("header")
                ?: headerFromSectionList
            
            val responsiveHeader = headerObj?.optJSONObject("musicResponsiveHeaderRenderer")
            val detailHeader = headerObj?.optJSONObject("musicDetailHeaderRenderer")
            
            if (responsiveHeader != null) {
                Log.d(TAG, "Found musicResponsiveHeaderRenderer")
                headerTitle = responsiveHeader.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "").orEmpty()
                
                val straplineTextOneRuns = responsiveHeader.optJSONObject("straplineTextOne")?.optJSONArray("runs")
                val straplineRuns = responsiveHeader.optJSONObject("strapline")?.optJSONArray("runs")
                val subtitleRuns = responsiveHeader.optJSONObject("subtitle")?.optJSONArray("runs")
                val secondSubtitleRuns = responsiveHeader.optJSONObject("secondSubtitle")?.optJSONArray("runs")
                
                val artistRefs = extractArtistAndAlbumRefs(straplineTextOneRuns ?: straplineRuns ?: subtitleRuns)
                headerArtists.addAll(artistRefs.artists.map { it.name })
                headerArtistRefs.addAll(artistRefs.artists.map { ArtistRef(it.name, it.browseId) })
                headerArtistBrowseId = artistRefs.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                
                if (headerArtists.isEmpty()) {
                    val fb = extractFirstNonSeparatorRun(straplineTextOneRuns ?: straplineRuns ?: subtitleRuns)
                    if (fb.isNotBlank()) {
                        headerArtists.add(fb)
                        headerArtistRefs.add(ArtistRef(fb, null))
                    }
                }
                
                val allDetailRuns = mutableListOf<JSONObject>()
                listOf(subtitleRuns, secondSubtitleRuns, straplineRuns, straplineTextOneRuns).forEach { runs ->
                    if (runs != null) {
                        for (idx in 0 until runs.length()) {
                            runs.optJSONObject(idx)?.let { allDetailRuns.add(it) }
                        }
                    }
                }
                
                for (run in allDetailRuns) {
                    val text = run.optString("text", "").trim()
                    if (text.isBlank() || text == "•" || text == "·" || text == "|") continue
                    
                    if (text.endsWith("songs") || text.endsWith(" song") || text.endsWith(" tracks") || text.endsWith(" track")) {
                        headerTrackCount = text.split(" ")[0].toIntOrNull() ?: 0
                    } else if (text.matches(Regex("\\d{4}"))) {
                        headerYear = text
                    } else if (text.equals("Album", ignoreCase = true) || text.equals(" Single", ignoreCase = true) || text.equals(" EP", ignoreCase = true)) {
                        headerType = text
                    }
                }
                
                val thumbnails = responsiveHeader.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                headerThumbnail = extractHighestQualityThumbnail(thumbnails, "")
                
            } else if (detailHeader != null) {
                Log.d(TAG, "Found musicDetailHeaderRenderer")
                headerTitle = detailHeader.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "").orEmpty()
                
                val subtitleRuns = detailHeader.optJSONObject("subtitle")?.optJSONArray("runs")
                val secondSubtitleRuns = detailHeader.optJSONObject("secondSubtitle")?.optJSONArray("runs")
                
                val artistRefs = extractArtistAndAlbumRefs(subtitleRuns)
                headerArtists.addAll(artistRefs.artists.map { it.name })
                headerArtistRefs.addAll(artistRefs.artists.map { ArtistRef(it.name, it.browseId) })
                if (headerArtistBrowseId == null) {
                    headerArtistBrowseId = artistRefs.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                }
                
                if (headerArtists.isEmpty() && subtitleRuns != null) {
                    val fb = extractFirstNonSeparatorRun(subtitleRuns)
                    if (fb.isNotBlank()) {
                        headerArtists.add(fb)
                        headerArtistRefs.add(ArtistRef(fb, null))
                    }
                }
                
                val allDetailRuns = mutableListOf<JSONObject>()
                listOf(subtitleRuns, secondSubtitleRuns).forEach { runs ->
                    if (runs != null) {
                        for (idx in 0 until runs.length()) {
                            runs.optJSONObject(idx)?.let { allDetailRuns.add(it) }
                        }
                    }
                }
                
                for (run in allDetailRuns) {
                    val text = run.optString("text", "").trim()
                    if (text.isBlank() || text == "•" || text == "·" || text == "|") continue
                    
                    if (text.endsWith("songs") || text.endsWith(" song") || text.endsWith(" tracks") || text.endsWith(" track")) {
                        headerTrackCount = text.split(" ")[0].toIntOrNull() ?: 0
                    } else if (text.matches(Regex("\\d{4}"))) {
                        headerYear = text
                    } else if (text.equals("Album", ignoreCase = true) || text.equals(" Single", ignoreCase = true) || text.equals(" EP", ignoreCase = true)) {
                        headerType = text
                    }
                }
                
                val thumbnails = detailHeader.optJSONObject("thumbnail")
                    ?.optJSONObject("croppedSquareThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                headerThumbnail = extractHighestQualityThumbnail(thumbnails, "")
            }

            if (headerTitle.isBlank()) headerTitle = "Album"
            
            // 2. Track List (Songs section)
            val songsList = mutableListOf<com.sonicmusic.app.domain.model.AlbumTrack>()
            var nativeVideos = mutableListOf<com.sonicmusic.app.domain.model.AlbumVideo>()

            for (sectionList in sectionLists) {
                for (i in 0 until sectionList.length()) {
                    val section = sectionList.optJSONObject(i) ?: continue
                    
                    val musicShelf = section.optJSONObject("musicShelfRenderer")
                    if (musicShelf != null) {
                        val contents = musicShelf.optJSONArray("contents")
                        if (contents != null) {
                            for (j in 0 until contents.length()) {
                                val itemWrapper = contents.optJSONObject(j) ?: continue
                                val item = itemWrapper.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                
                                val videoId = sequenceOf(
                                    item.optJSONObject("playlistItemData")?.optString("videoId", ""),
                                    item.optJSONObject("overlay")
                                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                        ?.optJSONObject("content")
                                        ?.optJSONObject("musicPlayButtonRenderer")
                                        ?.optJSONObject("playNavigationEndpoint")
                                        ?.optJSONObject("watchEndpoint")
                                        ?.optString("videoId", "")
                                ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
                                
                                if (videoId.isBlank()) {
                                    Log.w(TAG, "Track at index $j has no videoId")
                                    continue
                                }
                                
                                val flexColumns = item.optJSONArray("flexColumns") ?: continue
                                if (flexColumns.length() == 0) continue
                                
                                val title = extractMusicText(flexColumns.optJSONObject(0))
                                
                                val trackArtists = mutableListOf<String>()
                                val trackArtistBrowseIds = mutableListOf<String>()
                                val trackArtistRefs = mutableListOf<ArtistRef>()
                                if (flexColumns.length() > 1) {
                                    val runs = flexColumns.optJSONObject(1)
                                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        ?.optJSONObject("text")
                                        ?.optJSONArray("runs")
                                    val refs = extractArtistAndAlbumRefs(runs)
                                    if (refs.artists.isNotEmpty()) {
                                        trackArtists.addAll(refs.artists.map { it.name })
                                        trackArtistBrowseIds.addAll(refs.artists.mapNotNull { it.browseId })
                                        trackArtistRefs.addAll(refs.artists.map { ArtistRef(it.name, it.browseId) })
                                    } else {
                                        val fb = if (runs != null) extractFirstNonSeparatorRun(runs) else ""
                                        if (fb.isNotBlank()) {
                                            trackArtists.add(fb)
                                            trackArtistRefs.add(ArtistRef(fb, null))
                                        }
                                    }
                                }
                                if (trackArtists.isEmpty()) {
                                    trackArtists.addAll(headerArtists)
                                    trackArtistRefs.addAll(headerArtistRefs)
                                    if (headerArtistBrowseId != null) {
                                        trackArtistBrowseIds.add(headerArtistBrowseId)
                                    }
                                }
                                
                                var durationText = "0:00"
                                val fixedColumns = item.optJSONArray("fixedColumns")
                                if (fixedColumns != null && fixedColumns.length() > 0) {
                                    durationText = fixedColumns.optJSONObject(0)
                                        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                                        ?.optJSONObject("text")
                                        ?.optJSONArray("runs")
                                        ?.optJSONObject(0)
                                        ?.optString("text", "0:00") ?: "0:00"
                                }
                                
                                val parsedTrackNumber = item.optJSONObject("indexText")
                                    ?.optJSONArray("runs")
                                    ?.optJSONObject(0)
                                    ?.optString("text", "")
                                    ?.toIntOrNull() ?: (j + 1)
                                
                                songsList.add(
                                    com.sonicmusic.app.domain.model.AlbumTrack(
                                        videoId = videoId,
                                        trackNumber = parsedTrackNumber,
                                        title = title,
                                        durationText = durationText,
                                        artists = trackArtists,
                                        albumBrowseId = browseId,
                                        albumName = headerTitle,
                                        artistBrowseIds = trackArtistBrowseIds,
                                        artistRefs = trackArtistRefs
                                    )
                                )
                            }
                        }
                    }
                    
                    val carouselShelf = section.optJSONObject("musicCarouselShelfRenderer")
                    if (carouselShelf != null) {
                        val titleText = carouselShelf.optJSONObject("header")
                            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                            ?.optJSONObject("title")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            .orEmpty()
                        
                        if (titleText.contains("Videos", ignoreCase = true) || titleText.contains(" Versions", ignoreCase = true)) {
                            Log.d(TAG, "Found videos carousel section: $titleText")
                            val carouselContents = carouselShelf.optJSONArray("contents")
                            if (carouselContents != null) {
                                for (j in 0 until carouselContents.length()) {
                                    val itemObj = carouselContents.optJSONObject(j) ?: continue
                                    
                                    val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                                    val responsive = itemObj.optJSONObject("musicResponsiveListItemRenderer")
                                    
                                    if (twoRow != null) {
                                        val vId = twoRow.optJSONObject("navigationEndpoint")
                                            ?.optJSONObject("watchEndpoint")
                                            ?.optString("videoId", "")
                                            ?: twoRow.optJSONObject("title")
                                                ?.optJSONArray("runs")
                                                ?.optJSONObject(0)
                                                ?.optJSONObject("navigationEndpoint")
                                                ?.optJSONObject("watchEndpoint")
                                                ?.optString("videoId", "")
                                            ?: ""
                                        
                                        if (vId.isNotBlank()) {
                                            val vTitle = twoRow.optJSONObject("title")
                                                ?.optJSONArray("runs")
                                                ?.optJSONObject(0)
                                                ?.optString("text", " Unknown Video") ?: " Unknown Video"
                                            
                                            val thumbs = twoRow.optJSONObject("thumbnailRenderer")
                                                ?.optJSONObject("musicThumbnailRenderer")
                                                ?.optJSONObject("thumbnail")
                                                ?.optJSONArray("thumbnails")
                                            val vThumb = extractHighestQualityThumbnail(thumbs, "https://i.ytimg.com/vi/$vId/hqdefault.jpg")
                                            
                                            var vDuration: String? = null
                                            val subRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                                            if (subRuns != null) {
                                                for (k in 0 until subRuns.length()) {
                                                    val t = subRuns.optJSONObject(k)?.optString("text", "").orEmpty()
                                                    if (t.contains(":")) {
                                                        vDuration = t
                                                        break
                                                    }
                                                }
                                            }
                                            
                                            nativeVideos.add(
                                                com.sonicmusic.app.domain.model.AlbumVideo(
                                                    videoId = vId,
                                                    title = vTitle,
                                                    thumbnailUrl = upgradeThumbQuality(vThumb, vId),
                                                    durationText = vDuration
                                                )
                                            )
                                        }
                                    } else if (responsive != null) {
                                        val vId = sequenceOf(
                                            responsive.optJSONObject("playlistItemData")?.optString("videoId", ""),
                                            responsive.optJSONObject("overlay")
                                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                                ?.optJSONObject("content")
                                                ?.optJSONObject("musicPlayButtonRenderer")
                                                ?.optJSONObject("playNavigationEndpoint")
                                                ?.optJSONObject("watchEndpoint")
                                                ?.optString("videoId", "")
                                        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
                                        
                                        if (vId.isNotBlank()) {
                                            val flexCols = responsive.optJSONArray("flexColumns")
                                            val vTitle = if (flexCols != null && flexCols.length() > 0) {
                                                extractMusicText(flexCols.optJSONObject(0))
                                            } else "Unknown Video"
                                            
                                            val thumbs = responsive.optJSONObject("thumbnail")
                                                ?.optJSONObject("musicThumbnailRenderer")
                                                ?.optJSONObject("thumbnail")
                                                ?.optJSONArray("thumbnails")
                                            val vThumb = extractHighestQualityThumbnail(thumbs, "https://i.ytimg.com/vi/$vId/hqdefault.jpg")
                                            
                                            var vDuration: String? = null
                                            val fixedCols = responsive.optJSONArray("fixedColumns")
                                            if (fixedCols != null && fixedCols.length() > 0) {
                                                vDuration = fixedCols.optJSONObject(0)
                                                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                                                    ?.optJSONObject("text")
                                                    ?.optJSONArray("runs")
                                                    ?.optJSONObject(0)
                                                    ?.optString("text", "")
                                            }
                                            
                                            nativeVideos.add(
                                                com.sonicmusic.app.domain.model.AlbumVideo(
                                                    videoId = vId,
                                                    title = vTitle,
                                                    thumbnailUrl = upgradeThumbQuality(vThumb, vId),
                                                    durationText = vDuration
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (headerArtists.isEmpty() && songsList.isNotEmpty()) {
                val unionArtists = songsList.flatMap { it.artists }.distinct()
                headerArtists.addAll(unionArtists)
            }
            if (headerArtists.isEmpty()) headerArtists.add("Unknown Artist")
            if (headerThumbnail.isBlank() && songsList.isNotEmpty()) {
                headerThumbnail = "https://i.ytimg.com/vi/${songsList.first().videoId}/hqdefault.jpg"
            }
            if (headerTrackCount == 0) {
                headerTrackCount = songsList.size
            }

            if (nativeVideos.isEmpty() && songsList.isNotEmpty()) {
                Log.d(TAG, "No native videos found. Running fallback search for top tracks...")
                val topTracks = songsList.take(3)
                
                val searchResults = coroutineScope {
                    topTracks.map { track ->
                        val artistQuery = track.artists.firstOrNull() ?: ""
                        val searchQuery = "${track.title} $artistQuery official video"
                        async {
                            val videos = searchVideos(searchQuery, limit = 3).getOrNull()?.songs.orEmpty()
                            videos.firstOrNull { video ->
                                val cleanVideoTitle = video.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                                val cleanVideoArtist = video.artist.lowercase().replace(Regex("[^a-z0-9]"), "")
                                val cleanTrackTitle = track.title.lowercase()
                                    .split(" feat").first()
                                    .split(" ft").first()
                                    .split(" with").first()
                                    .split(" (").first()
                                    .replace(Regex("[^a-z0-9]"), "")
                                val cleanArtist = artistQuery.lowercase().replace(Regex("[^a-z0-9]"), "")
                                
                                val titleMatches = cleanVideoTitle.contains(cleanTrackTitle)
                                val artistMatches = cleanArtist.isEmpty() || 
                                        cleanVideoArtist.contains(cleanArtist) || 
                                        cleanVideoTitle.contains(cleanArtist)
                                
                                titleMatches && artistMatches
                            }
                        }
                    }
                }.mapNotNull { it.await() }
                
                searchResults.forEach { matchedVideo ->
                    nativeVideos.add(
                        com.sonicmusic.app.domain.model.AlbumVideo(
                            videoId = matchedVideo.id,
                            title = matchedVideo.title,
                            thumbnailUrl = upgradeThumbQuality(matchedVideo.thumbnailUrl, matchedVideo.id),
                            durationText = matchedVideo.formattedDuration()
                        )
                    )
                }
                Log.d(TAG, "Fallback videos fetched: ${nativeVideos.size}")
            }

            val details = com.sonicmusic.app.domain.model.AlbumDetails(
                browseId = browseId,
                title = headerTitle,
                artists = headerArtists,
                year = headerYear,
                albumType = headerType,
                thumbnailUrl = headerThumbnail,
                trackCount = headerTrackCount,
                songs = songsList.distinctBy { it.videoId },
                videos = nativeVideos.distinctBy { it.videoId },
                artistBrowseId = headerArtistBrowseId ?: songsList.flatMap { it.artistBrowseIds }.firstOrNull { it.isNotBlank() },
                artistRefs = if (headerArtistRefs.isNotEmpty()) headerArtistRefs else songsList.flatMap { it.artistRefs }.distinctBy { it.name }
            )
            Result.success(details)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse album details", e)
            Result.failure(e)
        }
    }

    suspend fun getSongDetails(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            val requestBody = Innertube.buildYtmContext(language, region) {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            
            val request = Innertube.musicPost(
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

            val artistInfo = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                runs = null,
                playerAuthor = videoDetails.optString("author", " Unknown"),
                playerChannelId = videoDetails.optString("channelId", "")
            )

            val detailArtistRefs = if (artistInfo.individuals.isNotEmpty()) {
                artistInfo.individuals.map { ArtistRef(it.name, it.browseId) }
            } else {
                val chId = videoDetails.optString("channelId", "").takeIf { it.isNotBlank() }
                listOf(ArtistRef(cleanArtistName(artistInfo.displayName), chId))
            }

            Result.success(Song(
                id = videoId,
                title = videoDetails.optString("title", " Unknown"),
                artist = cleanArtistName(artistInfo.displayName),
                artistId = if (artistInfo.individuals.isNotEmpty()) {
                    artistInfo.individuals.map { it.browseId.orEmpty() }.joinToString(",")
                } else {
                    videoDetails.optString("channelId", "").takeIf { it.isNotBlank() }
                },
                duration = videoDetails.optInt("lengthSeconds", 0),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                viewCount = videoDetails.optString("viewCount", "0").toLongOrNull(),
                artists = detailArtistRefs
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Get details error", e)
            Result.failure(e)
        }
    }
    // ═══════════════════════════════════════════════════════════════
    // YTMUSIC BROWSE ENDPOINTS (FMusic/RiMusic Style)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Data class for a shelf returned by the FEmusic_home browse endpoint.
     */
    data class BrowseShelf(
        val title: String,
        val browseId: String? = null,
        val songs: List<Song>,
        val continuationToken: String? = null
    )

    /**
     * Data class for a mood/genre category from FEmusic_moods_and_genres.
     */
    data class MoodGenreCategory(
        val title: String,
        val params: String,
        val color: Long? = null
    )

    /**
     * Chart type for the FEmusic_charts endpoint.
     */
    enum class ChartType {
        TOP_SONGS, TOP_VIDEOS, TOP_ARTISTS, TRENDING
    }

    data class HomeBrowseResult(
        val shelves: List<BrowseShelf>,
        val continuationToken: String? = null
    )

    /**
     * Browse the YouTube Music home page (FEmusic_home).
     * Returns personalized/regional shelves that can be used for "Mixed For You".
     *
     * RiMusic equivalent: Innertube.browse(browseId = "FEmusic_home")
     */
    suspend fun browseHome(recentVideoIds: List<String> = emptyList()): Result<List<BrowseShelf>> = withContext(Dispatchers.IO) {
        val result = browseHomeWithContinuation(recentVideoIds = recentVideoIds, continuationToken = null)
        result.map { it.shelves }
    }

    /**
     * Browse the YouTube Music home page with continuation support.
     */
    suspend fun browseHomeWithContinuation(
        recentVideoIds: List<String> = emptyList(),
        continuationToken: String? = null
    ): Result<HomeBrowseResult> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Browsing YTMusic home for region: $region, cont=${!continuationToken.isNullOrBlank()}")

            val requestBody = if (continuationToken.isNullOrBlank()) {
                Innertube.webRemixBody(language, region) {
                    put("browseId", YtmScopeConfig.PermittedBrowseIds.HOME)
                    if (recentVideoIds.isNotEmpty()) {
                        put("playbackContext", JSONObject().apply {
                            put("recentlyPlayedVideoIds", JSONArray(recentVideoIds))
                        })
                    }
                }
            } else {
                Innertube.webRemixBody(language, region) {
                    put("continuation", continuationToken)
                }
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("FEmusic_home failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty FEmusic_home response"))
            }

            val browseResult = parseHomeBrowseResult(responseBody)
            Log.d(TAG, "FEmusic_home returned ${browseResult.shelves.size} shelves, nextCont=${!browseResult.continuationToken.isNullOrBlank()}")
            Result.success(browseResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "FEmusic_home error", e)
            Result.failure(e)
        }
    }

    /**
     * Browse moods and genres (FEmusic_moods_and_genres).
     * Returns categorized mood/genre chips for the home page.
     *
     * RiMusic equivalent: Innertube.browse(browseId = "FEmusic_moods_and_genres")
     */
    suspend fun browseMoodsAndGenres(): Result<List<MoodGenreCategory>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Browsing moods & genres for region: $region")

            val requestBody = Innertube.webRemixBody(language, region) {
                put("browseId", YtmScopeConfig.PermittedBrowseIds.MOODS_AND_GENRES)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Moods & genres failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty moods & genres response"))
            }

            val categories = parseMoodsAndGenresResponse(responseBody)
            Log.d(TAG, "Moods & genres returned ${categories.size} categories")
            Result.success(categories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Moods & genres error", e)
            Result.failure(e)
        }
    }

    /**
     * Browse charts with continuation support (FEmusic_charts).
     * Extends the existing getTrendingSongs with pagination.
     *
     * @param chartType Type of chart to fetch
     * @param continuationToken Token for loading next page
     */
    suspend fun browseCharts(
        chartType: ChartType = ChartType.TOP_SONGS,
        continuationToken: String? = null
    ): Result<BrowsePageResult> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Browsing charts (${chartType.name}) for region: $region")

            val requestBody = if (continuationToken.isNullOrBlank()) {
                Innertube.webRemixBody(language, region) {
                    put("browseId", YtmScopeConfig.PermittedBrowseIds.CHARTS)
                }
            } else {
                Innertube.webRemixBody(language, region) {
                    put("continuation", continuationToken)
                }
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Charts browse failed with HTTP ${response.code}, falling back to YTM chart search")
                val fallbackSongs = searchSongs("top hits $region ${currentYear()}", 30).getOrDefault(emptyList())
                return@withContext Result.success(BrowsePageResult(items = fallbackSongs))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty charts response"))
            }

            val songs = parseChartsResponse(responseBody)
            val nextToken = extractBrowseContinuationToken(responseBody)

            Log.d(TAG, "Charts returned ${songs.size} songs, continuation=${!nextToken.isNullOrBlank()}")
            Result.success(
                BrowsePageResult(
                    items = sanitizeSongResults(songs, limit = 0, offset = 0),
                    continuationToken = nextToken
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Charts browse error", e)
            Result.failure(e)
        }
    }

    /**
     * Browse a specific mood/genre playlist.
     *
     * @param params Browse params from MoodGenreCategory
     * @param limit Maximum number of songs to return
     */
    suspend fun browseMoodGenrePlaylist(
        params: String,
        limit: Int = 30
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Browsing mood/genre playlist")

            val requestBody = Innertube.webRemixBody(language, region) {
                put("browseId", YtmScopeConfig.PermittedBrowseIds.MOODS_AND_GENRES_CATEGORY)
                put("params", params)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Mood playlist failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty mood playlist response"))
            }

            val songs = sanitizeSongResults(
                songs = parseBrowseResponse(responseBody),
                limit = limit,
                offset = 0
            )

            Log.d(TAG, "Mood/genre playlist returned ${songs.size} songs")
            Result.success(songs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Mood/genre playlist error", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BROWSE RESPONSE PARSERS (FMusic/RiMusic Style)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse FEmusic_home browse response into shelves.
     */
    private fun parseHomeBrowseResponse(jsonString: String): List<BrowseShelf> {
        return parseHomeBrowseResult(jsonString).shelves
    }

    /**
     * Parse FEmusic_home browse response into shelves with continuation token.
     * Supports both initial page (sectionListRenderer) and continuation pages (sectionListContinuation).
     */
    private fun parseHomeBrowseResult(jsonString: String): HomeBrowseResult {
        val shelves = mutableListOf<BrowseShelf>()
        var nextContinuation: String? = null

        try {
            val json = JSONObject(jsonString)

            // 1. Check continuationContents (for continuation page)
            val contSection = json.optJSONObject("continuationContents")
                ?.optJSONObject("sectionListContinuation")

            val sections = if (contSection != null) {
                // Continuation token from continuations array
                nextContinuation = contSection.optJSONArray("continuations")
                    ?.optJSONObject(0)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf { it.isNotBlank() }

                contSection.optJSONArray("contents")
            } else {
                // Initial browse response
                val sectionList = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")

                nextContinuation = sectionList?.optJSONArray("continuations")
                    ?.optJSONObject(0)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf { it.isNotBlank() }

                sectionList?.optJSONArray("contents")
            }

            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue

                    // Try musicCarouselShelfRenderer (most common)
                    val carousel = section.optJSONObject("musicCarouselShelfRenderer")
                    if (carousel != null) {
                        val headerObj = carousel.optJSONObject("header")
                            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                        val title = headerObj?.optJSONObject("title")
                            ?.let { extractText(it) }
                            ?: "Recommended"
                        val browseId = headerObj?.optJSONObject("title")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId")

                        val items = carousel.optJSONArray("contents") ?: continue
                        val songs = mutableListOf<Song>()
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j) ?: continue
                            parseMusicItem(item)?.let { songs.add(it) }
                        }

                        if (songs.isNotEmpty()) {
                            shelves.add(
                                BrowseShelf(
                                    title = title,
                                    browseId = browseId,
                                    songs = songs
                                )
                            )
                        }
                    }

                    // Try musicShelfRenderer
                    val shelf = section.optJSONObject("musicShelfRenderer")
                    if (shelf != null) {
                        val title = shelf.optJSONObject("title")
                            ?.let { extractText(it) }
                            ?: "Music"

                        val items = shelf.optJSONArray("contents") ?: continue
                        val songs = mutableListOf<Song>()
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j) ?: continue
                            parseMusicItem(item)?.let { songs.add(it) }
                        }

                        if (songs.isNotEmpty()) {
                            shelves.add(
                                BrowseShelf(
                                    title = title,
                                    songs = songs
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing FEmusic_home", e)
        }
        return HomeBrowseResult(shelves = shelves, continuationToken = nextContinuation)
    }

    /**
     * Parse FEmusic_moods_and_genres browse response.
     * Structure: singleColumnBrowseResultsRenderer → tabs → sectionListRenderer → contents
     * Each section has a musicNavigationButtonRenderer with title and params.
     */
    private fun parseMoodsAndGenresResponse(jsonString: String): List<MoodGenreCategory> {
        val categories = mutableListOf<MoodGenreCategory>()
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
                ?: return categories

            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue

                // Try gridRenderer (main mood/genre grid)
                val gridItems = section.optJSONObject("gridRenderer")
                    ?.optJSONArray("items")

                if (gridItems != null) {
                    for (j in 0 until gridItems.length()) {
                        val gridItem = gridItems.optJSONObject(j) ?: continue
                        val button = gridItem.optJSONObject("musicNavigationButtonRenderer")
                            ?: continue

                        val title = button.optJSONObject("buttonText")
                            ?.let { extractText(it) }
                            ?: continue

                        val params = button.optJSONObject("clickCommand")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("params", "")
                            ?: button.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("params", "")
                            ?: continue

                        if (params.isBlank()) continue

                        // Extract optional color
                        val colorValue = button.optJSONObject("solid")
                            ?.optJSONObject("leftStripeColor")
                            ?.optLong("value")

                        categories.add(
                            MoodGenreCategory(
                                title = title,
                                params = params,
                                color = colorValue
                            )
                        )
                    }
                }

                // Also try musicCarouselShelfRenderer items with navigation buttons
                val carouselItems = section.optJSONObject("musicCarouselShelfRenderer")
                    ?.optJSONArray("contents")

                if (carouselItems != null) {
                    for (j in 0 until carouselItems.length()) {
                        val item = carouselItems.optJSONObject(j) ?: continue
                        val button = item.optJSONObject("musicNavigationButtonRenderer")
                            ?: continue

                        val title = button.optJSONObject("buttonText")
                            ?.let { extractText(it) }
                            ?: continue

                        val params = button.optJSONObject("clickCommand")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("params", "")
                            ?: button.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("params", "")
                            ?: continue

                        if (params.isBlank()) continue

                        categories.add(
                            MoodGenreCategory(
                                title = title,
                                params = params
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing moods & genres", e)
        }
        return categories.distinctBy { it.title }
    }

    /**
     * Get Trending Songs using YouTube Music Charts API.
     * Uses the browse endpoint with FEmusic_charts browseId (no params needed —
     * YTM returns "Top Songs" for the region by default).
     */
    suspend fun getTrendingSongs(limit: Int = 30): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Fetching real-time trending songs for region: $region")

            // FEmusic_charts without params returns the default "Top Songs" chart
            val requestBody = Innertube.webRemixBody(language, region) {
                put("browseId", YtmScopeConfig.PermittedBrowseIds.CHARTS)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Charts API failed with HTTP ${response.code}, falling back to YTM trending search")
                val fallbackResult = searchSongs("trending songs", limit).getOrDefault(emptyList())
                return@withContext Result.success(fallbackResult)
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.w(TAG, "Empty response from FEmusic_charts")
                return@withContext Result.success(emptyList())
            }

            val songs = sanitizeSongResults(
                songs = parseChartsResponse(responseBody),
                limit = limit,
                offset = 0
            )

            if (songs.isNotEmpty()) {
                Log.d(TAG, "Got ${songs.size} trending songs for $region")
                return@withContext Result.success(songs)
            }

            Log.w(TAG, "Charts returned empty, falling back to search query for trending songs...")
            val fallbackResult = searchSongs("top songs $region ${currentYear()}", limit)
            val fallbackSongs = fallbackResult.getOrDefault(emptyList())
            if (fallbackSongs.isNotEmpty()) {
                return@withContext Result.success(fallbackSongs)
            }
            Result.success(emptyList())

        } catch (e: Exception) {
            Log.e(TAG, "Trending songs fetch failed", e)
            val fallbackResult = runCatching { searchSongs("top trending songs", limit).getOrDefault(emptyList()) }.getOrDefault(emptyList())
            Result.success(fallbackResult)
        }
    }

    /**
     * Data class for browse results with continuation support.
     */
    data class BrowsePageResult(
        val items: List<Song>,
        val continuationToken: String? = null
    )

    /**
     * Get top artists from YouTube Music for the user's region.
     * Runs multiple broad search queries and aggregates unique results to get 50+.
     */
    suspend fun getTopArtists(limit: Int = 50): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val countryName = getCountryDisplayName(region)
            Log.d(TAG, "Fetching top artists for region: $region ($countryName)")

            val allArtists = mutableListOf<Song>()
            val seenIds = mutableSetOf<String>()

            // Multiple broad queries to gather many artists
            val queries = getArtistQueriesForRegion(countryName, region)

            for (query in queries) {
                if (allArtists.size >= limit) break
                try {
                    val page = searchArtistsOrAlbumsPage(
                        query = query,
                        filter = Innertube.SearchFilter.Artist,
                        region = region,
                        language = language,
                        continuationToken = null
                    )
                    if (page != null) {
                        for (artist in page.items) {
                            if (seenIds.add(artist.id)) {
                                allArtists.add(artist)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Artist query '$query' failed: ${e.message}")
                }
            }

            Log.d(TAG, "Got ${allArtists.size} unique artists for $region")
            Result.success(allArtists.take(limit))

        } catch (e: Exception) {
            Log.e(TAG, "Top artists error", e)
            Result.success(emptyList())
        }
    }

    /**
     * Get top albums from YouTube Music Charts.
     * Uses FEmusic_charts browse endpoint and parses the "Top Albums" section
     * via parseChartsAlbums(), which extracts musicTwoRowItemRenderer items
     * with MPRE browseIds.
     */
    suspend fun getTopAlbums(limit: Int = 50): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Fetching top albums from charts for region: $region")

            val requestBody = Innertube.webRemixBody(language, region) {
                put("browseId", YtmScopeConfig.PermittedBrowseIds.CHARTS)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Charts API failed for albums with HTTP ${response.code}")
                return@withContext Result.success(emptyList())
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.w(TAG, "Empty charts response for albums")
                return@withContext Result.success(emptyList())
            }

            val albums = parseChartsAlbums(responseBody, limit)
            Log.d(TAG, "Got ${albums.size} top albums from charts for $region")
            Result.success(albums)

        } catch (e: Exception) {
            Log.e(TAG, "Top albums fetch failed", e)
            Result.success(emptyList())
        }
    }

    /**
     * Load more artists using a continuation token from a previous search.
     */
    suspend fun loadMoreArtists(continuationToken: String): Result<BrowsePageResult> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val page = searchArtistsOrAlbumsPage(
                query = null,
                filter = Innertube.SearchFilter.Artist,
                region = region,
                language = language,
                continuationToken = continuationToken
            )
            Result.success(page ?: BrowsePageResult(emptyList()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search for albums only.
     */
    suspend fun searchAlbums(query: String): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val page = searchArtistsOrAlbumsPage(
                query = query,
                filter = Innertube.SearchFilter.Album,
                region = region,
                language = language,
                continuationToken = null
            )
            Result.success(page?.items ?: emptyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load more albums using a continuation token from a previous search.
     */
    suspend fun loadMoreAlbums(continuationToken: String): Result<BrowsePageResult> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val page = searchArtistsOrAlbumsPage(
                query = null,
                filter = Innertube.SearchFilter.Album,
                region = region,
                language = language,
                continuationToken = continuationToken
            )
            Result.success(page ?: BrowsePageResult(emptyList()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Core helper: search for artists or albums with continuation support.
     * If continuationToken is provided, loads next page (query is ignored).
     */
    private suspend fun searchArtistsOrAlbumsPage(
        query: String?,
        filter: Innertube.SearchFilter,
        region: String,
        language: String,
        continuationToken: String?
    ): BrowsePageResult? {
        if (continuationToken == null && query == null) {
            return null
        }

        val requestBody = Innertube.webRemixBody(language, region) {
            if (continuationToken != null) {
                put("continuation", continuationToken)
            } else if (query != null) {
                put("query", query)
                put("params", filter.value)
            }
        }

        val request = Innertube.musicPost(
            endpoint = Innertube.SEARCH,
            body = requestBody,
            acceptLanguage = getAcceptLanguageHeader(language, region)
        )

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val responseBody = response.body?.string() ?: return null

        val isArtist = filter == Innertube.SearchFilter.Artist
        return if (isArtist) {
            parseArtistSearchResponseWithContinuation(responseBody)
        } else {
            parseAlbumSearchResponseWithContinuation(responseBody)
        }
    }

    /**
     * Get region-specific search queries to aggregate many unique artists.
     */
    private fun getArtistQueriesForRegion(countryName: String, regionCode: String): List<String> {
        val base = listOf(countryName)
        val regionSpecific = when (regionCode.uppercase()) {
            "IN" -> listOf(" Bollywood", " Hindi music", " Punjabi music", " Tamil music", " Telugu music", " Indian pop", " playback singer")
            "US" -> listOf(" pop music", " hip hop", " R&B", " country music", " rock music", " American pop")
            "GB" -> listOf(" British pop", " UK music", " grime", " indie rock", " British rock")
            "KR" -> listOf(" K-pop", " Korean pop", " Korean R&B", " Korean hip hop")
            "JP" -> listOf(" J-pop", " Japanese pop", " anime music", " Japanese rock")
            "BR" -> listOf(" sertanejo", " Brazilian pop", " MPB", " funk brasileiro", " pagode")
            "MX" -> listOf(" reggaeton", " Latin pop", " Mexican music", " banda", " corridos")
            "DE" -> listOf(" German pop", " Schlager", " German hip hop", " Deutsche Musik")
            "FR" -> listOf(" French pop", " chanson", " French hip hop", " musique française")
            "ES" -> listOf(" Spanish pop", " reggaeton", " Latin pop", " flamenco")
            else -> listOf("pop music", " hip hop", " rock music", " trending music")
        }
        return base + regionSpecific + listOf("popular music", " trending")
    }

    /**
     * Get region-specific search queries for albums.
     */
    private fun getAlbumQueriesForRegion(countryName: String, regionCode: String): List<String> {
        val base = listOf(countryName)
        val regionSpecific = when (regionCode.uppercase()) {
            "IN" -> listOf(" Bollywood album", " Hindi album", " Punjabi album", " Tamil album", " new release India")
            "US" -> listOf(" new album", " pop album", " hip hop album", " best album", " trending album")
            "GB" -> listOf(" British album", " UK album", " indie album", " new release UK")
            "KR" -> listOf(" K-pop album", " Korean album", " new release Korea")
            "JP" -> listOf(" J-pop album", " Japanese album", " anime album")
            else -> listOf("new album", " popular album", " best album", " top album")
        }
        return base + regionSpecific + listOf("music album", " new release")
    }

    /**
     * Parse artist search response with continuation token for Load More.
     */
    private fun parseArtistSearchResponseWithContinuation(jsonString: String): BrowsePageResult {
        val items = parseArtistSearchResponse(jsonString)
        val continuation = extractBrowseContinuationToken(jsonString)
        return BrowsePageResult(items, continuation)
    }

    /**
     * Parse album search response with continuation token for Load More.
     */
    private fun parseAlbumSearchResponseWithContinuation(jsonString: String): BrowsePageResult {
        val items = parseAlbumSearchResponse(jsonString)
        val continuation = extractBrowseContinuationToken(jsonString)
        return BrowsePageResult(items, continuation)
    }

    private fun isAlbumBrowseId(browseId: String): Boolean {
        return browseId.startsWith("MPRE") || browseId.startsWith(" MPLA")
    }

    /**
     * Extract continuation token from a search response's musicShelfRenderer.
     */
    private fun extractBrowseContinuationToken(jsonString: String): String? {
        try {
            val json = JSONObject(jsonString)

            // Check continuationContents (for continuation pages)
            val contShelf = json.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
            if (contShelf != null) {
                return extractMusicShelfContinuationToken(contShelf)
            }

            // Check initial response tabs
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")

            if (tabs != null) {
                for (t in 0 until tabs.length()) {
                    val tabContents = tabs.optJSONObject(t)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?: continue

                    for (s in 0 until tabContents.length()) {
                        val shelf = tabContents.optJSONObject(s)
                            ?.optJSONObject("musicShelfRenderer")
                            ?: continue
                        val token = extractMusicShelfContinuationToken(shelf)
                        if (token != null) return token
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Parse artist items from a YTM search response filtered by Artist type.
     * Extracts browseId from multiple locations matching the parseMixedItem pattern.
     */
    private fun parseArtistSearchResponse(jsonString: String): List<Song> {
        val artists = mutableListOf<Song>()

        try {
            val json = JSONObject(jsonString)

            // Extract all musicShelfRenderers (from initial tabs or continuation)
            val shelves = mutableListOf<JSONObject>()

            // 1. Check for continuation format
            val contShelf = json.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
            if (contShelf != null) {
                shelves.add(contShelf)
            }

            // 2. Check for initial search format (tabs)
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

                    for (s in 0 until tabContent.length()) {
                        val section = tabContent.optJSONObject(s) ?: continue
                        val shelf = section.optJSONObject("musicShelfRenderer")
                        if (shelf != null) shelves.add(shelf)
                    }
                }
            }

            // Parse extracted shelves
            for (shelf in shelves) {
                val shelfContents = shelf.optJSONArray("contents") ?: continue

                for (i in 0 until shelfContents.length()) {
                    val item = shelfContents.optJSONObject(i) ?: continue
                    val listItem = item.optJSONObject("musicResponsiveListItemRenderer")
                        ?: continue

                    // Extract browseId from multiple locations (like parseMixedItem)
                    val flexColumns = listItem.optJSONArray("flexColumns")

                    // Location 1: root navigationEndpoint
                    var browseId = listItem.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId", "")
                        ?: ""

                    // Location 2: flexColumns[0] title runs navigationEndpoint
                    if (browseId.isBlank() && flexColumns != null && flexColumns.length() > 0) {
                        val titleRuns = flexColumns.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (titleRuns != null && titleRuns.length() > 0) {
                            browseId = titleRuns.optJSONObject(0)
                                ?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("browseId", "")
                                ?: ""
                        }
                    }

                    // Location 3: overlay play button
                    if (browseId.isBlank()) {
                        browseId = listItem.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                            ?.optJSONObject("playNavigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId", "")
                            ?: ""
                    }

                    // Extract artist name from flexColumns
                    val name = if (flexColumns != null && flexColumns.length() > 0) {
                        extractText(
                            flexColumns.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                        )
                    } else ""

                    if (name.isBlank()) continue

                    // Use name as fallback ID if browseId is empty
                    val effectiveId = browseId.ifBlank { name }

                    // Extract subtitle (e.g., subscriber count)
                    val subtitle = if (flexColumns != null && flexColumns.length() > 1) {
                        extractText(
                            flexColumns.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                        )
                    } else ""

                    // Extract thumbnail
                    val thumbnails = listItem.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")

                    val thumbnailUrl = extractHighestQualityThumbnail(
                        thumbnails = thumbnails,
                        fallback = ""
                    )

                    val resolvedArtistBrowseId = browseId.takeIf { it.startsWith("UC") } ?: effectiveId.takeIf { it.startsWith("UC") }
                    artists.add(Song(
                        id = effectiveId,
                        title = name,
                        artist = subtitle.takeIf { it.isNotBlank() } ?: "Artist",
                        artistId = resolvedArtistBrowseId,
                        duration = 0,
                        thumbnailUrl = thumbnailUrl,
                        contentType = ContentType.ARTIST,
                        artists = listOf(ArtistRef(name = name, browseId = resolvedArtistBrowseId))
                    ))
                } // End of shelfContents loop (i)
            } // End of shelves loop
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing artist search response", e)
        }

        return artists.distinctBy { it.id }
    }

    /**
     * Parse album items from a YTM search response filtered by Album type.
     * Extracts browseId from multiple locations.
     */
    private fun parseAlbumSearchResponse(jsonString: String): List<Song> {
        val albums = mutableListOf<Song>()

        try {
            val json = JSONObject(jsonString)

            // Extract all musicShelfRenderers (from initial tabs or continuation)
            val shelves = mutableListOf<JSONObject>()

            // 1. Check for continuation format
            val contShelf = json.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
            if (contShelf != null) {
                shelves.add(contShelf)
            }

            // 2. Check for initial search format (tabs)
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

                    for (s in 0 until tabContent.length()) {
                        val section = tabContent.optJSONObject(s) ?: continue
                        val shelf = section.optJSONObject("musicShelfRenderer")
                        if (shelf != null) shelves.add(shelf)
                    }
                }
            }

            // Parse extracted shelves
            for (shelf in shelves) {
                val shelfContents = shelf.optJSONArray("contents") ?: continue

                for (i in 0 until shelfContents.length()) {
                    val item = shelfContents.optJSONObject(i) ?: continue
                    val listItem = item.optJSONObject("musicResponsiveListItemRenderer")
                        ?: continue

                    val flexColumns = listItem.optJSONArray("flexColumns")

                    // Extract browseId from multiple locations
                    var browseId = listItem.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId", "")
                        ?: ""

                    if (browseId.isBlank() && flexColumns != null && flexColumns.length() > 0) {
                        val titleRuns = flexColumns.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (titleRuns != null && titleRuns.length() > 0) {
                            browseId = titleRuns.optJSONObject(0)
                                ?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("browseId", "")
                                ?: ""
                        }
                    }

                    // Extract album title from flexColumns
                    val albumTitle = if (flexColumns != null && flexColumns.length() > 0) {
                        extractText(
                            flexColumns.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                        )
                    } else ""

                    if (albumTitle.isBlank()) continue

                    if (!isAlbumBrowseId(browseId)) continue

                    // Extract artist name from subtitle runs
                    val subtitleRuns = if (flexColumns != null && flexColumns.length() > 1) {
                        flexColumns.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                    } else null

                    val subtitleText = if (flexColumns != null && flexColumns.length() > 1) {
                        extractText(
                            flexColumns.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                        )
                    } else ""

                    val artistName = if (subtitleRuns != null) {
                        val refs = extractArtistAndAlbumRefs(subtitleRuns)
                        refs.artists.firstOrNull()?.name
                            ?: extractFirstNonSeparatorRun(subtitleRuns)
                    } else {
                        subtitleText
                    }

                    // Extract thumbnail
                    val thumbnails = listItem.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")

                    val thumbnailUrl = extractHighestQualityThumbnail(
                        thumbnails = thumbnails,
                        fallback = ""
                    )

                    val albumSubtitleRefs = subtitleRuns?.let { extractArtistAndAlbumRefs(it) }
                    val albumArtistRefs = if (albumSubtitleRefs != null && albumSubtitleRefs.artists.isNotEmpty()) {
                        albumSubtitleRefs.artists.map { ArtistRef(it.name, it.browseId) }
                    } else if (artistName.isNotBlank()) {
                        listOf(ArtistRef(cleanArtistName(artistName), null))
                    } else emptyList()
                    val albumArtistId = albumArtistRefs.mapNotNull { it.browseId }.joinToString(",").takeIf { it.isNotBlank() }

                    albums.add(Song(
                        id = browseId,
                        title = albumTitle,
                        artist = cleanArtistName(artistName),
                        artistId = albumArtistId,
                        albumId = browseId,
                        duration = 0,
                        thumbnailUrl = thumbnailUrl,
                        contentType = ContentType.ALBUM,
                        artists = albumArtistRefs
                    ))
                } // End of shelfContents loop (i)
            } // End of shelves loop
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing album search response", e)
        }

        return albums.distinctBy { it.id }
    }

    /**
     * Get human-readable country name from country code for search queries.
     */
    private fun getCountryDisplayName(countryCode: String): String {
        return try {
            java.util.Locale("", countryCode).displayCountry.takeIf { it.isNotBlank() } ?: countryCode
        } catch (e: Exception) {
            countryCode
        }
    }

    /**
     * Parse artists from charts response.
     * Looks for musicTwoRowItemRenderer items with browseEndpoint (browseId starting with "UC").
     */
    private fun parseChartsArtists(jsonString: String, limit: Int): List<Song> {
        val artists = mutableListOf<Song>()

        try {
            val json = JSONObject(jsonString)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return artists

            for (t in 0 until tabs.length()) {
                val sections = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?: continue

                for (s in 0 until sections.length()) {
                    val section = sections.optJSONObject(s) ?: continue

                    // Check carousel shelf header to find "Top artists" section
                    val shelf = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                    val shelfTitle = shelf.optJSONObject("header")
                        ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                        ?.optJSONObject("title")
                    val titleText = extractText(shelfTitle).lowercase()

                    // Match artist section by title or by content type
                    val isArtistSection = titleText.contains("artist") ||
                        titleText.contains("trending artist")

                    val contents = shelf.optJSONArray("contents") ?: continue

                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i) ?: continue
                        val twoRow = item.optJSONObject("musicTwoRowItemRenderer") ?: continue

                        val browseEndpoint = twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                        val browseId = browseEndpoint?.optString("browseId", "") ?: ""

                        // Artist browseIds start with "UC"
                        if (browseId.startsWith("UC") || isArtistSection) {
                            val name = extractText(twoRow.optJSONObject("title"))
                            if (name.isBlank()) continue

                            val subtitle = extractText(twoRow.optJSONObject("subtitle"))

                            val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")

                            val thumbnailUrl = extractHighestQualityThumbnail(
                                thumbnails = thumbnails,
                                fallback = ""
                            )

                            val resolvedArtistBrowseId = browseId.takeIf { it.startsWith("UC") }
                            artists.add(Song(
                                id = browseId.ifBlank { name },
                                title = name,
                                artist = subtitle.takeIf { it.isNotBlank() } ?: "Artist",
                                artistId = resolvedArtistBrowseId,
                                artists = listOf(ArtistRef(name = name, browseId = resolvedArtistBrowseId)),
                                duration = 0,
                                thumbnailUrl = thumbnailUrl,
                                contentType = ContentType.ARTIST
                            ))

                            if (artists.size >= limit) return artists
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing charts artists", e)
        }

        return artists.distinctBy { it.id }
    }

    /**
     * Parse albums from charts response.
     * Looks for musicTwoRowItemRenderer items with browse endpoints for albums.
     */
    private fun parseChartsAlbums(jsonString: String, limit: Int): List<Song> {
        val albums = mutableListOf<Song>()

        try {
            val json = JSONObject(jsonString)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return albums

            for (t in 0 until tabs.length()) {
                val sections = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?: continue

                for (s in 0 until sections.length()) {
                    val section = sections.optJSONObject(s) ?: continue
                    val shelf = section.optJSONObject("musicCarouselShelfRenderer") ?: continue

                    val contents = shelf.optJSONArray("contents") ?: continue

                    for (i in 0 until contents.length()) {
                        val item = contents.optJSONObject(i) ?: continue
                        val twoRow = item.optJSONObject("musicTwoRowItemRenderer") ?: continue

                        val browseEndpoint = twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                        val browseId = browseEndpoint?.optString("browseId", "") ?: ""

                        // Album browseIds are required for the detail pipeline.
                        if (isAlbumBrowseId(browseId)) {
                            val albumTitle = extractText(twoRow.optJSONObject("title"))
                            if (albumTitle.isBlank()) continue

                            val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                            val albumArtistRefs = mutableListOf<ArtistRef>()
                            var primaryArtistBrowseId: String? = null
                            val artistName = if (subtitleRuns != null) {
                                val refs = extractArtistAndAlbumRefs(subtitleRuns)
                                albumArtistRefs.addAll(refs.artists.map { ArtistRef(it.name, it.browseId) })
                                primaryArtistBrowseId = refs.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                                refs.artists.firstOrNull()?.name
                                    ?: extractFirstNonSeparatorRun(subtitleRuns)
                            } else {
                                extractText(twoRow.optJSONObject("subtitle"))
                            }
                            val cleanedArtist = cleanArtistName(artistName)
                            if (albumArtistRefs.isEmpty() && cleanedArtist.isNotBlank()) {
                                albumArtistRefs.add(ArtistRef(cleanedArtist, primaryArtistBrowseId))
                            }

                            val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")

                            val thumbnailUrl = extractHighestQualityThumbnail(
                                thumbnails = thumbnails,
                                fallback = ""
                            )

                            albums.add(Song(
                                id = browseId,
                                title = albumTitle,
                                artist = cleanedArtist,
                                artistId = primaryArtistBrowseId,
                                artists = albumArtistRefs,
                                albumId = browseId,
                                duration = 0,
                                thumbnailUrl = thumbnailUrl,
                                contentType = ContentType.ALBUM
                            ))

                            if (albums.size >= limit) return albums
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing charts albums", e)
        }

        return albums.distinctBy { it.id }
    }

    /**
     * Get New Releases from YouTube Music.
     * Uses FEmusic_new_releases_albums — the correct browse endpoint for
     * official new album/single releases.
     */
    suspend fun getNewReleases(limit: Int = 25): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Fetching new releases for region: $region")

            val requestBody = Innertube.webRemixBody(language, region) {
                put("browseId", YtmScopeConfig.PermittedBrowseIds.NEW_RELEASES_ALBUMS)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "New releases API failed with HTTP ${response.code}")
                return@withContext Result.success(emptyList())
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.w(TAG, "Empty response from FEmusic_new_releases_albums")
                return@withContext Result.success(emptyList())
            }

            val songs = sanitizeSongResults(
                songs = parseBrowseResponse(responseBody),
                limit = limit,
                offset = 0
            )

            if (songs.isNotEmpty()) {
                Log.d(TAG, "Got ${songs.size} new releases for $region")
                return@withContext Result.success(songs)
            }

            Log.w(TAG, "New releases returned empty, falling back to search query...")
            val fallbackResult = searchSongs("latest new songs $region ${currentYear()}", limit)
            val fallbackSongs = fallbackResult.getOrDefault(emptyList())
            if (fallbackSongs.isNotEmpty()) {
                return@withContext Result.success(fallbackSongs)
            }
            Result.success(emptyList())

        } catch (e: Exception) {
            Log.e(TAG, "New releases fetch failed", e)
            val fallbackResult = runCatching { searchSongs("new music releases", limit).getOrDefault(emptyList()) }.getOrDefault(emptyList())
            Result.success(fallbackResult)
        }
    }

    suspend fun getEnglishHits(limit: Int = 25): Result<List<Song>> = withContext(Dispatchers.IO) {
        // Use specific search for English songs with current year
        searchSongs("top English songs ${currentYear()} trending", limit)
    }

    /**
     * Detect country code directly using YouTube Music Innertube API response context
     */
    suspend fun detectCountryFromInnertube(): String? = withContext(Dispatchers.IO) {
        try {
            // Make a lightweight browse request to FEmusic_home with blank hl/gl
            val requestBody = Innertube.buildYtmContext(language = "en", region = "") {
                put("browseId", YtmBrowseId.Home.value)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.BROWSE,
                body = requestBody,
                acceptLanguage = "en-US,en;q=0.9",
                fieldMask = "responseContext"
            )

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body?.string() ?: return@withContext null
            val root = JSONObject(responseBody)
            val context = root.optJSONObject("responseContext") ?: return@withContext null

            // 1. Try parsing from visitorData first (very reliable IP-based country)
            val visitorData = context.optString("visitorData")
            if (!visitorData.isNullOrBlank()) {
                try {
                    val decodedVisitorData = java.net.URLDecoder.decode(visitorData, "UTF-8")
                    val parsedCountry = parseCountryFromVisitorData(decodedVisitorData)
                    if (parsedCountry != null) {
                        Log.d(TAG, "Innertube detected region from visitorData: $parsedCountry")
                        return@withContext parsedCountry
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse country from visitorData", e)
                }
            }

            // 2. Fallback: Parse from serviceTrackingParams
            val trackingParams = context.optJSONArray("serviceTrackingParams") ?: return@withContext null
            for (i in 0 until trackingParams.length()) {
                val serviceParam = trackingParams.optJSONObject(i) ?: continue
                val params = serviceParam.optJSONArray("params") ?: continue
                for (j in 0 until params.length()) {
                    val param = params.optJSONObject(j) ?: continue
                    val key = param.optString("key").trim()
                    if (key == "country" || key == "c") {
                        val country = param.optString("value")
                        if (!country.isNullOrBlank() && country.length == 2 && country.all { it.isLetter() }) {
                            Log.d(TAG, "Innertube detected region: $country")
                            return@withContext country.uppercase(Locale.US)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect region from Innertube", e)
        }
        null
    }

    private fun parseCountryFromVisitorData(visitorData: String): String? {
        try {
            val decoded = android.util.Base64.decode(visitorData, android.util.Base64.DEFAULT)
            // Look for field 6 (tag 0x32)
            for (i in 0 until decoded.size - 5) {
                if (decoded[i] == 0x32.toByte() &&
                    decoded[i + 2] == 0x0A.toByte() &&
                    decoded[i + 3] == 0x02.toByte()) {
                    val c1 = decoded[i + 4].toInt().toChar()
                    val c2 = decoded[i + 5].toInt().toChar()
                    if (c1 in 'A'..'Z' && c2 in 'A'..'Z') {
                        return "$c1$c2"
                    }
                }
            }
            // Fallback: search for any 0x0A, 0x02, [A-Z], [A-Z]
            for (i in 0 until decoded.size - 3) {
                if (decoded[i] == 0x0A.toByte() &&
                    decoded[i + 1] == 0x02.toByte()) {
                    val c1 = decoded[i + 2].toInt().toChar()
                    val c2 = decoded[i + 3].toInt().toChar()
                    if (c1 in 'A'..'Z' && c2 in 'A'..'Z') {
                        return "$c1$c2"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse country from visitorData", e)
        }
        return null
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
            Log.e(TAG, "Search suggestions error", e)
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
                .header("User-Agent", " Mozilla/5.0")
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

            // Two-column structure (typically albums/playlists detail page)
            val twoColumnContents = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            
            if (twoColumnContents != null) {
                songs.addAll(parseSectionContents(twoColumnContents))
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

            // Try gridRenderer (used by FEmusic_new_releases_albums)
            val gridContents = section.optJSONObject("gridRenderer")
                ?.optJSONArray("items")
            
            if (gridContents != null) {
                for (j in 0 until gridContents.length()) {
                    val item = gridContents.optJSONObject(j) ?: continue
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
                val itemArtistRefs = mutableListOf<ArtistRef>()
                var primaryArtistBrowseId: String? = null
                val artist = if (subtitleRuns != null) {
                    val refs = extractArtistAndAlbumRefs(subtitleRuns)
                    itemArtistRefs.addAll(refs.artists.map { ArtistRef(it.name, it.browseId) })
                    primaryArtistBrowseId = refs.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                    refs.artists.firstOrNull()?.name
                        ?: extractFirstNonSeparatorRun(subtitleRuns)
                } else {
                    extractText(twoRow.optJSONObject("subtitle"))
                }
                val cleanedArtist = cleanArtistName(artist)
                if (itemArtistRefs.isEmpty() && cleanedArtist.isNotBlank()) {
                    itemArtistRefs.add(ArtistRef(cleanedArtist, primaryArtistBrowseId))
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
                    Log.d(TAG, "Filtering non-music from browse: $title ($contentType)")
                    return null
                }

                return Song(
                    id = videoId,
                    title = title,
                    artist = cleanedArtist,
                    artistId = primaryArtistBrowseId,
                    artists = itemArtistRefs,
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
    suspend fun getUpNext(videoId: String): Result<Pair<List<Song>, String?>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Fetching Up Next for: $videoId")
            
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
            val parsed = parseQueueResponse(json)
            val queueItems = sanitizeSongResults(
                songs = parsed.first,
                limit = 0,
                offset = 0
            )
            
            Log.d(TAG, "Got ${queueItems.size} songs from Up Next")
            Result.success(Pair(queueItems, parsed.second))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Get Up Next error", e)
            Result.failure(e)
        }
    }

    /**
     * Get Radio Mix (RDAMVM) from YouTube Music
     * This creates a proper "Radio" station based on the song
     */
    suspend fun getRadioMix(videoId: String): Result<Pair<List<Song>, String?>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Fetching Radio Mix for: $videoId")
            
            // RDAMVM is the prefix for "Radio Mix" playlists in YouTube Music
            // It stands for "Radio Mix" + videoId
            val playlistId = "RDAMVM$videoId"
            
            val requestBody = Innertube.webRemixBody(language, region) {
                put("videoId", videoId)
                put("playlistId", playlistId)
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
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
            val parsed = parseQueueResponse(json)
            val queueItems = sanitizeSongResults(
                songs = parsed.first,
                limit = 0,
                offset = 0
            )
            
            // Filter out the seed song if it appears first
            val filteredItems = if (queueItems.isNotEmpty() && queueItems[0].id == videoId) {
                queueItems.drop(1)
            } else {
                queueItems
            }
            
            Log.d(TAG, "Got ${filteredItems.size} songs from Radio Mix")
            Result.success(Pair(filteredItems, parsed.second))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Get Radio Mix error", e)
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
                        getUpNext(seedVideoId).getOrNull()?.first.orEmpty()
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
                    getRadioMix(seedVideoId).getOrNull()?.first.orEmpty()
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
            val requestBody = Innertube.buildYtmContext(language, region) {
                put("videoId", videoId)
                put("racyCheckOk", true)
                put("contentCheckOk", true)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.NEXT,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
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

    private fun parseQueueResponse(json: JSONObject): Pair<List<Song>, String?> {
        val songs = mutableListOf<Song>()
        var continuationToken: String? = null
        
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

            if (playlistPanel == null) {
                playlistPanel = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                    ?.optJSONObject("playlist")
                    ?.optJSONObject("playlistPanelRenderer")
            }
            
            if (playlistPanel == null) return Pair(songs, null)
            
            val contents = playlistPanel.optJSONArray("contents") ?: return Pair(songs, null)
            
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
                
                val refs = extractArtistAndAlbumRefs(longBylineRuns ?: shortBylineRuns)
                val albumName = refs.album?.name
                val albumBrowseId = refs.album?.browseId
                
                val durationText = extractText(item.optJSONObject("lengthText"))
                val contentType = detectContentType(title, artist, durationText)
                // Accept SONG, VIDEO, and UNKNOWN from Radio Mix / Up Next — these are music-only endpoints.
                // Only reject clearly non-music types (PODCAST, LIVE_STREAM, ALBUM, SHORT).
                if (contentType == ContentType.PODCAST || contentType == ContentType.LIVE_STREAM || contentType == ContentType.ALBUM) {
                    Log.d(TAG, "Filtering non-song queue item: $title ($contentType)")
                    continue
                }
                
                val thumbnails = item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbnailUrl = extractHighestQualityThumbnail(
                    thumbnails = thumbnails,
                    fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                )
                
                val queueArtistRefs = if (artistInfo.individuals.isNotEmpty()) {
                    artistInfo.individuals.map { ArtistRef(it.name, it.browseId) }
                } else if (refs.artists.isNotEmpty()) {
                    refs.artists.map { ArtistRef(it.name, it.browseId) }
                } else {
                    listOf(ArtistRef(cleanArtistName(artist), null))
                }
                val primaryArtistBrowseId = queueArtistRefs.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                    ?: if (artistInfo.individuals.isNotEmpty()) {
                        artistInfo.individuals.map { it.browseId.orEmpty() }.joinToString(",")
                    } else null

                songs.add(Song(
                    id = videoId,
                    title = title,
                    artist = cleanArtistName(artist),
                    artistId = primaryArtistBrowseId,
                    artists = queueArtistRefs,
                    album = albumName,
                    albumId = albumBrowseId,
                    duration = parseDuration(durationText),
                    thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                    category = "Music",
                    contentType = ContentType.SONG,
                    albumBrowseId = albumBrowseId,
                    albumName = albumName
                ))
            }

            continuationToken = playlistPanel.optJSONArray("continuations")
                ?.optJSONObject(0)
                ?.optJSONObject("nextContinuationData")
                ?.optString("continuation")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing queue", e)
        }
        
        return Pair(songs, continuationToken)
    }

    /**
     * Get queue continuation tracks using the continuation token
     */
    suspend fun getQueueContinuation(continuationToken: String): Result<Pair<List<Song>, String?>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            Log.d(TAG, "Fetching Queue Continuation with token: $continuationToken")

            val requestBody = Innertube.webRemixBody(language, region) {
                put("continuation", continuationToken)
                put("isAudioOnly", true)
            }

            val request = Innertube.musicPost(
                endpoint = Innertube.NEXT,
                body = requestBody,
                acceptLanguage = getAcceptLanguageHeader(language, region)
            )

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Continuation failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JSONObject(responseBody)

            val parsed = parseQueueContinuationResponse(json)
            val queueItems = sanitizeSongResults(
                songs = parsed.first,
                limit = 0,
                offset = 0
            )

            Log.d(TAG, "Got ${queueItems.size} songs from queue continuation")
            Result.success(Pair(queueItems, parsed.second))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Get Queue Continuation error", e)
            Result.failure(e)
        }
    }

    /**
     * Parse queue continuation response (playlistPanelContinuation)
     */
    private fun parseQueueContinuationResponse(json: JSONObject): Pair<List<Song>, String?> {
        val songs = mutableListOf<Song>()
        var continuationToken: String? = null

        try {
            val continuationContents = json.optJSONObject("continuationContents")
            val playlistPanelContinuation = continuationContents?.optJSONObject("playlistPanelContinuation")
                ?: json.optJSONObject("playlistPanelContinuation") // fallback

            if (playlistPanelContinuation != null) {
                val contents = playlistPanelContinuation.optJSONArray("contents")
                if (contents != null) {
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

                        val refs = extractArtistAndAlbumRefs(longBylineRuns ?: shortBylineRuns)
                        val albumName = refs.album?.name
                        val albumBrowseId = refs.album?.browseId

                        val durationText = extractText(item.optJSONObject("lengthText"))
                        val contentType = detectContentType(title, artist, durationText)
                        // Accept SONG, VIDEO, and UNKNOWN — only reject clearly non-music types.
                        if (contentType == ContentType.PODCAST || contentType == ContentType.LIVE_STREAM || contentType == ContentType.ALBUM) {
                            Log.d(TAG, "Filtering non-song queue continuation item: $title ($contentType)")
                            continue
                        }

                        val thumbnails = item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val thumbnailUrl = extractHighestQualityThumbnail(
                            thumbnails = thumbnails,
                            fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                        )

                        val queueArtistRefs = if (artistInfo.individuals.isNotEmpty()) {
                            artistInfo.individuals.map { ArtistRef(it.name, it.browseId) }
                        } else if (refs.artists.isNotEmpty()) {
                            refs.artists.map { ArtistRef(it.name, it.browseId) }
                        } else {
                            listOf(ArtistRef(cleanArtistName(artist), null))
                        }
                        val primaryArtistBrowseId = queueArtistRefs.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                            ?: if (artistInfo.individuals.isNotEmpty()) {
                                artistInfo.individuals.map { it.browseId.orEmpty() }.joinToString(",")
                            } else null

                        songs.add(Song(
                            id = videoId,
                            title = title,
                            artist = cleanArtistName(artist),
                            artistId = primaryArtistBrowseId,
                            artists = queueArtistRefs,
                            album = albumName,
                            albumId = albumBrowseId,
                            duration = parseDuration(durationText),
                            thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                            category = "Music",
                            contentType = ContentType.SONG,
                            albumBrowseId = albumBrowseId,
                            albumName = albumName
                        ))
                    }
                }

                continuationToken = playlistPanelContinuation.optJSONArray("continuations")
                    ?.optJSONObject(0)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing queue continuation", e)
        }

        return Pair(songs, continuationToken)
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

        val best = candidates.maxByOrNull { candidate ->
            var score = 0
            val title = candidate.title.lowercase().replace(Regex("\\s+"), " ").trim()
            if (title == normalizedQuery) score += 10
            if (title.contains(normalizedQuery) || normalizedQuery.contains(title)) score += 6
            if (!candidate.thumbnailUrl.isNullOrBlank()) score += 2
            if (!extractSubscriberSnippet(candidate.artist).isNullOrBlank()) score += 3
            score
        } ?: return null
        // Require name overlap — never return an unrelated artist
        val bestTitle = best.title.lowercase().replace(Regex("\\s+"), " ").trim()
        val hasNameMatch = bestTitle == normalizedQuery ||
            bestTitle.contains(normalizedQuery) ||
            normalizedQuery.contains(bestTitle)
        return if (hasNameMatch) best else null
    }

    private fun selectBestLooseArtistCandidate(query: String, candidates: List<Song>): Song? {
        if (candidates.isEmpty()) return null
        val normalizedQuery = normalizeArtistToken(query)
        if (normalizedQuery.isBlank()) return null

        val best = candidates
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
            } ?: return null
        // Require at least a partial name match — never return a completely unrelated result
        val bestTitle = normalizeArtistToken(best.title)
        val hasNameMatch = bestTitle == normalizedQuery ||
            bestTitle.contains(normalizedQuery) ||
            normalizedQuery.contains(bestTitle)
        return if (hasNameMatch) best else null
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
        val artistBrowseId = if (subtitleRefs.artists.isNotEmpty()) {
            subtitleRefs.artists.map { it.browseId.orEmpty() }.joinToString(",")
        } else null
        val albumName = subtitleRefs.album?.name
        val albumBrowseId = subtitleRefs.album?.browseId

        val normalizedThumb = if (isLikelyVideoId(resolvedId)) {
            upgradeThumbQuality(thumbnailUrl, resolvedId)
        } else {
            normalizeAlbumThumbnail(thumbnailUrl, resolvedId)
        }

        val sectionArtistRefs = if (subtitleRefs.artists.isNotEmpty()) {
            subtitleRefs.artists.map { ArtistRef(it.name, it.browseId) }
        } else if (artist.isNotBlank()) {
            listOf(ArtistRef(artist, artistBrowseId))
        } else {
            emptyList()
        }
        val primaryArtistBrowseId = sectionArtistRefs.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId ?: artistBrowseId

        return if (contentType == ContentType.ALBUM) {
            Song(
                id = resolvedId,
                title = title,
                artist = artist,
                artistId = primaryArtistBrowseId,
                artists = sectionArtistRefs,
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
                artistId = primaryArtistBrowseId,
                artists = sectionArtistRefs,
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
        val artistBrowseId = if (subtitleRefs.artists.isNotEmpty()) {
            subtitleRefs.artists.map { it.browseId.orEmpty() }.joinToString(",")
        } else null
        val albumName = subtitleRefs.album?.name
        val albumBrowseId = subtitleRefs.album?.browseId
        val normalizedThumb = if (isLikelyVideoId(resolvedId)) {
            upgradeThumbQuality(thumbnailUrl, resolvedId)
        } else {
            normalizeAlbumThumbnail(thumbnailUrl, resolvedId)
        }

        val itemArtistRefs = if (contentType == ContentType.ARTIST) {
            val canonicalBrowseId = resolvedId.takeIf { it.startsWith("UC") }
            listOf(ArtistRef(name = title, browseId = canonicalBrowseId))
        } else if (subtitleRefs.artists.isNotEmpty()) {
            subtitleRefs.artists.map { ArtistRef(it.name, it.browseId) }
        } else if (artist.isNotBlank()) {
            listOf(ArtistRef(artist, artistBrowseId))
        } else {
            emptyList()
        }

        val itemArtistId = if (contentType == ContentType.ARTIST) {
            resolvedId.takeIf { it.startsWith("UC") }
        } else {
            itemArtistRefs.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId ?: artistBrowseId
        }

        return Song(
            id = resolvedId,
            title = title,
            artist = artist,
            artistId = itemArtistId,
            artists = itemArtistRefs,
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
        if (id.startsWith("MPRE") || id.startsWith(" MPLA")) return ContentType.ALBUM
        if (id.startsWith("VL") || id.startsWith(" PL") || id.startsWith(" RD")) return ContentType.PLAYLIST

        if (
            lowerSection.contains("related") ||
            lowerSection.contains("fans might also like") ||
            lowerSection.contains("fans also like") ||
            lowerSection.contains("fans also")
        ) {
            return ContentType.ARTIST
        }
        if (lowerSection.contains("featured on")) return ContentType.PLAYLIST
        if (lowerSection.contains("album") || lowerSection.contains(" single") || lowerSection.contains(" ep")) {
            return ContentType.ALBUM
        }
        if (lowerSection.contains("video")) return ContentType.VIDEO

        if (lowerSubtitle.contains("artist")) return ContentType.ARTIST
        if (lowerSubtitle.contains("album") || lowerSubtitle.contains(" single") || lowerSubtitle.contains(" ep")) {
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

        val songsKeywords = listOf("songs", " top songs", " popular songs", "노래", "曲", " canciones")
        val albumsKeywords = listOf("album", " albums", "앨범", "アルバム", "álbum")
        val singlesKeywords = listOf("single", " singles", " ep", "싱글", "シングル", " sencillo")
        val videosKeywords = listOf("video", " videos", "동영상", "動画", " vídeo")
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
        val playlistsKeywords = listOf("playlist", " playlists", "재생목록", "プレイリスト", " lista")

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
        val albumArtistRefs = mutableListOf<ArtistRef>()
        var primaryArtistBrowseId: String? = null
        val artist = if (subtitleRuns != null && subtitleRuns.length() > 0) {
            val refs = extractArtistAndAlbumRefs(subtitleRuns)
            albumArtistRefs.addAll(refs.artists.map { ArtistRef(it.name, it.browseId) })
            primaryArtistBrowseId = refs.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
            val extracted = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(subtitleRuns.toRunList()).displayName
            extracted.ifBlank { refs.artists.firstOrNull()?.name ?: "" }
        } else {
            parseArtistFromSubtitle(subtitle)
        }.ifBlank { fallbackArtistName }
        if (albumArtistRefs.isEmpty() && artist.isNotBlank()) {
            albumArtistRefs.add(ArtistRef(artist, primaryArtistBrowseId))
        }
        
        val year = parseYearToken(subtitle)

        return Song(
            id = browseId,
            title = title,
            artist = artist,
            artistId = primaryArtistBrowseId,
            artists = albumArtistRefs,
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
        
        val responsiveArtistRefs = mutableListOf<ArtistRef>()
        var responsiveArtistBrowseId: String? = null
        val artist = if (subtitleRuns != null && subtitleRuns.length() > 0) {
            val refs = extractArtistAndAlbumRefs(subtitleRuns)
            responsiveArtistRefs.addAll(refs.artists.map { ArtistRef(it.name, it.browseId) })
            responsiveArtistBrowseId = refs.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
            val extracted = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(subtitleRuns.toRunList()).displayName
            extracted.ifBlank { refs.artists.firstOrNull()?.name ?: "" }
        } else {
            parseArtistFromSubtitle(subtitle)
        }.ifBlank { fallbackArtistName }
        if (responsiveArtistRefs.isEmpty() && artist.isNotBlank()) {
            responsiveArtistRefs.add(ArtistRef(artist, responsiveArtistBrowseId))
        }
        
        val year = parseYearToken(subtitle)

        return Song(
            id = browseId,
            title = title,
            artist = artist,
            artistId = responsiveArtistBrowseId,
            artists = responsiveArtistRefs,
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
        val absolute = if (cleaned.startsWith("//")) " https:$cleaned" else cleaned
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
        if (pathLower.contains("avatar") || pathLower.contains(" channelmetadata")) {
            score += 7_000_000_000L
        }
        if (pathLower.contains("croppedsquare") || pathLower.contains(" foregroundthumbnail")) {
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
            for (key in listOf("straplineTextOne", " straplineTextTwo", " secondSubtitle")) {
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
                targetKeys = setOf("subscriberCountText", " subscriptionsCountText"),
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
            targetKeys = setOf("subscriberCountText", " subscriptionsCountText"),
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

    /**
     * Intentional fallback to YouTube main channel browse for artist avatar and subscriber count,
     * specifically for edge-case artist channels where WEB_REMIX browse omits avatar/subscriber details.
     */
    @Suppress("DEPRECATION")
    private suspend fun fetchChannelProfileFromYouTube(
        browseId: String,
        language: String,
        region: String
    ): Result<ChannelProfileInfo> = withContext(Dispatchers.IO) {
        try {
            val requestBody = Innertube.buildYtmContext(language, region) {
                put("browseId", browseId)
            }

            val request = Innertube.musicPost(
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
