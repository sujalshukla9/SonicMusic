package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Audio Stream Extractor
 * 
 * Uses multiple strategies for reliable audio extraction:
 * 1. Piped API (most reliable, pre-extracted)
 * 2. InnerTube ANDROID client with proper headers
 * 3. InnerTube IOS client as fallback
 */
@Singleton
class NewPipeService @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val TAG = "NewPipeService"
        
        // Piped instances (free, open-source YouTube proxies)
        private val PIPED_INSTANCES = listOf(
            "pipedapi.kavin.rocks",
            "pipedapi.adminforge.de",
            "api.piped.yt",
            "pipedapi.in.projectsegfau.lt"
        )
        
        private const val YOUTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
        private const val API_KEY = "REDACTED_API_KEY"
    }

    /**
     * Extract audio stream URL from a video ID
     */
    suspend fun getStreamUrl(videoId: String, quality: StreamQuality): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Extracting audio for: $videoId")
        
        // Strategy 1: Try Piped API first (most reliable)
        for (instance in PIPED_INSTANCES) {
            try {
                Log.d(TAG, "📡 Trying Piped: $instance")
                val result = extractWithPiped(videoId, instance, quality)
                if (result.isSuccess) {
                    Log.d(TAG, "✅ Piped ($instance) successful!")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Piped ($instance) failed: ${e.message}")
            }
        }
        
        // Strategy 2: Try InnerTube ANDROID
        try {
            Log.d(TAG, "📡 Trying InnerTube ANDROID...")
            val result = extractWithInnerTube(videoId, quality, "ANDROID")
            if (result.isSuccess) {
                Log.d(TAG, "✅ InnerTube ANDROID successful!")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ InnerTube ANDROID failed: ${e.message}")
        }
        
        // Strategy 3: Try InnerTube IOS
        try {
            Log.d(TAG, "📡 Trying InnerTube IOS...")
            val result = extractWithInnerTube(videoId, quality, "IOS")
            if (result.isSuccess) {
                Log.d(TAG, "✅ InnerTube IOS successful!")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ InnerTube IOS failed: ${e.message}")
        }
        
        Log.e(TAG, "❌ All extraction methods failed for $videoId")
        Result.failure(Exception("Failed to extract audio stream for $videoId"))
    }
    
    /**
     * Extract using Piped API - Most reliable method
     * Piped pre-extracts and decrypts streams for us
     */
    private fun extractWithPiped(videoId: String, instance: String, quality: StreamQuality): Result<String> {
        val request = Request.Builder()
            .url("https://$instance/streams/$videoId")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get()
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            return Result.failure(Exception("HTTP ${response.code}"))
        }

        val responseBody = response.body?.string()
        if (responseBody.isNullOrEmpty()) {
            return Result.failure(Exception("Empty response"))
        }

        val json = JSONObject(responseBody)
        
        // Check for error
        if (json.has("error")) {
            return Result.failure(Exception(json.optString("error", "Unknown error")))
        }

        // Get audio streams
        val audioStreams = json.optJSONArray("audioStreams")
        if (audioStreams == null || audioStreams.length() == 0) {
            return Result.failure(Exception("No audio streams"))
        }

        // Find best audio stream
        var bestUrl: String? = null
        var bestBitrate = 0
        var targetBitrate = when (quality) {
            StreamQuality.HIGH, StreamQuality.BEST -> Int.MAX_VALUE
            StreamQuality.MEDIUM -> 128
            StreamQuality.LOW -> 64
        }

        for (i in 0 until audioStreams.length()) {
            val stream = audioStreams.optJSONObject(i) ?: continue
            val url = stream.optString("url", "")
            val bitrate = stream.optInt("bitrate", 0) / 1000 // Convert to kbps
            val mimeType = stream.optString("mimeType", "")
            
            if (url.isEmpty()) continue
            
            // Prefer m4a/mp4 audio for better compatibility
            val isPreferred = mimeType.contains("mp4") || mimeType.contains("m4a")
            
            if (quality == StreamQuality.HIGH || quality == StreamQuality.BEST) {
                // Get highest bitrate
                if (bitrate > bestBitrate || (bitrate == bestBitrate && isPreferred)) {
                    bestUrl = url
                    bestBitrate = bitrate
                }
            } else {
                // Get closest to target bitrate
                val currentDiff = kotlin.math.abs(bitrate - targetBitrate)
                val bestDiff = kotlin.math.abs(bestBitrate - targetBitrate)
                if (bestUrl == null || currentDiff < bestDiff) {
                    bestUrl = url
                    bestBitrate = bitrate
                }
            }
        }

        return if (bestUrl != null) {
            Log.d(TAG, "🎧 Piped: Selected ${bestBitrate}kbps stream")
            Result.success(bestUrl)
        } else {
            Result.failure(Exception("No suitable audio stream"))
        }
    }
    
    /**
     * Extract using InnerTube API directly
     */
    private fun extractWithInnerTube(videoId: String, quality: StreamQuality, clientType: String): Result<String> {
        val (requestBody, userAgent, clientName, clientVersion) = when (clientType) {
            "ANDROID" -> Quadruple(
                createAndroidRequestBody(videoId),
                "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip",
                "3",
                "19.09.37"
            )
            "IOS" -> Quadruple(
                createIOSRequestBody(videoId),
                "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 17_4 like Mac OS X)",
                "5",
                "19.09.3"
            )
            else -> return Result.failure(Exception("Unknown client type"))
        }

        val request = Request.Builder()
            .url("$YOUTUBE_PLAYER_URL?key=$API_KEY&prettyPrint=false")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Origin", "https://www.youtube.com")
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            return Result.failure(Exception("HTTP ${response.code}"))
        }

        val responseBody = response.body?.string()
        if (responseBody.isNullOrEmpty()) {
            return Result.failure(Exception("Empty response"))
        }

        return parseInnerTubeResponse(JSONObject(responseBody), quality)
    }
    
    private fun createAndroidRequestBody(videoId: String): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 34)
                    put("hl", "en")
                    put("gl", "IN")
                    put("osName", "Android")
                    put("osVersion", "14")
                    put("platform", "MOBILE")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("signatureTimestamp", 19950) // Update this periodically
                })
            })
        }
    }
    
    private fun createIOSRequestBody(videoId: String): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "IOS")
                    put("clientVersion", "19.09.3")
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone14,3")
                    put("hl", "en")
                    put("gl", "IN")
                    put("osName", "iOS")
                    put("osVersion", "17.4.0.21E219")
                    put("platform", "MOBILE")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
    }
    
    private fun parseInnerTubeResponse(json: JSONObject, quality: StreamQuality): Result<String> {
        // Check playability
        val playabilityStatus = json.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status", "")
        
        if (status != "OK") {
            val reason = playabilityStatus?.optString("reason")
                ?: playabilityStatus?.optString("messages")
                ?: "Playback not allowed"
            return Result.failure(Exception(reason))
        }
        
        val streamingData = json.optJSONObject("streamingData")
            ?: return Result.failure(Exception("No streaming data"))

        // Try adaptive formats first
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
            val url = findBestAudioStream(adaptiveFormats, quality)
            if (url != null) return Result.success(url)
        }

        // Try regular formats
        val formats = streamingData.optJSONArray("formats")
        if (formats != null && formats.length() > 0) {
            val url = findBestAudioStream(formats, quality)
            if (url != null) return Result.success(url)
        }

        // Try HLS as last resort
        val hlsUrl = streamingData.optString("hlsManifestUrl", "")
        if (hlsUrl.isNotEmpty()) {
            return Result.success(hlsUrl)
        }

        return Result.failure(Exception("No playable audio stream"))
    }
    
    private fun findBestAudioStream(formats: JSONArray, quality: StreamQuality): String? {
        data class AudioInfo(val url: String, val bitrate: Int, val mimeType: String)
        val audioStreams = mutableListOf<AudioInfo>()

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType", "")
            
            // Only audio formats
            if (!mimeType.startsWith("audio/")) continue
            
            var url = format.optString("url", "")
            
            // Skip if no URL and has signatureCipher (encrypted)
            if (url.isEmpty()) {
                val cipher = format.optString("signatureCipher", "")
                if (cipher.isNotEmpty()) {
                    // Try to parse URL from cipher (may not work without decryption)
                    val params = cipher.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else "")
                    }
                    url = params["url"] ?: ""
                    // If there's a signature, the URL won't work without it
                    if (params.containsKey("s")) {
                        continue // Skip encrypted streams
                    }
                }
            }
            
            if (url.isEmpty()) continue
            
            val bitrate = format.optInt("bitrate", 0)
            audioStreams.add(AudioInfo(url, bitrate, mimeType))
        }

        if (audioStreams.isEmpty()) return null
        
        // Sort by bitrate descending
        val sorted = audioStreams.sortedByDescending { it.bitrate }
        
        val selected = when (quality) {
            StreamQuality.HIGH, StreamQuality.BEST -> sorted.first()
            StreamQuality.MEDIUM -> sorted.getOrElse(sorted.size / 2) { sorted.first() }
            StreamQuality.LOW -> sorted.last()
        }
        
        Log.d(TAG, "🎧 InnerTube: Selected ${selected.bitrate / 1000}kbps ${selected.mimeType}")
        return selected.url
    }

    /**
     * Get song info from video ID
     */
    suspend fun getSongInfo(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        // Try Piped first
        for (instance in PIPED_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("https://$instance/streams/$videoId")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue
                
                val json = JSONObject(response.body?.string() ?: "")
                if (json.has("error")) continue
                
                return@withContext Result.success(Song(
                    id = videoId,
                    title = json.optString("title", "Unknown"),
                    artist = cleanArtistName(json.optString("uploader", "Unknown")),
                    duration = json.optInt("duration", 0),
                    thumbnailUrl = json.optString("thumbnailUrl", "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"),
                    viewCount = json.optLong("views", 0)
                ))
            } catch (e: Exception) {
                continue
            }
        }
        
        // Fallback to basic info
        Result.success(Song(
            id = videoId,
            title = "Unknown",
            artist = "Unknown",
            duration = 0,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        ))
    }
    
    private fun cleanArtistName(artist: String): String {
        return artist
            .removeSuffix(" - Topic")
            .removeSuffix(" - topic")
            .removeSuffix("VEVO")
            .removeSuffix(" VEVO")
            .trim()
    }
    
    // Helper class for multiple returns
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
