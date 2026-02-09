package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Audio Stream Extractor
 * 
 * Multi-strategy extraction with robust fallback:
 * 1. NewPipe Extractor (primary - handles cipher/signatures)
 * 2. Android TV Client (best for direct streams)
 * 3. Android Music Client (music-optimized)
 * 4. iOS Client (fallback)
 * 5. Retry with exponential backoff
 */
@Singleton
class AudioStreamExtractor @Inject constructor(
    private val newPipeService: NewPipeService
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "AudioStreamExtractor"
        private const val MAX_RETRIES = 3
        private const val INITIAL_DELAY_MS = 500L
        
        // YouTube InnerTube API endpoints
        private const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/player"
        private const val API_KEY = "REDACTED_API_KEY"
    }

    /**
     * Extract audio stream URL for a video with multiple fallback strategies
     */
    suspend fun extractAudioStream(videoId: String, quality: StreamQuality): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Extracting audio for: $videoId, quality: $quality")

        // Strategy 1: Try NewPipe first (handles cipher/signatures)
        newPipeService.getStreamUrl(videoId, quality).let { result ->
            if (result.isSuccess) {
                val url = result.getOrNull()
                if (!url.isNullOrEmpty()) {
                    Log.d(TAG, "✅ NewPipe extraction successful")
                    return@withContext Result.success(url)
                }
            }
            Log.w(TAG, "⚠️ NewPipe failed: ${result.exceptionOrNull()?.message}")
        }

        // Strategy 2: Try Android TV Client (best for streams)
        extractWithClient(videoId, ClientType.ANDROID_TV, quality).let { result ->
            if (result.isSuccess) {
                Log.d(TAG, "✅ Android TV client successful")
                return@withContext result
            }
            Log.w(TAG, "⚠️ Android TV client failed")
        }

        // Strategy 3: Try Android Music Client
        extractWithClient(videoId, ClientType.ANDROID_MUSIC, quality).let { result ->
            if (result.isSuccess) {
                Log.d(TAG, "✅ Android Music client successful")
                return@withContext result
            }
            Log.w(TAG, "⚠️ Android Music client failed")
        }

        // Strategy 4: Try iOS Client
        extractWithClient(videoId, ClientType.IOS, quality).let { result ->
            if (result.isSuccess) {
                Log.d(TAG, "✅ iOS client successful")
                return@withContext result
            }
            Log.w(TAG, "⚠️ iOS client failed")
        }

        // Strategy 5: Try Android Client (standard)
        extractWithClient(videoId, ClientType.ANDROID, quality).let { result ->
            if (result.isSuccess) {
                Log.d(TAG, "✅ Android client successful")
                return@withContext result
            }
            Log.w(TAG, "⚠️ Android client failed")
        }

        // Strategy 6: Retry NewPipe with exponential backoff
        var lastError: Exception? = null
        var currentDelay = INITIAL_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            Log.d(TAG, "📡 Retry attempt ${attempt + 1}/$MAX_RETRIES")

            try {
                delay(currentDelay)
                currentDelay *= 2

                val result = newPipeService.getStreamUrl(videoId, quality)
                if (result.isSuccess) {
                    val url = result.getOrNull()
                    if (!url.isNullOrEmpty()) {
                        Log.d(TAG, "✅ Retry successful")
                        return@withContext Result.success(url)
                    }
                }
                lastError = result.exceptionOrNull() as? Exception ?: Exception("Empty result")
            } catch (e: Exception) {
                Log.w(TAG, "❌ Retry ${attempt + 1} failed", e)
                lastError = e
            }
        }

        Log.e(TAG, "❌ All extraction strategies failed for $videoId")
        Result.failure(lastError ?: Exception("Failed to extract audio stream"))
    }

    /**
     * Extract using specific InnerTube client
     */
    private suspend fun extractWithClient(
        videoId: String,
        clientType: ClientType,
        quality: StreamQuality
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildClientRequest(videoId, clientType)

            val request = Request.Builder()
                .url("$YOUTUBE_API?key=$API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .apply {
                    when (clientType) {
                        ClientType.ANDROID, ClientType.ANDROID_MUSIC, ClientType.ANDROID_TV -> {
                            header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip")
                        }
                        ClientType.IOS -> {
                            header("User-Agent", "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 17_4 like Mac OS X)")
                        }
                    }
                }
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val json = JSONObject(response.body?.string() ?: "")
            
            // Check for playability errors
            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "")
            if (status != "OK") {
                val reason = playability?.optString("reason", "Unknown error")
                Log.w(TAG, "⚠️ Playability: $status - $reason")
                return@withContext Result.failure(Exception("$status: $reason"))
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: return@withContext Result.failure(Exception("No streaming data"))

            // Parse adaptive audio formats
            val formats = mutableListOf<AudioFormat>()
            
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.optJSONObject(i) ?: continue
                    val mimeType = format.optString("mimeType", "")
                    if (mimeType.startsWith("audio/")) {
                        val url = format.optString("url", "")
                        if (url.isNotEmpty()) {
                            formats.add(
                                AudioFormat(
                                    url = url,
                                    mimeType = mimeType,
                                    bitrate = format.optInt("bitrate", 0),
                                    quality = when {
                                        format.optInt("bitrate", 0) >= 192000 -> StreamQuality.HIGH
                                        format.optInt("bitrate", 0) >= 128000 -> StreamQuality.MEDIUM
                                        else -> StreamQuality.LOW
                                    },
                                    itag = format.optInt("itag", 0)
                                )
                            )
                        }
                    }
                }
            }

            if (formats.isEmpty()) {
                return@withContext Result.failure(Exception("No audio formats found"))
            }

            // Sort by bitrate (highest first) and prefer OPUS for best quality (then M4A for compatibility)
            // OPUS provides better quality at same bitrate vs AAC
            val sortedFormats = formats.sortedWith(
                compareByDescending<AudioFormat> { it.bitrate }
                    .thenBy {
                        when {
                            it.mimeType.contains("opus") -> 0  // Best quality
                            it.mimeType.contains("mp4a") -> 1  // Good compatibility
                            else -> 2
                        }
                    }
            )

            // For BEST/HIGH quality, always pick highest bitrate (256kbps+)
            // OPUS at 256kbps is essentially lossless for most listeners
            val selectedFormat = when (quality) {
                StreamQuality.BEST, StreamQuality.HIGH -> {
                    // Prefer OPUS at highest bitrate for best quality
                    sortedFormats.firstOrNull { it.bitrate >= 256000 }
                        ?: sortedFormats.firstOrNull { it.bitrate >= 128000 }
                        ?: sortedFormats.firstOrNull()
                }
                StreamQuality.MEDIUM -> sortedFormats.getOrNull(sortedFormats.size / 2) ?: sortedFormats.firstOrNull()
                StreamQuality.LOW -> sortedFormats.lastOrNull()
            }

            selectedFormat?.let {
                Log.d(TAG, "📊 Selected: ${it.bitrate}bps, ${it.mimeType}")
                return@withContext Result.success(it.url)
            }

            Result.failure(Exception("No suitable format"))
        } catch (e: Exception) {
            Log.e(TAG, "Client extraction failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Build InnerTube client request body
     */
    private fun buildClientRequest(videoId: String, clientType: ClientType): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    when (clientType) {
                        ClientType.ANDROID -> {
                            put("clientName", "ANDROID")
                            put("clientVersion", "19.09.37")
                            put("androidSdkVersion", 34)
                            put("platform", "MOBILE")
                        }
                        ClientType.ANDROID_MUSIC -> {
                            put("clientName", "ANDROID_MUSIC")
                            put("clientVersion", "6.42.52")
                            put("androidSdkVersion", 34)
                            put("platform", "MOBILE")
                        }
                        ClientType.ANDROID_TV -> {
                            put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                            put("clientVersion", "2.0")
                            put("platform", "TV")
                        }
                        ClientType.IOS -> {
                            put("clientName", "IOS")
                            put("clientVersion", "19.09.3")
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone14,3")
                            put("platform", "MOBILE")
                        }
                    }
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", videoId)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", "19999")
                })
            })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
    }

    /**
     * Get available audio formats for a video
     */
    suspend fun getAudioFormats(videoId: String): Result<List<AudioFormat>> = withContext(Dispatchers.IO) {
        extractWithClient(videoId, ClientType.ANDROID, StreamQuality.BEST).map { listOf() }
    }

    /**
     * Client types for different extraction strategies
     */
    private enum class ClientType {
        ANDROID,
        ANDROID_MUSIC,
        ANDROID_TV,
        IOS
    }
}

/**
 * Represents an audio stream format
 */
data class AudioFormat(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val quality: StreamQuality,
    val itag: Int
) {
    fun isMp4(): Boolean = mimeType.contains("mp4a") || mimeType.contains("mp4")
    fun isOpus(): Boolean = mimeType.contains("opus") || mimeType.contains("webm")
    
    fun qualityLabel(): String {
        return when {
            bitrate >= 256000 -> "High (${bitrate / 1000}kbps)"
            bitrate >= 128000 -> "Medium (${bitrate / 1000}kbps)"
            else -> "Low (${bitrate / 1000}kbps)"
        }
    }
}