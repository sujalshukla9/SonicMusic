package com.sonicmusic.app.data.remote.source

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * Default region: India (IN)
 */
@Singleton
class YouTubeiService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
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
        
        // Default region if detection is unavailable
        const val DEFAULT_REGION = "IN" // India
        
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

    private fun normalizeCountryCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.US)
        if (normalized.isNullOrEmpty() || normalized.length != 2) return null
        if (!normalized.all { it.isLetter() }) return null
        return if (normalized == "UK") "GB" else normalized
    }

    private val detectedRegion: String by lazy { detectUserRegion() }

    private suspend fun getRequestRegion(): String {
        val stored = normalizeCountryCode(settingsDataStore.countryCode.first())
        if (stored != null) return stored
        return normalizeCountryCode(detectedRegion) ?: DEFAULT_REGION
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

            // YouTube Music client configuration (WEB_REMIX)
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
                    })
                })
                put("query", query)
                // No params = mixed results
            }

            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/search?key=$YOUTUBE_MUSIC_API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("YouTube Music failed: ${response.code}"))
            }

            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            
            // Parse mixed results but keep song-only output for app consistency.
            val items = sanitizeSongResults(
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
     * Search using YouTube Music API (music.youtube.com)
     * Returns music-only results
     */
    private suspend fun searchYouTubeMusic(query: String, limit: Int, offset: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            // YouTube Music client configuration (WEB_REMIX)
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
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
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
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
            val songs = sanitizeSongResults(
                songs = parseYouTubeMusicResponse(responseBody),
                limit = limit,
                offset = offset
            )
            
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
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 34)
                        put("hl", language)
                        put("gl", region)
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
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
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
        val cleaned = songs.asSequence()
            .filter { song ->
                when (song.contentType) {
                    ContentType.SONG -> true
                    ContentType.UNKNOWN -> song.duration == 0 || isValidDuration(song.duration)
                    else -> false
                }
            }
            .map { song ->
                song.copy(
                    contentType = ContentType.SONG,
                    thumbnailUrl = upgradeThumbQuality(song.thumbnailUrl, song.id)
                )
            }
            .filter { isMusicContent(it) }
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

            return Song(
                id = id,
                title = titleText,
                artist = subtitleText, // For top result, subtitle is often "Artist" or "Album • Artist"
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
                for (k in 0 until subtitleRuns.length()) {
                    fullSubtitle.append(subtitleRuns.optJSONObject(k)?.optString("text", ""))
                }
                subtitle = fullSubtitle.toString()
                
                // Extract type
                typeText = subtitleRuns.optJSONObject(0)?.optString("text", "") ?: ""
                
                // Extract Artist (usually 2nd or 3rd run if separate)
                // Simplified: use the whole subtitle as artist/desc for now
                artist = subtitle
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

            // Determine Type
            // Music items usually have a navigation endpoint type check or localized text string check
            // For MVP, simple string check on subtitle or explicit type
            
            var contentType = ContentType.SONG
            if (subtitle.contains("Video", true)) contentType = ContentType.VIDEO
            else if (subtitle.contains("Artist", true) || id.startsWith("UC")) contentType = ContentType.ARTIST
            else if (subtitle.contains("Album", true) || id.startsWith("MPRE")) contentType = ContentType.ALBUM
            else if (subtitle.contains("Playlist", true) || id.startsWith("VL")) contentType = ContentType.PLAYLIST
            
            // Special fix cases
            if (id.startsWith("UC")) contentType = ContentType.ARTIST
            
            return Song(
                id = id,
                title = title,
                artist = artist,
                duration = 0, // Mixed items might not have simple duration
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

            val artist = extractText(renderer.optJSONObject("ownerText"))
                .ifEmpty { extractText(renderer.optJSONObject("shortBylineText")) }

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
            return runs.optJSONObject(0)?.optString("text", "") ?: ""
        }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // OTHER PUBLIC METHODS
    // ═══════════════════════════════════════════════════════════════
    
    suspend fun getSongDetails(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        try {
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)

            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 34)
                        put("hl", language)
                        put("gl", region)
                    })
                })
                put("videoId", videoId)
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_BASE/player?key=$YOUTUBE_API_KEY")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip")
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
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
            val thumbnailUrl = extractHighestQualityThumbnail(
                thumbnails = thumbnails,
                fallback = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            )

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
            val region = getRequestRegion()
            val language = getLanguageForRegion(region)
            val year = currentYear()
            Log.d(TAG, "🔥 Fetching real-time trending songs for region: $region")
            
            // YouTube Music Charts browse ID - region-specific
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
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
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
                .build()
            
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
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
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
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
                .build()
            
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

            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
                    })
                })
                put("input", normalizedQuery)
            }

            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/music/get_search_suggestions?key=$YOUTUBE_MUSIC_API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
                .build()

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
                val artist = extractText(twoRow.optJSONObject("subtitle"))
                
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
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
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
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
                .build()
            
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
            
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", language)
                        put("gl", region)
                    })
                })
                put("playlistId", playlistId)
                put("isAudioOnly", true)
            }
            
            val request = Request.Builder()
                .url("$YOUTUBE_MUSIC_BASE/next?key=$YOUTUBE_MUSIC_API_KEY")
                .post(requestBody.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("Accept-Language", getAcceptLanguageHeader(language, region))
                .build()
            
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Get Radio Mix error", e)
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
}
