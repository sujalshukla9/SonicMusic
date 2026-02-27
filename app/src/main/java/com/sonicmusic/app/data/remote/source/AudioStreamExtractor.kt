package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.sonicmusic.app.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube audio stream extractor with resilient fallback strategy.
 *
 * Strategy:
 * 1. NewPipe (primary, handles signature/cipher flows more reliably)
 * 2. Innertube clients (fallback)
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
        private const val TAG = "SonicStream"
        
        // YouTube InnerTube API endpoints
        private const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/player"
        private const val API_KEY = BuildConfig.YOUTUBE_API_KEY
    }

    /**
     * Extract audio stream URL + metadata for a video with multiple fallback strategies.
     * Returns URL and AudioStreamInfo for quality badge display.
     */
    suspend fun extractAudioStream(
        videoId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Extracting audio stream for: $videoId (requested=${quality.displayName})")

        // NewPipe first: in runtime logs this path is more resilient against player-side 403 churn.
        val newPipeResult = newPipeService.getStreamUrl(videoId, quality)
        if (newPipeResult.isSuccess) {
            Log.d(TAG, "✅ NewPipe extraction succeeded for $videoId")
            return@withContext newPipeResult
        }
        val newPipeError = newPipeResult.exceptionOrNull()
        Log.w(TAG, "⚠️ NewPipe failed for $videoId: ${newPipeError?.message}")

        val clients = listOf(
            ClientType.ANDROID_MUSIC,
            ClientType.ANDROID_TV,
            ClientType.ANDROID,
            ClientType.IOS,
        )
        var lastError: Throwable? = null

        for (clientType in clients) {
            val result = extractWithClient(videoId, clientType, quality)
            if (result.isSuccess) {
                return@withContext result
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "⚠️ ${clientType.name} Innertube client failed: ${lastError?.message}")
        }

        Log.e(
            TAG,
            "❌ All extraction strategies failed for $videoId (NewPipe + Innertube). " +
                "NewPipe=${newPipeError?.message}, lastInnertube=${lastError?.message}",
        )
        Result.failure(lastError ?: newPipeError ?: Exception("Failed to extract audio stream"))
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
        requestedQuality: StreamQuality,
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

            val selectedFormat = selectBestFormat(formats, requestedQuality)

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
                
                Log.d(
                    TAG,
                    "📊 Selected: ${info.fullDescription} (itag ${fmt.itag}, requested=${requestedQuality.name})",
                )
                return@withContext Result.success(Pair(fmt.url, info))
            }

            Result.failure(Exception("No suitable format"))
        } catch (e: Exception) {
            Log.e(TAG, "Client extraction failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun selectBestFormat(
        formats: List<AudioFormat>,
        requestedQuality: StreamQuality,
    ): AudioFormat? {
        if (formats.isEmpty()) return null
        val validFormats = formats.filter { it.bitrateKbps > 0 }.ifEmpty { formats }

        return when (requestedQuality) {
            StreamQuality.BEST -> {
                val opusFormats = validFormats.filter { it.isOpus() }
                opusFormats.maxWithOrNull(
                    compareBy<AudioFormat> { it.bitrateKbps }
                        .thenBy { it.sampleRate }
                        .thenBy { it.channelCount },
                ) ?: validFormats.maxByOrNull(::bestSourceScore)
            }

            StreamQuality.HIGH -> {
                validFormats
                    .filter { it.isAac() && it.bitrateKbps >= 120 }
                    .maxByOrNull(::bestSourceScore)
                    ?: validFormats
                        .filter { it.bitrateKbps >= 120 }
                        .maxByOrNull(::bestSourceScore)
                    ?: validFormats.maxByOrNull(::bestSourceScore)
            }

            StreamQuality.MEDIUM -> {
                validFormats
                    .filter { it.bitrateKbps in 100..160 }
                    .maxByOrNull(::bestSourceScore)
                    ?: validFormats.minByOrNull { kotlin.math.abs(it.bitrateKbps - 128) }
            }

            StreamQuality.LOW -> {
                validFormats.minByOrNull { it.bitrateKbps }
            }
        }
    }

    private fun bestSourceScore(format: AudioFormat): Long {
        val codecBonus = when {
            format.isOpus() -> 20L
            format.isAac() -> 10L
            else -> 0L
        }
        return (format.bitrateKbps.toLong() * 1_000L) + format.sampleRate + codecBonus
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
        isOpus() && bitrateKbps >= 160 -> "Very High (${bitrateKbps}kbps OPUS)"
        bitrateKbps >= 256 -> "High (${bitrateKbps}kbps)"
        bitrateKbps >= 128 -> "Medium (${bitrateKbps}kbps)"
        else -> "Low (${bitrateKbps}kbps)"
    }
}
