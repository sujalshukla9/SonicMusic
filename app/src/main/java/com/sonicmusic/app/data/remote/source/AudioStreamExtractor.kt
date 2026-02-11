package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.AudioStreamInfo
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
 * YouTube Audio Stream Extractor — Apple Music-Style Quality Pipeline
 * 
 * Multi-strategy extraction with codec-aware quality selection:
 * 1. NewPipe Extractor (primary - handles cipher/signatures)
 * 2. Android TV Client (best for direct streams)
 * 3. Android Music Client (music-optimized)
 * 4. iOS Client (fallback)
 * 5. Retry with exponential backoff
 * 
 * Returns stream URL + AudioStreamInfo for quality badge display.
 */
@Singleton
class AudioStreamExtractor @Inject constructor(
    private val newPipeService: NewPipeService,
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
     * Extract audio stream URL + metadata for a video with multiple fallback strategies.
     * Returns URL and AudioStreamInfo for quality badge display.
     */
    suspend fun extractAudioStream(
        videoId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Extracting audio for: $videoId, quality: ${quality.displayName}")

        // Strategy 1: Try NewPipe first (handles cipher/signatures)
        newPipeService.getStreamUrl(videoId, quality).let { result ->
            if (result.isSuccess) {
                val pair = result.getOrNull()
                if (pair != null && pair.first.isNotEmpty()) {
                    Log.d(TAG, "✅ NewPipe extraction successful")
                    return@withContext Result.success(pair)
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
                    val pair = result.getOrNull()
                    if (pair != null && pair.first.isNotEmpty()) {
                        Log.d(TAG, "✅ Retry successful")
                        return@withContext Result.success(pair)
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
     * Legacy compatibility: Extract stream URL only (wraps the new method)
     */
    suspend fun extractAudioStreamUrl(
        videoId: String,
        quality: StreamQuality,
    ): Result<String> {
        return extractAudioStream(videoId, quality).map { it.first }
    }

    /**
     * Extract using specific InnerTube client
     */
    private suspend fun extractWithClient(
        videoId: String,
        clientType: ClientType,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> = withContext(Dispatchers.IO) {
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

            // Parse adaptive audio formats with rich metadata
            val formats = mutableListOf<AudioFormat>()
            
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.optJSONObject(i) ?: continue
                    val mimeType = format.optString("mimeType", "")
                    if (mimeType.startsWith("audio/")) {
                        val url = format.optString("url", "")
                        if (url.isNotEmpty()) {
                            val bitrate = format.optInt("bitrate", 0) / 1000 // bps to kbps
                            val codec = AudioStreamInfo.codecFromMimeType(mimeType)
                            
                            formats.add(
                                AudioFormat(
                                    url = url,
                                    mimeType = mimeType,
                                    bitrate = format.optInt("bitrate", 0),
                                    bitrateKbps = bitrate,
                                    quality = AudioStreamInfo.qualityTierFromStream(codec, bitrate),
                                    itag = format.optInt("itag", 0),
                                    sampleRate = format.optInt("audioSampleRate", 
                                        AudioStreamInfo.sampleRateFromCodec(codec)),
                                    channelCount = format.optInt("audioChannels", 2),
                                    codec = codec,
                                    container = AudioStreamInfo.containerFromMimeType(mimeType),
                                )
                            )
                        }
                    }
                }
            }

            if (formats.isEmpty()) {
                return@withContext Result.failure(Exception("No audio formats found"))
            }

            // Apple Music-style codec-aware format selection
            val selectedFormat = selectBestFormat(formats, quality)

            selectedFormat?.let { fmt ->
                val info = AudioStreamInfo(
                    codec = fmt.codec,
                    bitrate = fmt.bitrateKbps,
                    sampleRate = fmt.sampleRate,
                    bitDepth = 16,
                    qualityTier = fmt.quality,
                    containerFormat = fmt.container,
                    channelCount = fmt.channelCount,
                )
                
                Log.d(TAG, "📊 Selected: ${info.fullDescription} (itag ${fmt.itag})")
                return@withContext Result.success(Pair(fmt.url, info))
            }

            Result.failure(Exception("No suitable format"))
        } catch (e: Exception) {
            Log.e(TAG, "Client extraction failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Apple Music-style format selection based on quality tier
     */
    private fun selectBestFormat(formats: List<AudioFormat>, quality: StreamQuality): AudioFormat? {
        if (formats.isEmpty()) return null
        
        // Filter out very low quality streams if possible
        val validFormats = formats.filter { it.bitrateKbps > 0 }
            .ifEmpty { formats }

        return when (quality) {
            StreamQuality.LOSSLESS, StreamQuality.BEST -> {
                // Priority:
                // 1. Opus 160kbps+ (itag 251 - usually 160k, sometimes higher)
                // 2. AAC 256kbps (itag 141)
                // 3. AAC 128kbps (itag 140)
                // 4. Highest bitrate remaining
                
                validFormats.filter { it.itag == 251 }.maxByOrNull { it.bitrateKbps }
                    ?: validFormats.filter { it.itag == 272 }.maxByOrNull { it.bitrateKbps } // High bitrate release
                    ?: validFormats.firstOrNull { it.itag == 141 }
                    ?: validFormats.firstOrNull { it.itag == 140 }
                    ?: validFormats.filter { it.isOpus() }.maxByOrNull { it.bitrateKbps }
                    ?: validFormats.maxByOrNull { it.bitrateKbps }
            }
            
            StreamQuality.HIGH -> {
                // Priority: AAC 256 (itag 141) -> AAC 128 (itag 140) -> Opus 160 (itag 251) -> Highest
                validFormats.firstOrNull { it.itag == 141 }
                    ?: validFormats.firstOrNull { it.itag == 140 }
                    ?: validFormats.firstOrNull { it.itag == 251 }
                    ?: validFormats.maxByOrNull { it.bitrateKbps }
            }
            
            StreamQuality.MEDIUM -> {
                // ~128kbps
                validFormats.firstOrNull { it.itag == 140 } // AAC 128
                    ?: validFormats.firstOrNull { it.itag == 251 } // Opus 160 (efficient)
                    ?: validFormats.sortedBy { kotlin.math.abs(it.bitrateKbps - 128) }.firstOrNull()
            }
            
            StreamQuality.LOW -> {
                // Lowest bitrate (itag 139, 249, 250)
                validFormats.firstOrNull { it.itag == 249 } // Opus 50k
                    ?: validFormats.firstOrNull { it.itag == 250 } // Opus 70k
                    ?: validFormats.firstOrNull { it.itag == 139 } // AAC 48k
                    ?: validFormats.minByOrNull { it.bitrateKbps }
            }
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
     * Client types for different extraction strategies
     */
    private enum class ClientType {
        ANDROID,
        ANDROID_MUSIC,
        ANDROID_TV,
        IOS,
    }
}

/**
 * Represents an audio stream format with rich metadata
 */
data class AudioFormat(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val bitrateKbps: Int,
    val quality: StreamQuality,
    val itag: Int,
    val sampleRate: Int = 44100,
    val channelCount: Int = 2,
    val codec: String = "Unknown",
    val container: String = "Unknown",
) {
    fun isOpus(): Boolean = codec.equals("OPUS", true)
    fun isAac(): Boolean = codec.contains("AAC", true)
    
    fun qualityLabel(): String = when {
        isOpus() && bitrateKbps >= 160 -> "Lossless (${bitrateKbps}kbps OPUS)"
        bitrateKbps >= 256 -> "High (${bitrateKbps}kbps)"
        bitrateKbps >= 128 -> "Medium (${bitrateKbps}kbps)"
        else -> "Low (${bitrateKbps}kbps)"
    }
}