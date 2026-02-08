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
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Audio Stream Extractor
 * 
 * Strategy:
 * 1. Try NewPipeService first (pure InnerTube with multiple client fallback)
 * 2. Retry with exponential backoff for resilience
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
    }

    /**
     * Extract audio stream URL for a video with retry logic
     */
    suspend fun extractAudioStream(videoId: String, quality: StreamQuality): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Extracting audio for: $videoId, quality: $quality")
        
        var lastError: Exception? = null
        var currentDelay = INITIAL_DELAY_MS
        
        // Retry with exponential backoff
        repeat(MAX_RETRIES) { attempt ->
            Log.d(TAG, "📡 Attempt ${attempt + 1}/$MAX_RETRIES")
            
            try {
                val result = newPipeService.getStreamUrl(videoId, quality)
                
                if (result.isSuccess) {
                    val url = result.getOrNull()
                    if (!url.isNullOrEmpty()) {
                        Log.d(TAG, "✅ Successfully extracted audio stream")
                        return@withContext Result.success(url)
                    }
                }
                
                lastError = result.exceptionOrNull() as? Exception 
                    ?: Exception("Empty result")
                    
            } catch (e: Exception) {
                Log.w(TAG, "❌ Attempt ${attempt + 1} failed", e)
                lastError = e
            }
            
            // Wait before retry (except on last attempt)
            if (attempt < MAX_RETRIES - 1) {
                Log.d(TAG, "⏳ Waiting ${currentDelay}ms before retry...")
                delay(currentDelay)
                currentDelay *= 2 // Exponential backoff
            }
        }
        
        Log.e(TAG, "❌ All extraction attempts failed for $videoId")
        Result.failure(lastError ?: Exception("Failed to extract audio stream"))
    }

    /**
     * Get available audio formats for a video
     */
    suspend fun getAudioFormats(videoId: String): Result<List<AudioFormat>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=REDACTED_API_KEY")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Request failed: ${response.code}"))
            }

            val json = JSONObject(response.body?.string() ?: "")
            val streamingData = json.optJSONObject("streamingData")
                ?: return@withContext Result.failure(Exception("No streaming data"))

            val formats = mutableListOf<AudioFormat>()
            
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.optJSONObject(i) ?: continue
                    val mimeType = format.optString("mimeType", "")
                    if (mimeType.startsWith("audio/")) {
                        formats.add(parseAudioFormat(format))
                    }
                }
            }

            Result.success(formats.sortedByDescending { it.bitrate })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get audio formats", e)
            Result.failure(e)
        }
    }

    private fun parseAudioFormat(format: JSONObject): AudioFormat {
        val mimeType = format.optString("mimeType", "")
        val bitrate = format.optInt("bitrate", 0)
        val audioQuality = format.optString("audioQuality", "")
        val url = format.optString("url", "")
        
        val quality = when {
            audioQuality.contains("AUDIO_QUALITY_LOW") -> StreamQuality.LOW
            audioQuality.contains("AUDIO_QUALITY_MEDIUM") -> StreamQuality.MEDIUM
            bitrate >= 192000 -> StreamQuality.HIGH
            else -> StreamQuality.MEDIUM
        }

        return AudioFormat(
            url = url,
            mimeType = mimeType,
            bitrate = bitrate,
            quality = quality,
            itag = format.optInt("itag", 0)
        )
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