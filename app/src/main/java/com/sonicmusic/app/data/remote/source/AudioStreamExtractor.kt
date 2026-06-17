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
 * 2. Innertube WEB clients (no auth required — mobile clients now mandate OAuth)
 */
@Singleton
class AudioStreamExtractor @Inject constructor(
    private val newPipeService: NewPipeService,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val TAG = "SonicStream"
        
        // YouTube InnerTube API endpoints
        private const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/player"
        private const val API_KEY = BuildConfig.YOUTUBE_API_KEY

        // Current User-Agent strings (June 2026)
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        private const val ANDROID_USER_AGENT =
            "com.google.android.youtube/21.23.483 (Linux; U; Android 14; en_US) gzip"
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.22.04 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X; en_US)"
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

        // WEB clients first — they do NOT require OAuth login.
        // Mobile clients (ANDROID, ANDROID_MUSIC, IOS) now mandate sign-in
        // and return LOGIN_REQUIRED without OAuth tokens.
        val clients = listOf(
            ClientType.WEB,
            ClientType.WEB_REMIX,
            ClientType.MWEB,
            ClientType.ANDROID_MUSIC,
            ClientType.IOS,
            ClientType.ANDROID,
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
                        ClientType.ANDROID, ClientType.ANDROID_MUSIC -> {
                            header("User-Agent", ANDROID_USER_AGENT)
                        }
                        ClientType.IOS -> {
                            header("User-Agent", IOS_USER_AGENT)
                        }
                        ClientType.WEB, ClientType.WEB_REMIX, ClientType.MWEB -> {
                            header("User-Agent", WEB_USER_AGENT)
                            // YouTube validates Origin/Referer for WEB clients
                            header("Origin", "https://www.youtube.com")
                            header("Referer", "https://www.youtube.com/")
                            header("X-Youtube-Client-Name", when (clientType) {
                                ClientType.WEB_REMIX -> "67"
                                ClientType.MWEB -> "2"
                                else -> "1"
                            })
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
                    "📊 Selected: ${info.fullDescription} (itag ${fmt.itag}, client=${clientType.name})",
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

        return validFormats.maxWithOrNull(
            compareBy<AudioFormat> { it.bitrateKbps }
                .thenBy { it.sampleRate }
                .thenBy { it.channelCount }
                .thenBy { fmt ->
                    val codecBonus = when {
                        fmt.isAac() -> 20L
                        fmt.isOpus() -> 10L
                        else -> 0L
                    }
                    (fmt.bitrateKbps.toLong() * 1_000L) + fmt.sampleRate + codecBonus
                }
        )
    }

    private fun bestSourceScore(format: AudioFormat): Long {
        val codecBonus = when {
            format.isAac() -> 20L
            format.isOpus() -> 10L
            else -> 0L
        }
        return (format.bitrateKbps.toLong() * 1_000L) + format.sampleRate + codecBonus
    }

    /**
     * Build InnerTube client request body.
     *
     * WEB/MWEB clients are preferred because they do not require OAuth.
     * Mobile clients (ANDROID/IOS) now return LOGIN_REQUIRED without auth tokens.
     */
    private fun buildClientRequest(videoId: String, clientType: ClientType): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    when (clientType) {
                        ClientType.ANDROID -> {
                            put("clientName", "ANDROID")
                            put("clientVersion", "21.23.483")
                            put("androidSdkVersion", 34)
                            put("platform", "MOBILE")
                            put("osName", "Android")
                            put("osVersion", "14")
                        }
                        ClientType.ANDROID_MUSIC -> {
                            put("clientName", "ANDROID_MUSIC")
                            put("clientVersion", "7.27.52")
                            put("androidSdkVersion", 34)
                            put("platform", "MOBILE")
                            put("osName", "Android")
                            put("osVersion", "14")
                        }
                        ClientType.IOS -> {
                            put("clientName", "IOS")
                            put("clientVersion", "21.22.04")
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone16,2")
                            put("platform", "MOBILE")
                            put("osName", "iOS")
                            put("osVersion", "18.5.0.22F76")
                        }
                        ClientType.WEB -> {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20260601.01.00")
                            put("platform", "DESKTOP")
                        }
                        ClientType.WEB_REMIX -> {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", "1.20260601.01.00")
                            put("platform", "DESKTOP")
                        }
                        ClientType.MWEB -> {
                            put("clientName", "MWEB")
                            put("clientVersion", "2.20260601.01.00")
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
                    // Dynamic signature timestamp: days since Unix epoch.
                    // YouTube validates this; a hardcoded fake value causes HTTP 400.
                    val sts = (System.currentTimeMillis() / 86_400_000L).toString()
                    put("signatureTimestamp", sts)
                })
            })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
    }

    /**
     * Client types for different extraction strategies.
     * WEB/MWEB do not require OAuth. Mobile clients require login.
     */
    private enum class ClientType {
        ANDROID,
        ANDROID_MUSIC,
        IOS,
        WEB,
        WEB_REMIX,
        MWEB,
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
