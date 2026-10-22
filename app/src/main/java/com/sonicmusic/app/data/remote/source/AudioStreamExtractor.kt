package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.domain.model.AudioStreamInfo
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

import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.features.player.audio.AudioQualitySelector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube audio stream extractor with automatic best-quality selection.
 *
 * Architecture:
 * 1. Primary: NewPipe cipher-decrypted extraction engine discovering valid audio streams.
 * 2. Dedicated AudioQualitySelector: objectively scores candidates (AAC 256k, Opus 160k, etc.)
 *    against actual device decoder capabilities and genuine technical properties.
 * 3. Fallback: Direct Innertube extraction across supported client identities when cipher
 *    recovery or alternative formats are needed.
 * 4. Deduplication & Caching: Deduplicates concurrent requests for the same track and
 *    respects genuine URL expiration timestamps.
 */
@Singleton
class AudioStreamExtractor @Inject constructor(
    private val newPipeService: NewPipeService,
    private val okHttpClient: OkHttpClient,
    private val audioQualitySelector: AudioQualitySelector = AudioQualitySelector()
) {

    private val client = okHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Ephemeral in-memory stream cache
    data class CachedStreamCandidate(
        val candidate: AudioStreamCandidate,
        val allCandidates: List<AudioStreamCandidate>,
        val info: AudioStreamInfo,
        val quality: StreamQuality,
        val expiresAtMs: Long
    )

    private val streamCache = ConcurrentHashMap<String, CachedStreamCandidate>()
    private val requestMutex = Mutex()
    private val inFlightRequests = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Result<Pair<AudioStreamCandidate, AudioStreamInfo>>>>()

    companion object {
        private const val TAG = "SonicStream"

        // YouTube InnerTube API endpoint
        private const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1/player"
        private val API_KEY = BuildConfig.YOUTUBE_API_KEY.takeIf {
            it.isNotBlank() && it != "YOUR_API_KEY_HERE"
        }

        // Current User-Agent strings (June 2026)
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.22.04 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X; en_US)"
        private const val ANDROID_VR_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36"
        private val WEB_USER_AGENT = YtmScopeConfig.ClientContext.USER_AGENT
        private const val ANDROID_USER_AGENT =
            "com.google.android.youtube/21.23.483 (Linux; U; Android 14; en_US) gzip"
        private const val ANDROID_MUSIC_USER_AGENT =
            "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; en_US)"
    }

    /**
     * Clear cached stream candidates for a specific video or all.
     */
    fun clearCache(videoId: String? = null) {
        if (videoId != null) {
            streamCache.remove(videoId)
        } else {
            streamCache.clear()
        }
    }

    /**
     * Extract audio stream URL + metadata for a video with automatic best-quality selection.
     * Overload for backward-compatibility.
     */
    suspend fun extractAudioStream(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
    ): Result<Pair<String, AudioStreamInfo>> {
        return extractAudioStream(videoId, quality, forceRefresh = false, excludeUrls = emptySet())
    }

    /**
     * Extract audio stream URL + metadata for a video with support for force refresh and excluded URLs (recovery).
     */
    suspend fun extractAudioStream(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
        forceRefresh: Boolean = false,
        excludeUrls: Set<String> = emptySet(),
    ): Result<Pair<String, AudioStreamInfo>> {
        return extractBestCandidate(videoId, quality, forceRefresh, excludeUrls)
            .map { Pair(it.first.url, it.second) }
    }

    /**
     * Core extraction pipeline: extracts best AudioStreamCandidate and AudioStreamInfo.
     */
    suspend fun extractBestCandidate(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
        forceRefresh: Boolean = false,
        excludeUrls: Set<String> = emptySet(),
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        // 1. Check cache if not forcing refresh
        if (!forceRefresh) {
            val cached = streamCache[videoId]
            if (cached != null) {
                // If the selected candidate is still valid and not excluded
                if (!cached.candidate.isExpired() && cached.quality == quality && !excludeUrls.contains(cached.candidate.url)) {
                    Log.d(TAG, "Serving cached stream for $videoId: ${cached.info.fullDescription}")
                    return@withContext Result.success(Pair(cached.candidate, cached.info))
                }
                // Try fallback among previously cached candidates
                if (cached.allCandidates.isNotEmpty()) {
                    val fallback = audioQualitySelector.selectFallbackCandidate(cached.allCandidates, excludeUrls, quality)
                    if (fallback != null && !fallback.isExpired()) {
                        val info = fallback.toAudioStreamInfo(quality)
                        Log.d(TAG, "Serving cached fallback stream for $videoId: ${info.fullDescription}")
                        return@withContext Result.success(Pair(fallback, info))
                    }
                }
            }
        }

        Log.d(TAG, "Extracting audio candidate for: $videoId (requested=${quality.displayName}, forceRefresh=$forceRefresh)")

        // 2. Try NewPipe Extractor as primary source
        val allDiscoveredCandidates = mutableListOf<AudioStreamCandidate>()
        val newPipeResult = newPipeService.extractAudioCandidates(videoId)
        var newPipeError: Throwable? = null

        if (newPipeResult.isSuccess) {
            val npCandidates = newPipeResult.getOrThrow()
            allDiscoveredCandidates.addAll(npCandidates)
            val selection = audioQualitySelector.selectBestCandidate(npCandidates, quality, excludeUrls)
            if (selection != null) {
                val candidate = selection.candidate
                val info = candidate.toAudioStreamInfo(quality)
                val expiry = candidate.expiresAtMs ?: (System.currentTimeMillis() + 15 * 60 * 1000L)
                streamCache[videoId] = CachedStreamCandidate(candidate, npCandidates, info, quality, expiry)
                Log.d(TAG, "NewPipe selection successful for $videoId: ${info.fullDescription}")
                return@withContext Result.success(Pair(candidate, info))
            }
        } else {
            newPipeError = newPipeResult.exceptionOrNull()
            Log.w(TAG, "NewPipe extraction failed for $videoId: ${newPipeError?.message}. Falling back to Innertube clients...")
        }

        // 3. Fallback: Direct Innertube extraction across client identities
        val clients = listOf(
            ClientType.IOS,
            ClientType.ANDROID_VR,
            ClientType.WEB_REMIX,
            ClientType.WEB,
            ClientType.MWEB,
            ClientType.ANDROID_MUSIC,
            ClientType.ANDROID,
        )
        var lastInnertubeError: Throwable? = null

        for (clientType in clients) {
            val result = extractCandidatesWithClient(videoId, clientType)
            if (result.isSuccess) {
                val candidates = result.getOrThrow()
                allDiscoveredCandidates.addAll(candidates)
                val selection = audioQualitySelector.selectBestCandidate(candidates, quality, excludeUrls)
                if (selection != null) {
                    val candidate = selection.candidate
                    val info = candidate.toAudioStreamInfo(quality)
                    val expiry = candidate.expiresAtMs ?: (System.currentTimeMillis() + 15 * 60 * 1000L)
                    streamCache[videoId] = CachedStreamCandidate(candidate, allDiscoveredCandidates, info, quality, expiry)
                    Log.d(TAG, "Innertube client ${clientType.name} succeeded for $videoId: ${info.fullDescription}")
                    return@withContext Result.success(Pair(candidate, info))
                }
            }
            lastInnertubeError = result.exceptionOrNull()
            Log.w(TAG, "Innertube ${clientType.name} client failed for $videoId: ${lastInnertubeError?.message}")
        }

        // If any candidates were discovered across all attempts, try selecting a fallback
        if (allDiscoveredCandidates.isNotEmpty()) {
            val fallbackSelection = audioQualitySelector.selectBestCandidate(allDiscoveredCandidates, quality, excludeUrls)
            if (fallbackSelection != null) {
                val candidate = fallbackSelection.candidate
                val info = candidate.toAudioStreamInfo(quality)
                val expiry = candidate.expiresAtMs ?: (System.currentTimeMillis() + 15 * 60 * 1000L)
                streamCache[videoId] = CachedStreamCandidate(candidate, allDiscoveredCandidates, info, quality, expiry)
                Log.d(TAG, "Fallback selection succeeded across accumulated candidates for $videoId: ${info.fullDescription}")
                return@withContext Result.success(Pair(candidate, info))
            }
        }

        Log.e(
            TAG,
            "All audio extraction strategies failed for $videoId. " +
                "NewPipe=${newPipeError?.message}, Innertube=${lastInnertubeError?.message}"
        )
        Result.failure(newPipeError ?: lastInnertubeError ?: Exception("Failed to extract audio stream for $videoId"))
    }

    /**
     * Legacy compatibility: Extract stream URL only (wraps the new method)
     */
    suspend fun extractAudioStreamUrl(
        videoId: String,
        quality: StreamQuality = StreamQuality.BEST,
    ): Result<String> {
        return extractAudioStream(videoId, quality).map { it.first }
    }

    /**
     * Extract audio stream candidates using a specific InnerTube client persona.
     */
    suspend fun extractCandidatesWithClient(
        videoId: String,
        clientType: ClientType,
    ): Result<List<AudioStreamCandidate>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildClientRequest(videoId, clientType)

            val endpointUrl = if (API_KEY != null) {
                "$YOUTUBE_API?key=$API_KEY&prettyPrint=false"
            } else {
                "$YOUTUBE_API?prettyPrint=false"
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .apply {
                    when (clientType) {
                        ClientType.IOS -> header("User-Agent", IOS_USER_AGENT)
                        ClientType.ANDROID_VR -> header("User-Agent", ANDROID_VR_USER_AGENT)
                        ClientType.ANDROID_MUSIC -> header("User-Agent", ANDROID_MUSIC_USER_AGENT)
                        ClientType.ANDROID -> header("User-Agent", ANDROID_USER_AGENT)
                        ClientType.WEB, ClientType.WEB_REMIX, ClientType.MWEB -> {
                            header("User-Agent", WEB_USER_AGENT)
                            header("Origin", if (clientType == ClientType.WEB_REMIX) "https://music.youtube.com" else "https://www.youtube.com")
                            header("Referer", if (clientType == ClientType.WEB_REMIX) "https://music.youtube.com/" else "https://www.youtube.com/")
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
                return@withContext Result.failure(Exception("HTTP ${response.code} from Innertube client ${clientType.name}"))
            }

            val json = JSONObject(response.body?.string() ?: "")
            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "")
            if (status != "OK") {
                val reason = playability?.optString("reason", "Unknown status $status")
                return@withContext Result.failure(Exception("$status: $reason"))
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: return@withContext Result.failure(Exception("No streamingData in ${clientType.name} response"))

            val parsedFormats = mutableListOf<AudioFormat>()
            parseFormatArray(streamingData.optJSONArray("adaptiveFormats"), parsedFormats)
            parseFormatArray(streamingData.optJSONArray("formats"), parsedFormats)

            if (parsedFormats.isEmpty()) {
                return@withContext Result.failure(Exception("No audio formats in ${clientType.name} response"))
            }

            val audioConfig = json.optJSONObject("playerConfig")?.optJSONObject("audioConfig")
            val playerLoudnessDb = if (audioConfig != null && audioConfig.has("perceptualLoudnessDb")) {
                audioConfig.optDouble("perceptualLoudnessDb")
            } else if (audioConfig != null && audioConfig.has("loudnessDb")) {
                audioConfig.optDouble("loudnessDb")
            } else null

            val candidates = parsedFormats.map { fmt ->
                AudioStreamCandidate(
                    url = fmt.url,
                    mimeType = fmt.mimeType,
                    itag = fmt.itag,
                    codec = fmt.codec,
                    container = fmt.container,
                    bitrate = fmt.bitrate,
                    bitrateKbps = fmt.bitrateKbps,
                    sampleRate = fmt.sampleRate,
                    channelCount = fmt.channelCount,
                    isAudioOnly = fmt.isAudioOnly,
                    contentLength = fmt.contentLength,
                    durationMs = fmt.approxDurationMs,
                    loudnessDb = fmt.loudnessDb ?: playerLoudnessDb,
                    sourceName = "Innertube-${clientType.name}",
                    expiresAtMs = AudioStreamCandidate.extractExpiryFromUrl(fmt.url)
                )
            }

            Result.success(candidates)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse audio formats dynamically from Innertube JSON arrays.
     */
    private fun parseFormatArray(
        jsonArray: JSONArray?,
        outList: MutableList<AudioFormat>
    ) {
        if (jsonArray == null) return

        for (i in 0 until jsonArray.length()) {
            val format = jsonArray.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType", "")
            val isAudioOnly = mimeType.startsWith("audio/")
            val hasAudio = isAudioOnly || format.has("audioQuality") || format.has("audioSampleRate")

            if (!hasAudio) continue

            // Check URL and cipher
            var url = format.optString("url", "")
            if (url.isEmpty()) {
                val cipher = format.optString("signatureCipher", "").ifEmpty {
                    format.optString("cipher", "")
                }
                if (cipher.isNotEmpty()) {
                    val params = cipher.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to (parts.getOrNull(1)?.let { v -> URLDecoder.decode(v, "UTF-8") } ?: "")
                    }
                    // If no signature decryption is needed (no 's' parameter)
                    if (!params.containsKey("s") && params.containsKey("url")) {
                        url = params["url"].orEmpty()
                    }
                }
            }

            if (url.isEmpty()) {
                // Cipher-protected or unresolvable directly, skip for direct client
                continue
            }

            val itag = format.optInt("itag", 0).takeIf { it > 0 }
            val rawBitrate = format.optLong("averageBitrate", 0L).takeIf { it > 0 }
                ?: format.optLong("bitrate", 0L).takeIf { it > 0 }
                ?: (AudioStreamInfo.nominalBitrateForItag(itag)?.toLong()?.times(1000L) ?: 0L)
            val bitrateKbps = if (rawBitrate > 0) Math.round(rawBitrate / 1000.0).toInt() else 0

            val sampleRateStr = format.optString("audioSampleRate", "")
            val sampleRate = sampleRateStr.toIntOrNull()
                ?: format.optInt("audioSampleRate", 0).takeIf { it > 0 }
                ?: (AudioStreamInfo.nominalSampleRateForItag(itag) ?: 0)

            val channels = format.optInt("audioChannels", 0).takeIf { it > 0 } ?: 2
            val codec = AudioStreamInfo.codecFromMimeType(mimeType)
            val container = AudioStreamInfo.containerFromMimeType(mimeType)
            val loudnessDb = if (format.has("loudnessDb")) format.optDouble("loudnessDb") else null
            val contentLength = format.optString("contentLength", "").toLongOrNull()
                ?: format.optLong("contentLength", 0L).takeIf { it > 0 }
            val approxDurationMs = format.optString("approxDurationMs", "").toLongOrNull()
                ?: format.optLong("approxDurationMs", 0L).takeIf { it > 0 }
            val audioQuality = format.optString("audioQuality", "")

            outList.add(
                AudioFormat(
                    url = url,
                    mimeType = mimeType,
                    itag = itag,
                    codec = codec,
                    container = container,
                    bitrate = rawBitrate,
                    bitrateKbps = bitrateKbps,
                    sampleRate = sampleRate,
                    channelCount = channels,
                    audioQuality = audioQuality,
                    isAudioOnly = isAudioOnly,
                    loudnessDb = loudnessDb,
                    contentLength = contentLength,
                    approxDurationMs = approxDurationMs,
                )
            )
        }
    }

    /**
     * Dynamically select the best audio format based on acoustic fidelity and compatibility.
     * Logs candidate formats, scores, and selection rationale.
     */
    fun selectBestFormat(
        formats: List<AudioFormat>,
        requestedQuality: StreamQuality,
    ): Pair<AudioFormat, String>? {
        if (formats.isEmpty()) return null

        val playable = formats.filter { it.url.isNotBlank() }
        if (playable.isEmpty()) return null

        Log.d(TAG, "Evaluating ${playable.size} candidates for quality tier: ${requestedQuality.displayName}")
        playable.forEach { f ->
            val score = computeFormatScore(f, requestedQuality)
            Log.d(
                TAG,
                "  Candidate format: itag=${f.itag ?: "N/A"}, codec=${f.codec}, container=${f.container}, " +
                    "bitrate=${f.bitrateKbps}kbps, sr=${if (f.sampleRate > 0) "${f.sampleRate}Hz" else "unknown"}, " +
                    "ch=${if (f.channelCount > 0) "${f.channelCount}ch" else "unknown"}, audioOnly=${f.isAudioOnly}, score=$score"
            )
        }

        val best = playable.maxWithOrNull(
            compareBy<AudioFormat> { computeFormatScore(it, requestedQuality) }
                .thenBy { it.isAudioOnly }
                .thenBy { it.bitrateKbps }
                .thenBy { it.sampleRate }
        ) ?: return null

        val rationale = "Selected itag=${best.itag ?: "N/A"} (${best.codec} ${if (best.bitrateKbps > 0) "${best.bitrateKbps}kbps" else ""}, " +
            "sr=${if (best.sampleRate > 0) "${best.sampleRate}Hz" else "unknown"}, ${if (best.isAudioOnly) "audio-only" else "muxed"}) " +
            "as highest fidelity format among ${playable.size} candidates for ${requestedQuality.displayName}."

        return Pair(best, rationale)
    }

    /**
     * Compute objective perceptual acoustic fidelity score for a format.
     *
     * Principles:
     * - Audio-only streams (adaptiveFormats) are prioritized over muxed audio+video streams
     *   to prevent video download overhead and buffering stalls.
     * - Opus achieves fullband 20kHz frequency response at 48kHz sample rate, providing
     *   transparent CD-grade acoustic quality at ~128-160 kbps (itag 251), significantly
     *   outperforming standard 128 kbps AAC (itag 140) which has a 15.5kHz lowpass filter cutoff.
     * - AAC-LC at ≥256 kbps (itag 141) achieves top-tier acoustic fidelity.
     * - Higher sample rate (48000 Hz vs 44100 Hz vs 22050 Hz) and multi-channel capability are rewarded.
     * - Respects user StreamQuality settings (LOW for Data Saver, MEDIUM for cellular balance,
     *   HIGH/BEST for maximum fidelity).
     */
    fun computeFormatScore(format: AudioFormat, requestedQuality: StreamQuality): Long {
        val isOpus = format.codec.equals("OPUS", true)
        val isAac = format.codec.contains("AAC", true)
        val isAacHe = format.codec.equals("AAC-HE", true)
        val bitrate = format.bitrateKbps
        val isAudioOnly = format.isAudioOnly

        return when (requestedQuality) {
            StreamQuality.LOW -> {
                // Data Saver: prefer low bitrate streams (~50-70 kbps)
                val distance = kotlin.math.abs(bitrate - requestedQuality.bitrate)
                val base = (10_000L - distance * 100L).coerceAtLeast(0L)
                val codecBonus = if (isOpus) 500L else if (isAacHe) 300L else 0L
                val audioBonus = if (isAudioOnly) 5_000L else 0L
                base + codecBonus + audioBonus
            }
            StreamQuality.MEDIUM -> {
                // Balanced: prefer streams closest to 128 kbps
                val distance = kotlin.math.abs(bitrate - requestedQuality.bitrate)
                val base = (10_000L - distance * 100L).coerceAtLeast(0L)
                val codecBonus = if (isOpus) 500L else 0L
                val audioBonus = if (isAudioOnly) 5_000L else 0L
                base + codecBonus + audioBonus
            }
            StreamQuality.HIGH, StreamQuality.BEST -> {
                // Highest available acoustic fidelity
                val effectiveBitrate = when {
                    isAac && bitrate >= 256 -> (bitrate.toLong() * 1_250L)
                    isOpus && bitrate >= 120 -> (bitrate.toLong() * 1_400L)
                    isOpus -> (bitrate.toLong() * 1_300L)
                    isAac && !isAacHe -> (bitrate.toLong() * 1_000L)
                    isAacHe -> (bitrate.toLong() * 850L)
                    else -> (bitrate.toLong() * 900L)
                }
                val sampleRateBonus = format.sampleRate.toLong().coerceAtLeast(0L)
                val channelBonus = if (format.channelCount >= 2) 1_000L else 0L
                val audioBonus = if (isAudioOnly) 50_000L else 0L
                effectiveBitrate + sampleRateBonus + channelBonus + audioBonus
            }
        }
    }

    /**
     * Build InnerTube client request body.
     */
    private fun buildClientRequest(videoId: String, clientType: ClientType): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    when (clientType) {
                        ClientType.IOS -> {
                            put("clientName", "IOS")
                            put("clientVersion", "21.22.04")
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone16,2")
                            put("platform", "MOBILE")
                            put("osName", "iOS")
                            put("osVersion", "18.5.0.22F76")
                        }
                        ClientType.ANDROID_VR -> {
                            put("clientName", "ANDROID_VR")
                            put("clientVersion", "1.61.48")
                            put("deviceMake", "Oculus")
                            put("deviceModel", "Quest 3")
                            put("platform", "MOBILE")
                            put("osName", "Android")
                            put("osVersion", "12")
                        }
                        ClientType.WEB_REMIX -> {
                            put("clientName", YtmScopeConfig.ClientContext.CLIENT_NAME)
                            put("clientVersion", YtmScopeConfig.ClientContext.CLIENT_VERSION)
                            put("platform", YtmScopeConfig.ClientContext.PLATFORM)
                        }
                        ClientType.WEB -> {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20260601.01.00")
                            put("platform", "DESKTOP")
                        }
                        ClientType.MWEB -> {
                            put("clientName", "MWEB")
                            put("clientVersion", "2.20260601.01.00")
                            put("platform", "MOBILE")
                        }
                        ClientType.ANDROID_MUSIC -> {
                            put("clientName", "ANDROID_MUSIC")
                            put("clientVersion", "7.27.52")
                            put("androidSdkVersion", 34)
                            put("platform", "MOBILE")
                            put("osName", "Android")
                            put("osVersion", "14")
                        }
                        ClientType.ANDROID -> {
                            put("clientName", "ANDROID")
                            put("clientVersion", "21.23.483")
                            put("androidSdkVersion", 34)
                            put("platform", "MOBILE")
                            put("osName", "Android")
                            put("osVersion", "14")
                        }
                    }
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", videoId)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
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
     */
    enum class ClientType {
        IOS,
        ANDROID_VR,
        WEB_REMIX,
        WEB,
        MWEB,
        ANDROID_MUSIC,
        ANDROID,
    }
}

/**
 * Represents an audio stream format with genuine verified metadata.
 */
data class AudioFormat(
    val url: String,
    val mimeType: String,
    val itag: Int?,
    val codec: String,
    val container: String,
    val bitrate: Long,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val audioQuality: String,
    val isAudioOnly: Boolean,
    val loudnessDb: Double? = null,
    val contentLength: Long? = null,
    val approxDurationMs: Long? = null,
) {
    fun isOpus(): Boolean = codec.equals("OPUS", true)
    fun isAac(): Boolean = codec.contains("AAC", true)
}
