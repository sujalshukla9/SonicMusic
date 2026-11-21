package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.core.network.HttpStatusException
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.core.network.awaitSuccessfulBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.features.player.audio.AudioQualitySelector

/**
 * YouTube audio stream extractor with automatic best-quality selection.
 *
 * Architecture:
 * 1. Primary: NewPipe cipher-decrypted extraction engine discovering valid audio streams.
 * 2. Dedicated AudioQualitySelector: objectively scores candidates (AAC 256k, Opus 160k, etc.)
 *    against actual device decoder capabilities and genuine technical properties.
 * 3. Fallback: Direct Innertube extraction across supported client identities when cipher
 *    recovery or alternative formats are needed.
 * 4. Caching: Reuses discovered candidates and
 *    respects genuine URL expiration timestamps.
 */
@Singleton
class AudioStreamExtractor @Inject constructor(
    private val newPipeService: NewPipeService,
    private val okHttpClient: OkHttpClient,
    private val audioQualitySelector: AudioQualitySelector,
    private val accountClient: YouTubeMusicAccountClient
) {

    private val client = okHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class CachedCandidates(
        val streams: List<AudioStreamCandidate>,
        val expiresAtMs: Long
    )

    private val streamCache = android.util.LruCache<String, CachedCandidates>(128)
    private val extractionLocks = Array(64) { Mutex() }
    private val fallbackClients = listOf(
        ClientType.IOS, ClientType.ANDROID_VR, ClientType.WEB_REMIX, ClientType.WEB,
        ClientType.MWEB, ClientType.ANDROID_MUSIC, ClientType.ANDROID
    )

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
            streamCache.evictAll()
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
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> =
        extractAllCandidates(videoId, forceRefresh, excludeUrls).mapCatching { candidates ->
            val selected = checkNotNull(audioQualitySelector.selectBestCandidate(candidates, quality, excludeUrls))
            selected.candidate to selected.candidate.toAudioStreamInfo(quality)
        }

    /** Compare both providers before selecting a format. Concurrent requests share discovery. */
    suspend fun extractAllCandidates(
        videoId: String,
        forceRefresh: Boolean = false,
        excludeUrls: Set<String> = emptySet()
    ): Result<List<AudioStreamCandidate>> = withContext(Dispatchers.IO) {
        val observed = streamCache.get(videoId)
        val lock = extractionLocks[(videoId.hashCode() and Int.MAX_VALUE) % extractionLocks.size]
        lock.withLock {
            val cached = streamCache.get(videoId)
            if (cached != null && (!forceRefresh || cached !== observed) &&
                cached.expiresAtMs > System.currentTimeMillis() &&
                cached.streams.any { audioQualitySelector.isCandidateUsable(it, excludeUrls) }
            ) {
                return@withLock Result.success(cached.streams)
            }

            val (primary, alternatives) = coroutineScope {
                val alternatives = async { probeAlternatives(videoId) }
                newPipeService.extractAudioCandidates(videoId) to alternatives.await()
            }
            val discovered = (primary.getOrNull().orEmpty() + alternatives.flatMap { it.getOrNull().orEmpty() })
                .filter { audioQualitySelector.isCandidateUsable(it, emptySet()) }
                .groupBy { it.url }
                .values.map { duplicates ->
                    val strongest = duplicates.maxWith(compareBy<AudioStreamCandidate> { it.bitrateBps > 0 }
                        .thenBy { it.codecProfile != null }
                        .thenBy { it.sampleRate > 0 }
                        .thenBy { it.channelCount > 0 })
                    strongest.copy(loudnessDb = strongest.loudnessDb ?: duplicates.firstNotNullOfOrNull { it.loudnessDb })
                }
            if (discovered.isEmpty()) {
                return@withLock Result.failure(
                    primary.exceptionOrNull() ?: IllegalStateException("No playable audio streams for $videoId")
                )
            }

            val cacheLifetime = if (alternatives.any { it.isSuccess }) 900_000L else 60_000L
            streamCache.put(videoId, CachedCandidates(discovered, System.currentTimeMillis() + cacheLifetime))
            Result.success(discovered)
        }
    }

    private suspend fun probeAlternatives(videoId: String): List<Result<List<AudioStreamCandidate>>> = coroutineScope {
        fallbackClients.map { client -> async { extractCandidatesWithClient(videoId, client) } }.awaitAll()
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

            val json = JSONObject(client.newCall(request).awaitSuccessfulBody().orEmpty())
            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "")
            if (status != "OK") {
                val reason = playability?.optString("reason", "Unknown status $status")
                return@withContext Result.failure(Exception("$status: $reason"))
            }

            val candidates = parsePlayerCandidates(json, "Innertube-${clientType.name}")
            if (candidates.isEmpty()) {
                return@withContext Result.failure(Exception("No audio formats in ${clientType.name} response"))
            }
            Result.success(candidates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun verifyPlaybackSession(session: YouTubePlaybackSession): Boolean {
        val locale = java.util.Locale.getDefault()
        return accountClient.verifySession(
            session,
            locale.language.ifBlank { "en" },
            locale.country.ifBlank { "US" },
            locale.toLanguageTag()
        )
    }

    internal suspend fun extractAuthenticatedCandidates(
        videoId: String,
        session: YouTubePlaybackSession,
    ): List<AudioStreamCandidate> {
        return try {
            val signatureTimestamp = withContext(Dispatchers.IO) {
                runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
            }
            val json = authenticatedMusicRequest("player", session) {
                put("videoId", videoId)
                val playback = JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                signatureTimestamp?.let { playback.put("signatureTimestamp", it) }
                put("playbackContext", JSONObject().put("contentPlaybackContext", playback))
            }
            val candidates = withContext(Dispatchers.IO) {
                parseAuthenticatedPlayerCandidates(
                    json,
                    decodeSignature = { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, it) },
                    normalizeUrl = { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, it) }
                )
            }
            val data = json.optJSONObject("streamingData")
            val status = json.optJSONObject("playabilityStatus")?.optString("status")
                ?.takeIf { it.matches(Regex("[A-Z_]{1,40}")) }
            Log.d(TAG, "Authenticated player status=${status ?: "unknown"}, " +
                "adaptive=${data?.optJSONArray("adaptiveFormats")?.length() ?: 0}, " +
                "serverAbr=${!data?.optString("serverAbrStreamingUrl").isNullOrBlank()}, " +
                "usable=${candidates.size}, maxKbps=${candidates.maxOfOrNull { it.bitrateKbps } ?: 0}")
            candidates
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            Log.d(TAG, "Authenticated player HTTP ${e.code}")
            emptyList()
        } catch (_: Exception) {
            Log.d(TAG, "Authenticated player request or response failed")
            emptyList()
        }
    }

    internal fun parseAuthenticatedPlayerCandidates(
        json: JSONObject,
        decodeSignature: ((String) -> String)? = null,
        normalizeUrl: (String) -> String = { it },
    ): List<AudioStreamCandidate> {
        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status")
        val audioAvailability = playability?.optJSONObject("audioOnlyPlayability")
            ?.optJSONObject("audioOnlyPlayabilityRenderer")?.optString("audioOnlyAvailability").orEmpty()
        val loggedOut = json.optJSONObject("responseContext")
            ?.optJSONObject("mainAppWebResponseContext")?.optBoolean("loggedOut") == true
        if (status != "OK" || loggedOut ||
            (audioAvailability.isNotEmpty() && audioAvailability != "FEATURE_AVAILABILITY_ALLOWED")
        ) return emptyList()

        return parsePlayerCandidates(json, "Innertube-YouTubeMusic", adaptiveAudioOnly = true) { format ->
            runCatching {
                val url = resolveFormatUrl(format, decodeSignature) ?: return@runCatching null
                if (isYouTubeAudioUrl(url)) normalizeUrl(url).takeIf(::isYouTubeAudioUrl) else null
            }.getOrNull()
        }.filter { audioQualitySelector.isCandidateUsable(it, emptySet()) }
    }

    private suspend fun authenticatedMusicRequest(
        endpoint: String,
        session: YouTubePlaybackSession,
        body: JSONObject.() -> Unit = {},
    ): JSONObject = withContext(Dispatchers.IO) {
        val locale = java.util.Locale.getDefault()
        accountClient.post(
            "/youtubei/v1/$endpoint",
            session,
            locale.language.ifBlank { "en" },
            locale.country.ifBlank { "US" },
            locale.toLanguageTag(),
            body
        )
    }

    private fun parsePlayerCandidates(
        json: JSONObject,
        source: String,
        adaptiveAudioOnly: Boolean = false,
        resolveUrl: (JSONObject) -> String? = { resolveFormatUrl(it) },
    ): List<AudioStreamCandidate> {
        val streamingData = json.optJSONObject("streamingData") ?: return emptyList()
        val formats = mutableListOf<AudioFormat>()
        parseFormatArray(streamingData.optJSONArray("adaptiveFormats"), formats, adaptiveAudioOnly, resolveUrl)
        if (!adaptiveAudioOnly) parseFormatArray(streamingData.optJSONArray("formats"), formats, false, resolveUrl)

        val audioConfig = json.optJSONObject("playerConfig")?.optJSONObject("audioConfig")
        val loudness = audioConfig?.optDouble("loudnessDb")?.takeIf(Double::isFinite)
            ?: audioConfig?.optDouble("perceptualLoudnessDb")?.takeIf(Double::isFinite)
        return formats.map { it.toCandidate(source, loudness) }
    }

    private fun isYouTubeAudioUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com"))
    }

    private fun resolveFormatUrl(format: JSONObject, decodeSignature: ((String) -> String)? = null): String? {
        format.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
        if (cipher.isBlank()) return null
        val parameters = cipher.split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        val url = parameters["url"]?.takeIf { it.isNotBlank() } ?: return null
        val obfuscatedSignature = parameters["s"] ?: return url
        if (decodeSignature == null || !isYouTubeAudioUrl(url)) return null
        val signature = decodeSignature(obfuscatedSignature).takeIf { it.isNotBlank() } ?: return null
        val parameter = parameters["sp"]?.takeIf { it.isNotBlank() } ?: "signature"
        return url.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter(parameter, signature)?.build()?.toString()
    }

    private fun parseFormatArray(
        jsonArray: JSONArray?,
        outList: MutableList<AudioFormat>,
        audioOnly: Boolean,
        resolveUrl: (JSONObject) -> String?,
    ) {
        if (jsonArray == null) return

        for (i in 0 until jsonArray.length()) {
            val format = jsonArray.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType", "")
            val isAudioOnly = mimeType.startsWith("audio/")
            if (audioOnly && (!isAudioOnly || format.optJSONArray("drmFamilies")?.length()?.let { it > 0 } == true)) {
                continue
            }
            val hasAudio = isAudioOnly || format.has("audioQuality") || format.has("audioSampleRate")

            if (!hasAudio) continue

            val url = resolveUrl(format) ?: continue

            val itag = format.optInt("itag", 0).takeIf { it > 0 }
            val contentLength = format.optLong("contentLength").takeIf { it > 0 }
            val durationMs = format.optLong("approxDurationMs").takeIf { it > 0 }
            val rawBitrate = if (isAudioOnly) {
                format.optLong("averageBitrate").takeIf { it > 0 }
                    ?: audioBitrateBps(contentLength, durationMs)
                    ?: format.optLong("bitrate").coerceAtLeast(0L)
            } else 0L
            val bitrateKbps = Math.round(rawBitrate / 1000.0).toInt()
            val sampleRate = format.optInt("audioSampleRate").coerceAtLeast(0)
            val channels = format.optInt("audioChannels").coerceAtLeast(0)
            val codec = AudioStreamInfo.codecFromMimeType(mimeType)
            if (codec == "Unknown") continue
            val container = AudioStreamInfo.containerFromMimeType(mimeType)
            val loudnessDb = format.optDouble("loudnessDb").takeIf(Double::isFinite)
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
                    approxDurationMs = durationMs,
                )
            )
        }
    }

    internal fun selectBestFormat(
        formats: List<AudioFormat>,
        requestedQuality: StreamQuality
    ): Pair<AudioFormat, String>? {
        val candidates = formats.associateBy { it.toCandidate("Innertube") }
        val selected = audioQualitySelector.selectBestCandidate(candidates.keys.toList(), requestedQuality)
            ?: return null
        return candidates.getValue(selected.candidate) to selected.rationale
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
    fun toCandidate(source: String, defaultLoudness: Double? = null) = AudioStreamCandidate(
        url = url,
        mimeType = mimeType,
        itag = itag,
        codec = codec,
        codecProfile = AUDIO_CODEC_TOKEN.find(mimeType)?.value,
        container = container,
        bitrate = bitrate,
        bitrateKbps = bitrateKbps,
        sampleRate = sampleRate,
        channelCount = channelCount,
        isAudioOnly = isAudioOnly,
        contentLength = contentLength,
        durationMs = approxDurationMs,
        loudnessDb = loudnessDb ?: defaultLoudness,
        sourceName = source,
        expiresAtMs = AudioStreamCandidate.extractExpiryFromUrl(url)
    )
}

private val AUDIO_CODEC_TOKEN = Regex("(?i)mp4a\\.[a-z0-9.]+|opus|vorbis|flac|alac")

internal fun audioBitrateBps(contentLength: Long?, durationMs: Long?): Long? {
    if (contentLength == null || durationMs == null || durationMs <= 0) return null
    return (contentLength * 8000.0 / durationMs).toLong().takeIf { it > 0 }
}
