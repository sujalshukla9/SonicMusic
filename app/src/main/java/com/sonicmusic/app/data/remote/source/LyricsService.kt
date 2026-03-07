package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.LyricLine
import com.sonicmusic.app.domain.model.LyricsResult
import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches lyrics via LRCLIB (for synced) with fallback to YouTube Music Innertube API.
 * 
 * Includes Devanagari to Hinglish transliteration for Hindi lyrics.
 */
@Singleton
class LyricsService @Inject constructor() {

    companion object {
        private const val TAG = "LyricsService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch lyrics for a song. Attempts LRCLIB first for synced lyrics, then falls back to YT Music.
     */
    suspend fun fetchLyrics(song: Song): LyricsResult = withContext(Dispatchers.IO) {
        val videoId = song.id
        try {
            Log.d(TAG, "🎤 Fetching lyrics for: ${song.title} - $videoId")

            // Step 1: Try LRCLIB for synced lyrics
            val lrcResult = fetchLrcLibLyrics(song)
            if (lrcResult is LyricsResult.FoundSynced) {
                Log.d(TAG, "✅ Found synced lyrics from LRCLIB")
                return@withContext lrcResult
            }
            // LRCLIB may also return plain lyrics
            if (lrcResult is LyricsResult.Found) {
                Log.d(TAG, "✅ Found plain lyrics from LRCLIB")
                return@withContext lrcResult
            }

            // Step 2: Fall back to YouTube Music InnerTube plain lyrics
            Log.d(TAG, "⚠️ LRCLIB failed or no lyrics. Falling back to InnerTube for $videoId")
            val browseId = fetchLyricsBrowseId(videoId)
            if (browseId == null) {
                Log.d(TAG, "📝 No InnerTube lyrics tab found for: $videoId")
                return@withContext LyricsResult.NotFound
            }

            Log.d(TAG, "📝 Got InnerTube lyrics browseId: $browseId")
            return@withContext fetchLyricsText(browseId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching lyrics", e)
            LyricsResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Call LRCLIB API to get synchronized lyrics.
     */
    private fun fetchLrcLibLyrics(song: Song): LyricsResult {
        try {
            val durationSeconds = song.duration
            
            val urlBuilder = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("track_name", song.title)
                ?.addQueryParameter("artist_name", song.artist)
            
            if (durationSeconds > 0) {
                urlBuilder?.addQueryParameter("duration", durationSeconds.toString())
            }

            val url = urlBuilder?.build() ?: return LyricsResult.NotFound
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SonicMusic/1.0.0 (https://github.com/sujalshukla9/SonicMusic)")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return LyricsResult.NotFound
            }

            val responseBody = response.body?.string() ?: return LyricsResult.NotFound
            val json = JSONObject(responseBody)
            
            // Prefer synced lyrics
            val syncedLyrics = json.optString("syncedLyrics", "")
            if (syncedLyrics.isNotBlank()) {
                return parseLrc(syncedLyrics)
            }

            // Fallback: use LRCLIB plain lyrics (full song text)
            val plainLyrics = json.optString("plainLyrics", "")
            if (plainLyrics.isNotBlank()) {
                val hasDevanagari = plainLyrics.any { it in '\u0900'..'\u097F' }
                val displayText = if (hasDevanagari) {
                    TransliteratorHelper.transliterate(plainLyrics)
                } else {
                    plainLyrics
                }
                val originalText = if (hasDevanagari) plainLyrics else null
                return LyricsResult.Found(
                    text = displayText,
                    originalText = originalText,
                    source = "Source: LRCLIB"
                )
            }

            return LyricsResult.NotFound
        } catch (e: Exception) {
            Log.e(TAG, "❌ LRCLIB fetch failed", e)
            return LyricsResult.NotFound
        }
    }

    /**
     * Parses LRC format string into LyricLines
     */
    private fun parseLrc(lrcText: String): LyricsResult {
        val lines = mutableListOf<LyricLine>()
        // Match timestamps like [00:12.34] or [01:23.456] or [01:23]
        val timeRegex = Regex("\\[(\\d{2,}):(\\d{2})\\.?(\\d+)?\\]")
        
        lrcText.lines().forEach { line ->
            val matchResult = timeRegex.find(line)
            if (matchResult != null) {
                val minutes = matchResult.groupValues[1].toLong()
                val seconds = matchResult.groupValues[2].toLong()
                val fractionStr = matchResult.groupValues.getOrNull(3) ?: "0"
                // Pad or truncate fraction to milliseconds
                val millisStr = fractionStr.padEnd(3, '0').take(3)
                val milliseconds = millisStr.toLong()
                
                val timeMs = (minutes * 60 * 1000) + (seconds * 1000) + milliseconds
                
                val rawText = line.substring(matchResult.range.last + 1).trim()
                if (rawText.isNotBlank()) {
                    // Preserve original text and transliterate for display
                    val hasDevanagari = rawText.any { it in '\u0900'..'\u097F' }
                    val displayText = if (hasDevanagari) {
                        TransliteratorHelper.transliterate(rawText)
                    } else {
                        rawText
                    }
                    val originalText = if (hasDevanagari) rawText else null
                    lines.add(LyricLine(timeMs, displayText, originalText))
                }
            }
        }
        
        if (lines.isEmpty()) return LyricsResult.NotFound
        
        // Ensure sorted by time
        lines.sortBy { it.timeMs }
        return LyricsResult.FoundSynced(lines, "Source: LRCLIB")
    }

    /**
     * Step 1: Call /next to find the lyrics tab browseId.
     *
     * The response contains tabs like "Up Next", "Lyrics", "Related".
     * We look for the tab with title "Lyrics" and extract its
     * tabRenderer.endpoint.browseEndpoint.browseId.
     */
    private fun fetchLyricsBrowseId(videoId: String): String? {
        val body = Innertube.webRemixBody("en", "US") {
            put("videoId", videoId)
            put("isAudioOnly", true)
        }

        val request = Innertube.musicPost(
            endpoint = Innertube.NEXT,
            body = body,
            acceptLanguage = "en-US,en;q=0.9"
        )

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "❌ /next failed: ${response.code}")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        return parseLyricsBrowseId(responseBody)
    }

    /**
     * Parse the /next response to find the Lyrics tab browseId.
     */
    private fun parseLyricsBrowseId(jsonString: String): String? {
        try {
            val json = JSONObject(jsonString)

            // Path: contents.singleColumnMusicWatchNextResultsRenderer
            //   .tabbedRenderer.watchNextTabbedResultsRenderer.tabs[]
            val tabs = json
                .optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return null

            for (i in 0 until tabs.length()) {
                val tabRenderer = tabs.optJSONObject(i)
                    ?.optJSONObject("tabRenderer")
                    ?: continue

                val title = tabRenderer.optString("title", "")
                if (title.equals("Lyrics", ignoreCase = true)) {
                    val browseId = tabRenderer
                        .optJSONObject("endpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")

                    if (!browseId.isNullOrBlank()) {
                        return browseId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing lyrics browseId", e)
        }
        return null
    }

    /**
     * Step 2: Call /browse with the lyrics browseId to get plain lyrics text.
     */
    private fun fetchLyricsText(browseId: String): LyricsResult {
        val body = Innertube.webRemixBody("en", "US") {
            put("browseId", browseId)
        }

        val request = Innertube.musicPost(
            endpoint = Innertube.BROWSE,
            body = body,
            acceptLanguage = "en-US,en;q=0.9"
        )

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "❌ /browse failed: ${response.code}")
            return LyricsResult.Error("Failed to fetch lyrics: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: return LyricsResult.Error("Empty lyrics response")

        return parseLyricsText(responseBody)
    }

    /**
     * Parse the /browse response to extract lyrics text.
     */
    private fun parseLyricsText(jsonString: String): LyricsResult {
        try {
            val json = JSONObject(jsonString)

            val contents = json
                .optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (contents == null || contents.length() == 0) {
                return LyricsResult.NotFound
            }

            val firstContent = contents.optJSONObject(0)
            
            // Check for synced lyrics first
            val timedLyricsLines = firstContent
                ?.optJSONObject("elementRenderer")
                ?.optJSONObject("newElement")
                ?.optJSONObject("type")
                ?.optJSONObject("componentType")
                ?.optJSONObject("model")
                ?.optJSONObject("timedLyricsModel")
                ?.optJSONObject("lyricsData")
                ?.optJSONObject("timedLyricsData")
                ?.optJSONArray("timedLyricsLines")

            if (timedLyricsLines != null && timedLyricsLines.length() > 0) {
                val lines = mutableListOf<LyricLine>()
                for (i in 0 until timedLyricsLines.length()) {
                    val lineObj = timedLyricsLines.optJSONObject(i) ?: continue
                    val text = lineObj.optString("lyricLine", "")
                    val startTimeMs = lineObj.optJSONObject("cueRange")?.optLong("startTimeMilliseconds", -1L) ?: -1L
                    if (startTimeMs >= 0 && text.isNotBlank()) {
                         val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
                         val finalText = if (hasDevanagari) TransliteratorHelper.transliterate(text) else text
                         val originalText = if (hasDevanagari) text else null
                         lines.add(LyricLine(startTimeMs, finalText, originalText))
                    }
                }
                if (lines.isNotEmpty()) {
                    Log.d(TAG, "✅ Got synced lyrics via InnerTube API (${lines.size} lines)")
                    return LyricsResult.FoundSynced(lines, "Source: YouTube Music")
                }
            }

            // Fallback to plain lyrics parsing
            val shelf = firstContent
                ?.optJSONObject("musicDescriptionShelfRenderer")
                ?: return LyricsResult.NotFound

            // Extract lyrics text from description.runs
            val descriptionRuns = shelf
                .optJSONObject("description")
                ?.optJSONArray("runs")

            if (descriptionRuns == null || descriptionRuns.length() == 0) {
                return LyricsResult.NotFound
            }

            val lyricsText = buildString {
                for (i in 0 until descriptionRuns.length()) {
                    val text = descriptionRuns.optJSONObject(i)?.optString("text", "")
                    if (!text.isNullOrBlank()) {
                        append(text)
                    }
                }
            }.trim()

            if (lyricsText.isBlank()) {
                return LyricsResult.NotFound
            }

            // Preserve original and transliterate for display
            val hasDevanagari = lyricsText.any { it in '\u0900'..'\u097F' }
            val finalText = if (hasDevanagari) {
                TransliteratorHelper.transliterate(lyricsText)
            } else {
                lyricsText
            }
            val originalText = if (hasDevanagari) lyricsText else null

            // Extract source attribution (e.g. "Source: LyricFind")
            val source = shelf
                .optJSONObject("footer")
                ?.optJSONArray("runs")
                ?.let { runs ->
                    buildString {
                        for (i in 0 until runs.length()) {
                            append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                        }
                    }.trim().takeIf { it.isNotBlank() }
                }

            Log.d(TAG, "✅ Got plain lyrics (${finalText.length} chars), source: $source")
            return LyricsResult.Found(text = finalText, originalText = originalText, source = source)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing lyrics text", e)
            return LyricsResult.Error("Failed to parse lyrics: ${e.message}")
        }
    }
}

/**
 * Extracted into a separate object to prevent NoClassDefFoundError on API < 29
 * during class loading.
 */
private object TransliteratorHelper {
    private var instance: Any? = null
    
    fun transliterate(text: String): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                if (instance == null) {
                    instance = android.icu.text.Transliterator.getInstance("Any-Latin; nfd; [:nonspacing mark:] remove; nfc")
                }
                return (instance as android.icu.text.Transliterator).transliterate(text)
            } catch (e: Exception) {
                return text
            }
        }
        return text
    }
}
