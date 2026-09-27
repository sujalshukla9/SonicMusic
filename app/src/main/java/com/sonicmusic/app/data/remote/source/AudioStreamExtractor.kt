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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

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

    private val dolbyAtmosCache = ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val TAG = "SonicStream"

        // YouTube InnerTube API endpoints
        private const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/player"
        private const val API_KEY = BuildConfig.YOUTUBE_API_KEY

        // Current User-Agent strings (June 2026)
        private val WEB_USER_AGENT = YtmScopeConfig.ClientContext.USER_AGENT
        private const val ANDROID_USER_AGENT =
            "com.google.android.youtube/21.23.483 (Linux; U; Android 14; en_US) gzip"
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.22.04 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X; en_US)"
    }

    /**
     * Inspect the InnerTube format which matches the stream that will be played.
     *
     * A video-level check is deliberately insufficient: a normal AAC/Opus stream may
     * coexist with an Atmos rendition. Without the selected stream's itag we fail closed.
     * The cache is keyed by the video and selected itag, so changing stream quality causes
     * a fresh, correct decision without adding work to subsequent plays of the same stream.
     */
    suspend fun detectDolbyAtmos(videoId: String, streamUrl: String): Boolean = withContext(Dispatchers.IO) {
        val selectedItag = selectedItagFromUrl(streamUrl) ?: return@withContext false
        val cacheKey = "$videoId:$selectedItag"
        dolbyAtmosCache[cacheKey]?.let { return@withContext it }

        try {
            val requestBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "21.23.483")
                        put("androidSdkVersion", 34)
                        put("platform", "MOBILE")
                        put("osName", "Android")
                        put("osVersion", "14")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("$YOUTUBE_API?key=$API_KEY&prettyPrint=false")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", ANDROID_USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Dolby Atmos InnerTube check HTTP ${response.code} for $videoId")
                return@withContext false
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val streamingData = json.optJSONObject("streamingData") ?: return@withContext false
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return@withContext false
            val selectedFormat = (0 until adaptiveFormats.length())
                .asSequence()
                .mapNotNull(adaptiveFormats::optJSONObject)
                .firstOrNull { it.optInt("itag", -1) == selectedItag }
            val hasAtmos = selectedFormat?.let(::hasConfirmedDolbyAtmos) ?: false
            Log.d(TAG, "Dolby Atmos detection for $videoId itag=$selectedItag: $hasAtmos")
            dolbyAtmosCache[cacheKey] = hasAtmos
            hasAtmos
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect Dolby Atmos for $videoId", e)
            false
        }
    }

    /**
     * Check a list of InnerTube adaptive formats for explicitly labelled Atmos renditions.
     * This is useful for diagnostics only; playback must call [hasConfirmedDolbyAtmos] on
     * the selected format so an alternative Atmos rendition cannot label a stereo stream.
     */
    fun checkDolbyAtmosFromFormats(adaptiveFormats: JSONArray): Boolean {
        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            if (hasConfirmedDolbyAtmos(format)) return true
        }
        return false
    }

    /**
     * Only explicit Atmos/JOC metadata is sufficient. E-AC-3, AC-4, multichannel audio,
     * and InnerTube's generic spatial flag can all describe non-Atmos audio and must not
     * activate Dolby processing.
     */
    private fun hasConfirmedDolbyAtmos(format: JSONObject): Boolean {
        val mimeType = format.optString("mimeType", "").lowercase()
        if (!mimeType.startsWith("audio/")) return false

        val explicitMetadata = sequenceOf(
            mimeType,
            format.optString("audioQuality", ""),
            format.optJSONObject("audioTrack")?.toString().orEmpty(),
            format.optString("audioTrackType", ""),
        ).joinToString(" ").lowercase()

        val confirmed = explicitMetadata.contains("atmos") ||
            mimeType.contains("eac3-joc") || mimeType.contains("ec-3-joc") ||
            (mimeType.contains("joc") && (mimeType.contains("eac3") || mimeType.contains("ec-3")))

        if (confirmed) {
            Log.d(TAG, "Dolby Atmos confirmed for itag=${format.optInt("itag", 0)} via $mimeType")
        }
        return confirmed
    }

    private fun selectedItagFromUrl(streamUrl: String): Int? {
        return Regex("(?:[?&]|%3F|%26)itag(?:=|%3D)(\\d+)", RegexOption.IGNORE_CASE)
            .find(streamUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
    /**
     * Extract audio stream URL + metadata for a video with multiple fallback strategies.
     * Returns URL and AudioStreamInfo for quality badge display.
     */
    suspend fun extractAudioStream(
        videoId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Extracting audio stream for: $videoId (requested=${quality.displayName})")

        // 1. Primary: NewPipe (handles signature/cipher decryption and produces playable streams)
        val newPipeResult = newPipeService.getStreamUrl(videoId, quality)
        if (newPipeResult.isSuccess) {
            val (url, info) = newPipeResult.getOrThrow()
            val hasAtmos = detectDolbyAtmos(videoId, url)
            val enrichedInfo = info.copy(hasDolbyAtmos = hasAtmos)
            Log.d(TAG, "NewPipe extraction succeeded for $videoId (hasDolbyAtmos=$hasAtmos)")
            return@withContext Result.success(Pair(url, enrichedInfo))
        }
        val newPipeError = newPipeResult.exceptionOrNull()
        Log.w(TAG, "NewPipe failed for $videoId: ${newPipeError?.message}")

        // 2. High-speed Innertube client fallback
        val clients = listOf(
            ClientType.WEB_REMIX,
            ClientType.WEB,
            ClientType.MWEB,
            ClientType.ANDROID_MUSIC,
            ClientType.ANDROID,
        )
        var lastInnertubeError: Throwable? = null

        for (clientType in clients) {
            val result = extractWithClient(videoId, clientType, quality)
            if (result.isSuccess) {
                Log.d(TAG, "Innertube client ${clientType.name} extraction succeeded for $videoId")
                return@withContext result
            }
            lastInnertubeError = result.exceptionOrNull()
            Log.w(TAG, "${clientType.name} Innertube client failed: ${lastInnertubeError?.message}")
        }

        Log.e(
            TAG,
            "All extraction strategies failed for $videoId (NewPipe + Innertube). " +
                "NewPipe=${newPipeError?.message}, lastInnertube=${lastInnertubeError?.message}",
        )
        Result.failure(newPipeError ?: lastInnertubeError ?: Exception("Failed to extract audio stream"))
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
                val reason = playability?.optString("reason", " Unknown error")
                Log.w(TAG, "Playability: $status - $reason")
                return@withContext Result.failure(Exception("$status: $reason"))
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: return@withContext Result.failure(Exception("No streaming data"))

            // Parse adaptive audio formats with rich metadata
            val formats = mutableListOf<AudioFormat>()

            // ── Dolby Atmos per-song detection ────────────────────────
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")

            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.optJSONObject(i) ?: continue
                    val mimeType = format.optString("mimeType", "")

                    if (mimeType.startsWith("audio/")) {

                        // Parse playable format
                        var url = format.optString("url", "")
                        if (url.isEmpty()) {
                            val cipher = format.optString("signatureCipher", "").ifEmpty { format.optString("cipher", "") }
                            if (cipher.isNotEmpty()) {
                                url = cipher.split("&").find { it.startsWith("url=") }?.substringAfter("url=")?.let {
                                    java.net.URLDecoder.decode(it, "UTF-8")
                                }.orEmpty()
                            }
                        }
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
                                    hasDolbyAtmos = hasConfirmedDolbyAtmos(format),
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
                    hasDolbyAtmos = fmt.hasDolbyAtmos,
                )

                Log.d(
                    TAG,
                    "Selected: ${info.fullDescription} (itag ${fmt.itag}, client=${clientType.name}, dolbyAtmos=${fmt.hasDolbyAtmos})",
                )
                return@withContext Result.success(Pair(fmt.url, info))
            }

            Result.failure(Exception("No suitable format"))
        } catch (e: Exception) {
            Log.e(TAG, "Client extraction failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Select the best audio format with strict AAC 256kbps priority.
     *
     * Scoring model:
     *  - AAC streams at ≥256kbps  → highest tier (score multiplier: 3 × bitrate + large bonus)
     *  - AAC streams at any bitrate → preferred over OPUS (multiplier: 3 × bitrate)
     *  - OPUS/WebM streams         → fallback (multiplier: 1 × bitrate)
     *
     * This ensures we always pick the best AAC quality available, and only fall
     * back to OPUS if no AAC stream exists at all.
     */
    private fun selectBestFormat(
        formats: List<AudioFormat>,
        @Suppress("UNUSED_PARAMETER") requestedQuality: StreamQuality,
    ): AudioFormat? {
        if (formats.isEmpty()) return null
        val validFormats = formats.filter { it.bitrateKbps > 0 }.ifEmpty { formats }

        return validFormats.maxWithOrNull(
            compareBy { fmt -> bestSourceScore(fmt) }
        )
    }

    /**
     * Score an audio format for selection.
     *
     * AAC is scored 3× higher than OPUS so that even a 128kbps AAC stream
     * outranks a 128kbps OPUS stream, and a 256kbps AAC stream always wins.
     * An extra 500_000 bonus is added for AAC streams at ≥256kbps to guarantee
     * they beat any lower-bitrate AAC or any OPUS stream.
     */
    private fun bestSourceScore(format: AudioFormat): Long {
        return when {
            format.isAac() && format.bitrateKbps >= 256 ->
                (format.bitrateKbps.toLong() * 3_000L) + 500_000L + format.sampleRate
            format.isAac() ->
                (format.bitrateKbps.toLong() * 3_000L) + format.sampleRate
            else ->
                // OPUS / WebM / other — used only as a last resort
                (format.bitrateKbps.toLong() * 1_000L) + format.sampleRate
        }
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
                            put("clientName", YtmScopeConfig.ClientContext.CLIENT_NAME)
                            put("clientVersion", YtmScopeConfig.ClientContext.CLIENT_VERSION)
                            put("platform", YtmScopeConfig.ClientContext.PLATFORM)
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
    val hasDolbyAtmos: Boolean = false,
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
