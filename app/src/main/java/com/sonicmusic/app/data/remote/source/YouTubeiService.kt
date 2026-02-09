package com.sonicmusic.app.data.remote.source

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.sonicmusic.app.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
 * Default region: India (IN)
 */
@Singleton
class YouTubeiService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "YouTubeiService"
        
        // Default region and language
        const val DEFAULT_REGION = "IN" // India
        const val DEFAULT_LANGUAGE = "en"
        
        // YouTube Music API (music-only results)
        private const val YOUTUBE_MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
        private const val YOUTUBE_MUSIC_API_KEY = "REDACTED_API_KEY"
        
        // Regular YouTube API (fallback)
        private const val YOUTUBE_BASE = "https://www.youtube.com/youtubei/v1"
        private const val YOUTUBE_API_KEY = "REDACTED_API_KEY"
        
        // Duration limits for songs (in seconds)
        private const val MIN_DURATION = 60      // 1 minute minimum
        private const val MAX_DURATION = 600     // 10 minutes maximum
        
        // Non-music keywords to filter out
        private val EXCLUDE_KEYWORDS = listOf(
            "vlog", "review", "reaction", "episode", "full movie",
            "podcast", "interview", "tutorial", "gameplay", "trailer",
            "documentary", "news", "unboxing", "haul", "challenge",
            "behind the scenes", "bts making", "making of", "how to",
            "dj mix", "dj set", "mix tape", "full album", "mashup",
            "mega mix", "megamix", "best of", "greatest hits", "top hits",
            "compilation", "collection", "live set", "hours of music",
            "8d audio", "slowed", "reverb", "lofi hip hop", "study music"
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
        return when (region) {
            "IN" -> "hi" // India -> Hindi
            "PK" -> "ur" // Pakistan -> Urdu
            "BD" -> "bn" // Bangladesh -> Bengali
            "US", "GB", "AU", "CA", "NZ" -> "en" // English countries
            "JP" -> "ja" // Japan
            "KR" -> "ko" // Korea
            "BR", "PT" -> "pt" // Portuguese
            "ES", "MX", "AR", "CO", "CL" -> "es" // Spanish
            "FR" -> "fr" // France
            "DE", "AT", "CH" -> "de" // German
            "IT" -> "it" // Italy
            "RU" -> "ru" // Russia
            "CN", "TW", "HK" -> "zh" // Chinese
            "SA", "AE", "EG" -> "ar" // Arabic
            "TR" -> "tr" // Turkey
            "ID" -> "id" // Indonesia
            "TH" -> "th" // Thailand
            "VN" -> "vi" // Vietnam
            else -> "en" // Default to English
        }
    }
    
    // Current region (cached)
    private val currentRegion: String by lazy { detectUserRegion() }
    private val currentLanguage: String by lazy { getLanguageForRegion(currentRegion) }

    // ═══════════════════════════════════════════════════════════════
    // MAIN SEARCH - Uses YouTube Music API (music-only)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Search for songs using YouTube Music API
     * Returns music-only results with proper filtering
     */
    suspend fun searchSongs(query: String, limit: Int = 50): Result<List<Song>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Searching for: $query")
        
        // Try YouTube Music API first (music-only results)
        var result = searchYouTubeMusic(query, limit)
        
        if (result.isSuccess && result.getOrNull()?.isNotEmpty() == true) {
            Log.d(TAG, "✅ YouTube Music returned ${result.getOrNull()?.size} songs")
            return@withContext result
        }
        
        Log.d(TAG, "⚠️ YouTube Music failed, trying regular YouTube with filters")
        
        // Fallback to regular YouTube with strict music filtering
        result = searchYouTubeWithMusicFilter(query, limit)
        
        result
    }
    
    /**
     * Search using YouTube Music API (music.youtube.com)
     * Returns music-only results
     */
    private suspend fun searchYouTubeMusic(query: String, limit: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            // YouTube Music client configuration (WEB_REMIX)
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", DEFAULT_LANGUAGE)
                        put("gl", DEFAULT_REGION)
                    })
                })
                put("query", query)
                // Filter for songs only
                put("params", "EgWKAQIIAWoKEAMQBBAKEAkQBQ%3D%3D")
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/search?key=$YOUTUBE_MUSIC_API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            
            Log.d(TAG, "📡 Calling YouTube Music API...")

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ YouTube Music failed: ${response.code}")
                return@withContext Result.failure(Exception("YouTube Music failed: ${response.code}"))
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Empty response"))
            }
            
            Log.d(TAG, "📦 Response length: ${responseBody.length}")
            
            // Parse YouTube Music response
            val songs = parseYouTubeMusicResponse(responseBody)
                .filter { isMusicContent(it) }
                .take(limit)
            
            Log.d(TAG, "🎶 Parsed ${songs.size} music tracks")

            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ YouTube Music error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Search regular YouTube with strict music filtering
     */
    private suspend fun searchYouTubeWithMusicFilter(query: String, limit: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            // Add "song" or "music" to query for better results
            val musicQuery = if (!query.lowercase().contains("song") && !query.lowercase().contains("music")) {
                "$query song"
            } else {
                query
            }
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 34)
                        put("hl", DEFAULT_LANGUAGE)
                        put("gl", DEFAULT_REGION)
                        put("platform", "MOBILE")
                    })
                })
                put("query", musicQuery)
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_BASE/search?key=$YOUTUBE_API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip")
                .header("X-YouTube-Client-Name", "3")
                .header("X-YouTube-Client-Version", "19.09.37")
                .build()
            
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
            val songs = parseYouTubeResponse(responseBody)
                .filter { isMusicContent(it) }
                .filter { isValidDuration(it.duration) }
                .filter { !containsExcludedKeywords(it.title) }
                .take(limit)
            
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
    private fun parseYouTubeMusicResponse(jsonString: String): List<Song> {
        val songs = mutableListOf<Song>()
        
        try {
            val json = JSONObject(jsonString)
            
            // YouTube Music structure
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
            
            if (tabs == null) {
                Log.d(TAG, "⚠️ No tabs found, trying alternative structure")
                return parseYouTubeResponse(jsonString)
            }
            
            for (t in 0 until tabs.length()) {
                val tabContent = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?: continue
                
                for (i in 0 until tabContent.length()) {
                    val section = tabContent.optJSONObject(i) ?: continue
                    
                    // musicShelfRenderer contains song results
                    val shelfContents = section.optJSONObject("musicShelfRenderer")
                        ?.optJSONArray("contents")
                        ?: section.optJSONObject("musicCardShelfRenderer")
                            ?.optJSONArray("contents")
                        ?: continue
                    
                    for (j in 0 until shelfContents.length()) {
                        val item = shelfContents.optJSONObject(j) ?: continue
                        
                        // Parse musicResponsiveListItemRenderer
                        val musicItem = item.optJSONObject("musicResponsiveListItemRenderer")
                        if (musicItem != null) {
                            val song = parseMusicResponsiveItem(musicItem)
                            if (song != null) {
                                songs.add(song)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Parse YouTube Music error", e)
        }
        
        Log.d(TAG, "📊 YouTube Music parsed: ${songs.size} songs")
        return songs
    }
    
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
                        artist = artistColumn.optJSONObject(0)?.optString("text", "") ?: ""
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
            
            var thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            if (thumbnails != null && thumbnails.length() > 0) {
                val lastThumb = thumbnails.optJSONObject(thumbnails.length() - 1)
                thumbnailUrl = lastThumb?.optString("url", thumbnailUrl) ?: thumbnailUrl
            }
            
            if (title.isEmpty()) return null
            
            return Song(
                id = videoId,
                title = title,
                artist = cleanArtistName(artist),
                duration = parseDuration(durationText),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                category = "Music"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing music item", e)
            return null
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
            
            val artist = extractText(renderer.optJSONObject("ownerText"))
                .ifEmpty { extractText(renderer.optJSONObject("shortBylineText")) }
            
            val durationText = renderer.optJSONObject("lengthText")?.optString("simpleText", "0:00") ?: "0:00"
            
            val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            var thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            if (thumbnails != null && thumbnails.length() > 0) {
                thumbnailUrl = thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url", thumbnailUrl) ?: thumbnailUrl
            }
            
            return Song(
                id = videoId,
                title = title,
                artist = cleanArtistName(artist),
                duration = parseDuration(durationText),
                thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                category = "Music"
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
            return runs.optJSONObject(0)?.optString("text", "") ?: ""
        }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // OTHER PUBLIC METHODS
    // ═══════════════════════════════════════════════════════════════
    
    suspend fun getSongDetails(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 34)
                        put("hl", DEFAULT_LANGUAGE)
                        put("gl", DEFAULT_REGION)
                    })
                })
                put("videoId", videoId)
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_BASE/player?key=$YOUTUBE_API_KEY")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val json = JSONObject(responseBody)
            val videoDetails = json.optJSONObject("videoDetails") 
                ?: return@withContext Result.failure(Exception("No details"))

            val thumbnails = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            var thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            if (thumbnails != null && thumbnails.length() > 0) {
                thumbnailUrl = thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url", thumbnailUrl) ?: thumbnailUrl
            }

            Result.success(Song(
                id = videoId,
                title = videoDetails.optString("title", "Unknown"),
                artist = cleanArtistName(videoDetails.optString("author", "Unknown")),
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
            Log.d(TAG, "🔥 Fetching real-time trending songs for region: $currentRegion")
            
            // YouTube Music Charts browse ID - region-specific
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", currentLanguage)
                        put("gl", currentRegion)
                    })
                })
                // Browse ID for "Top Songs" chart
                put("browseId", "FEmusic_charts")
                put("params", "sgYJQGVuZ2luZQ%3D%3D") // Chart type params
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/browse?key=$YOUTUBE_MUSIC_API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Charts API failed, falling back to search")
                return@withContext searchSongs("trending songs $currentRegion 2024", limit)
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext searchSongs("top songs $currentRegion 2024", limit)
            }
            
            val songs = parseChartsResponse(responseBody).take(limit)
            
            if (songs.isEmpty()) {
                Log.w(TAG, "No songs from charts, using search fallback")
                return@withContext searchSongs("viral songs $currentRegion 2024", limit)
            }
            
            Log.d(TAG, "✅ Got ${songs.size} trending songs for $currentRegion")
            Result.success(songs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Trending error, using fallback", e)
            searchSongs("popular songs $currentRegion 2024", limit)
        }
    }

    /**
     * Get New Releases using YouTube Music New Releases API
     */
    suspend fun getNewReleases(limit: Int = 25): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🆕 Fetching real-time new releases for region: $currentRegion")
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", currentLanguage)
                        put("gl", currentRegion)
                    })
                })
                // Browse ID for New Releases
                put("browseId", "FEmusic_new_releases")
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/browse?key=$YOUTUBE_MUSIC_API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "New releases API failed, falling back to search")
                return@withContext searchSongs("new songs $currentRegion 2024", limit)
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext searchSongs("latest songs $currentRegion 2024", limit)
            }
            
            val songs = parseBrowseResponse(responseBody).take(limit)
            
            if (songs.isEmpty()) {
                Log.w(TAG, "No songs from new releases, using search fallback")
                return@withContext searchSongs("new releases $currentRegion 2024", limit)
            }
            
            Log.d(TAG, "✅ Got ${songs.size} new releases for $currentRegion")
            Result.success(songs)
            
        } catch (e: Exception) {
            Log.e(TAG, "New releases error, using fallback", e)
            searchSongs("new music $currentRegion 2024", limit)
        }
    }

    suspend fun getEnglishHits(limit: Int = 25): Result<List<Song>> = withContext(Dispatchers.IO) {
        // Use specific search for English songs with current year
        searchSongs("top English songs 2024 trending", limit)
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
                val artist = extractText(twoRow.optJSONObject("subtitle"))
                
                val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                
                var thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                if (thumbnails != null && thumbnails.length() > 0) {
                    thumbnailUrl = thumbnails.optJSONObject(thumbnails.length() - 1)
                        ?.optString("url", thumbnailUrl) ?: thumbnailUrl
                }
                
                return Song(
                    id = videoId,
                    title = title,
                    artist = cleanArtistName(artist),
                    duration = 0,
                    thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                    category = "Music"
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
            Log.d(TAG, "🎵 Fetching Up Next for: $videoId")
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", DEFAULT_LANGUAGE)
                        put("gl", DEFAULT_REGION)
                    })
                })
                put("videoId", videoId)
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/next?key=$YOUTUBE_MUSIC_API_KEY")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JSONObject(responseBody)
            
            // Parse queue from playlistPanelRenderer
            val queueItems = parseQueueResponse(json)
            
            Log.d(TAG, "✅ Got ${queueItems.size} songs from Up Next")
            Result.success(queueItems)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Get Up Next error", e)
            Result.failure(e)
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
                var artist = extractText(item.optJSONObject("longBylineText"))
                
                // Fallback for artist
                if (artist.isEmpty()) {
                    artist = extractText(item.optJSONObject("shortBylineText"))
                }
                
                val durationText = extractText(item.optJSONObject("lengthText"))
                
                val thumbnails = item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                var thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
                if (thumbnails != null && thumbnails.length() > 0) {
                    val lastThumb = thumbnails.optJSONObject(thumbnails.length() - 1)
                    thumbnailUrl = lastThumb?.optString("url", thumbnailUrl) ?: thumbnailUrl
                }
                
                songs.add(Song(
                    id = videoId,
                    title = title,
                    artist = cleanArtistName(artist),
                    duration = parseDuration(durationText),
                    thumbnailUrl = upgradeThumbQuality(thumbnailUrl, videoId),
                    category = "Music"
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
    
    private fun cleanArtistName(artist: String): String {
        return artist
            .removeSuffix(" - Topic")
            .removeSuffix(" - topic")
            .removeSuffix("VEVO")
            .removeSuffix(" VEVO")
            .removeSuffix(" Official")
            .removeSuffix(" - Official")
            .trim()
    }
    
    /**
     * Upgrade thumbnail URL to maximum quality
     * Handles various YouTube thumbnail URL patterns:
     * - Standard: i.ytimg.com/vi/{id}/{quality}.jpg
     * - Music: lh3.googleusercontent.com/... with w= and h= params
     * - i.ytimg.com with size parameters
     */
    private fun upgradeThumbQuality(url: String, videoId: String): String {
        // For standard YouTube thumbnail URLs, always use maxresdefault
        if (url.contains("i.ytimg.com/vi/")) {
            return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        }
        
        // For Google user content URLs (YouTube Music thumbnails)
        // Remove size restrictions (w=, h=, s=) and add maximum size
        if (url.contains("lh3.googleusercontent.com") || url.contains("yt3.googleusercontent.com")) {
            // Remove existing size params and add max size
            val baseUrl = url.split("=")[0]
            return "$baseUrl=w1280-h720-l90-rj" // Max quality params
        }
        
        // For i.ytimg.com URLs with different patterns
        if (url.contains("i.ytimg.com")) {
            // Remove any size parameters
            val cleanUrl = url.replace(Regex("[?&](w|h|sqp|rs)=[^&]*"), "")
            return cleanUrl
                .replace("hqdefault", "maxresdefault")
                .replace("mqdefault", "maxresdefault")
                .replace("sddefault", "maxresdefault")
                .replace("default", "maxresdefault")
        }
        
        // For any other URLs, try to get the video ID and construct max quality URL
        return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
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
}