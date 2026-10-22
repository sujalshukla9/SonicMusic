package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ArtistRef
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import okhttp3.OkHttpClient
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.features.player.audio.AudioQualitySelector
import kotlin.math.abs
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NewPipe Extractor Service — Production-Quality Audio Extraction
 *
 * Uses the official NewPipe Extractor library for YouTube audio extraction.
 * Extracts genuine technical stream descriptors without fabricating values.
 */
@Singleton
class NewPipeService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val audioQualitySelector: AudioQualitySelector = AudioQualitySelector()
) {

    companion object {
        private const val TAG = "NewPipeService"
        private var isInitialized = false
        private const val CAPTCHA_COOLDOWN_MS = 30 * 1000L // 30s cooldown instead of 15m
        private val ENGLISH_LOCALIZATION = Localization("en", "US")
    }

    @Volatile
    private var captchaBlockedUntilMs: Long = 0L

    init {
        initializeNewPipe()
    }

    /**
     * Initialize NewPipe Extractor with custom Downloader
     */
    @Synchronized
    private fun initializeNewPipe() {
        if (isInitialized) return
        
        try {
            NewPipe.init(
                NewPipeDownloader.getInstance(okHttpClient),
                ENGLISH_LOCALIZATION,
                ContentCountry.DEFAULT,
            )
            isInitialized = true
            Log.d(TAG, "NewPipe Extractor initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NewPipe", e)
        }
    }

    /**
     * Update NewPipe Content Country
     */
    fun updateRegion(countryCode: String) {
        try {
            NewPipe.init(
                NewPipeDownloader.getInstance(okHttpClient),
                ENGLISH_LOCALIZATION,
                ContentCountry(countryCode)
            )
            Log.d(TAG, "NewPipe region updated to: $countryCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update NewPipe region", e)
        }
    }

    /**
     * Get the YouTube service from NewPipe
     */
    private fun getYouTubeService(): YoutubeService {
        return NewPipe.getService(ServiceList.YouTube.serviceId) as YoutubeService
    }

    /**
     * Extract all genuine audio stream candidates from a video ID via NewPipe.
     * Preserves genuine reported bitrate, sample rate, channels, container, and codec.
     * Extracts genuine URL expiration from YouTube's expire parameter without guessing.
     */
    suspend fun extractAudioCandidates(
        videoId: String
    ): Result<List<AudioStreamCandidate>> = withContext(Dispatchers.IO) {
        if (isCaptchaCooldownActive()) {
            return@withContext Result.failure(Exception("NewPipe temporarily blocked by reCAPTCHA"))
        }

        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(NewPipe.getService(0), url)

            val audioStreams = streamInfo.audioStreams.orEmpty()
            if (audioStreams.isEmpty()) {
                Log.w(TAG, "No audio streams found by NewPipe for $videoId")
                return@withContext Result.failure(Exception("No audio streams available"))
            }

            val candidates = audioStreams.mapNotNull { stream ->
                val streamUrl = stream.content.orEmpty().ifBlank { return@mapNotNull null }
                val itagItem = stream.itagItem
                val rawCodec = stream.codec
                    ?: itagItem?.codec
                    ?: stream.format?.mimeType
                    ?: stream.format?.name.orEmpty()
                val codec = AudioStreamInfo.codecFromMimeType(rawCodec)
                val container = stream.format?.name?.let { AudioStreamInfo.containerFromMimeType(it) }
                    ?: AudioStreamInfo.containerFromMimeType(rawCodec)
                val itag = stream.format?.id?.takeIf { it > 0 } ?: itagItem?.id?.takeIf { it > 0 }
                val parsedBitrate = stream.averageBitrate.takeIf { it > 0 }
                    ?: itagItem?.bitrate?.takeIf { it > 0 }
                    ?: AudioStreamInfo.nominalBitrateForItag(itag)
                    ?: 0
                val bitrateKbps = when {
                    parsedBitrate > 1000 -> Math.round(parsedBitrate / 1000.0).toInt()
                    parsedBitrate > 0 -> parsedBitrate
                    else -> 0
                }
                val rawBitrate = (bitrateKbps.toLong() * 1000L).takeIf { it > 0 } ?: 0L
                val sampleRate = itagItem?.sampleRate?.takeIf { it > 0 }
                    ?: AudioStreamInfo.nominalSampleRateForItag(itag)
                    ?: 0
                val channelCount = itagItem?.audioChannels?.takeIf { it > 0 } ?: 2
                val contentLength = itagItem?.contentLength?.takeIf { it > 0 }
                val expiresAtMs = AudioStreamCandidate.extractExpiryFromUrl(streamUrl)

                AudioStreamCandidate(
                    url = streamUrl,
                    mimeType = stream.format?.mimeType ?: "audio/${container.lowercase()}",
                    itag = itag,
                    codec = codec,
                    codecProfile = itagItem?.codec,
                    container = container,
                    bitrate = rawBitrate,
                    bitrateKbps = bitrateKbps,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    isAudioOnly = true,
                    contentLength = contentLength,
                    sourceName = "NewPipe",
                    expiresAtMs = expiresAtMs
                )
            }

            if (candidates.isEmpty()) {
                return@withContext Result.failure(Exception("No playable audio candidates in NewPipe response"))
            }

            Log.d(TAG, "NewPipe extracted ${candidates.size} audio candidates for $videoId")
            Result.success(candidates)
        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "NewPipe blocked by reCAPTCHA for $videoId")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe candidate extraction failed for $videoId: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Extract audio stream URL + metadata from a video ID with automatic best-quality selection.
     * Returns URL and AudioStreamInfo for quality badge and playback.
     */
    suspend fun getStreamUrl(
        videoId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        val candidatesResult = extractAudioCandidates(videoId)
        if (candidatesResult.isFailure) {
            return@withContext Result.failure(candidatesResult.exceptionOrNull()!!)
        }

        val candidates = candidatesResult.getOrThrow()
        val selection = audioQualitySelector.selectBestCandidate(candidates, quality)
            ?: return@withContext Result.failure(Exception("Could not select suitable audio stream for tier ${quality.displayName}"))

        val selected = selection.candidate
        val info = selected.toAudioStreamInfo(quality)
        Log.d(TAG, "NewPipe selected: ${info.fullDescription}. URL: ${selected.url.take(80)}...")
        Result.success(Pair(selected.url, info))
    }


    /**
     * Get song details from video ID
     */
    suspend fun getSongDetails(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        if (isCaptchaCooldownActive()) {
            return@withContext Result.failure(Exception("NewPipe temporarily blocked by reCAPTCHA"))
        }

        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(NewPipe.getService(0), url)
            
            // Use maxresdefault for highest quality thumbnail
            val thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            
            val author = streamInfo.uploaderName ?: "Unknown Artist"
            val artistInfo = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                runs = null,
                playerAuthor = author
            )
            val uploaderUrl = streamInfo.uploaderUrl.orEmpty()
            val channelId = if (uploaderUrl.contains("/channel/")) {
                uploaderUrl.substringAfter("/channel/").substringBefore("/").takeIf { it.startsWith("UC") }
            } else null

            val artistRefs = if (artistInfo.individuals.isNotEmpty()) {
                artistInfo.individuals.map { ArtistRef(it.name, it.browseId ?: channelId) }
            } else {
                listOf(ArtistRef(artistInfo.displayName, channelId))
            }

            val song = Song(
                id = videoId,
                title = streamInfo.name,
                artist = artistInfo.displayName,
                artistId = channelId ?: artistRefs.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId,
                artists = artistRefs,
                duration = streamInfo.duration.toInt(),
                thumbnailUrl = thumbnailUrl,
            )
            
            Result.success(song)
        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "NewPipe song details blocked by reCAPTCHA.")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get song details", e)
            Result.failure(e)
        }
    }

    /**
     * Search for songs with offset-based pagination.
     * Uses NewPipe next-page API so callers can keep loading more results.
     */
    suspend fun searchSongs(
        query: String,
        limit: Int = 50,
        offset: Int = 0
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (isCaptchaCooldownActive()) {
            return@withContext Result.failure(Exception("NewPipe temporarily blocked by reCAPTCHA"))
        }

        try {
            val safeLimit = limit.coerceAtLeast(1)
            val safeOffset = offset.coerceAtLeast(0)
            val targetSize = safeOffset + safeLimit
            val maxPagesToFetch = 20

            Log.d(TAG, "Searching: $query (limit=$safeLimit, offset=$safeOffset)")

            val service = getYouTubeService()
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf("music_songs"),
                null
            )

            searchExtractor.fetchPage()

            var page = searchExtractor.initialPage
            val uniqueSongs = linkedMapOf<String, Song>()
            var fetchedPages = 1

            fun collectPageSongs(items: List<*>) {
                items.forEach { item ->
                    val streamItem = item as? org.schabi.newpipe.extractor.stream.StreamInfoItem ?: return@forEach
                    val mapped = mapStreamItemToSong(streamItem) ?: return@forEach
                    uniqueSongs.putIfAbsent(mapped.id, mapped)
                }
            }

            collectPageSongs(page.items)

            while (
                uniqueSongs.size < targetSize &&
                page.hasNextPage() &&
                fetchedPages < maxPagesToFetch
            ) {
                val nextPage = page.nextPage ?: break
                page = searchExtractor.getPage(nextPage)
                collectPageSongs(page.items)
                fetchedPages += 1
            }

            val songs = uniqueSongs.values
                .drop(safeOffset)
                .take(safeLimit)

            Log.d(TAG, "Found ${songs.size} songs (unique=${uniqueSongs.size}, pages=$fetchedPages)")
            Result.success(songs)

        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "NewPipe search blocked by reCAPTCHA for query=\"$query\"")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get songs by artist name.
     * Searches for the artist and filters results to match the artist name.
     */
    suspend fun getArtistSongs(
        artistName: String,
        limit: Int = 30
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (isCaptchaCooldownActive()) {
            return@withContext Result.failure(Exception("NewPipe temporarily blocked by reCAPTCHA"))
        }

        try {
            val safeLimit = limit.coerceAtLeast(1).coerceAtMost(2000)
            Log.d(TAG, "Fetching songs for artist: $artistName (limit=$safeLimit)")

            val service = getYouTubeService()
            val searchExtractor = service.getSearchExtractor(
                artistName,
                listOf("music_songs"),
                null
            )

            searchExtractor.fetchPage()

            var page = searchExtractor.initialPage
            val uniqueSongs = linkedMapOf<String, Song>()
            var fetchedPages = 1
            val pageEstimate = (safeLimit / 20) + 2
            val maxPages = pageEstimate.coerceIn(8, 50)
            val normalizedArtist = normalizeArtistName(artistName)

            fun collectArtistSongs(items: List<*>) {
                items.forEach { item ->
                    val streamItem = item as? org.schabi.newpipe.extractor.stream.StreamInfoItem ?: return@forEach
                    val mapped = mapStreamItemToSong(streamItem) ?: return@forEach
                    // Filter: song artist must contain the searched artist name
                    if (isArtistMatch(mapped.artist, normalizedArtist)) {
                        uniqueSongs.putIfAbsent(mapped.id, mapped)
                    }
                }
            }

            collectArtistSongs(page.items)

            while (
                uniqueSongs.size < safeLimit &&
                page.hasNextPage() &&
                fetchedPages < maxPages
            ) {
                val nextPage = page.nextPage ?: break
                page = searchExtractor.getPage(nextPage)
                collectArtistSongs(page.items)
                fetchedPages += 1
            }

            val songs = uniqueSongs.values.take(safeLimit)
            Log.d(TAG, "Found ${songs.size} songs for artist: $artistName (pages=$fetchedPages)")
            Result.success(songs)

        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "NewPipe artist search blocked by reCAPTCHA for artist=\"$artistName\"")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Artist songs fetch failed", e)
            Result.failure(e)
        }
    }

    fun resetCooldown() {
        captchaBlockedUntilMs = 0L
    }

    private fun markCaptchaBlocked() {
        captchaBlockedUntilMs = System.currentTimeMillis() + CAPTCHA_COOLDOWN_MS
    }

    private fun isCaptchaCooldownActive(): Boolean {
        return System.currentTimeMillis() < captchaBlockedUntilMs
    }

    private fun mapStreamItemToSong(item: org.schabi.newpipe.extractor.stream.StreamInfoItem): Song? {
        return try {
            val videoId = extractVideoId(item.url)
            if (videoId.isBlank()) return null

            val author = item.uploaderName ?: "Unknown"
            val artistInfo = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                runs = null,
                playerAuthor = author
            )
            val uploaderUrl = item.uploaderUrl.orEmpty()
            val channelId = if (uploaderUrl.contains("/channel/")) {
                uploaderUrl.substringAfter("/channel/").substringBefore("/").takeIf { it.startsWith("UC") }
            } else null

            val artistRefs = if (artistInfo.individuals.isNotEmpty()) {
                artistInfo.individuals.map { ArtistRef(it.name, it.browseId ?: channelId) }
            } else {
                listOf(ArtistRef(artistInfo.displayName, channelId))
            }

            Song(
                id = videoId,
                title = item.name,
                artist = artistInfo.displayName,
                artistId = channelId ?: artistRefs.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId,
                artists = artistRefs,
                duration = item.duration.toInt(),
                thumbnailUrl = selectBestThumbnailUrl(item, videoId)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Select best available thumbnail from extractor metadata.
     * This avoids forcing generic video frames (hq720) for music searches.
     */
    private fun selectBestThumbnailUrl(
        item: org.schabi.newpipe.extractor.stream.StreamInfoItem,
        videoId: String
    ): String {
        val thumbnails = runCatching { item.thumbnails }.getOrNull().orEmpty()
        val bestExtractorUrl = thumbnails
            .asSequence()
            .mapNotNull { image ->
                val url = image.url.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ThumbnailCandidate(
                    url = url,
                    score = thumbnailScore(image)
                )
            }
            .maxByOrNull { it.score }
            ?.url

        val normalized = bestExtractorUrl?.let { url ->
            // Preserve tuned YouTube/YouTube Music CDN URLs when provided by extractor.
            if (url.contains("googleusercontent.com", ignoreCase = true)) {
                ThumbnailUrlUtils.toHighQuality(url, videoId) ?: url
            } else {
                url
            }
        }

        return normalized ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
    }

    private fun thumbnailScore(image: Image): Long {
        val width = image.width.coerceAtLeast(0)
        val height = image.height.coerceAtLeast(0)
        val area = if (width > 0 && height > 0) width.toLong() * height.toLong() else 0L
        val maxEdge = max(width, height).toFloat().coerceAtLeast(1f)
        val ratioDelta = if (width > 0 && height > 0) abs(width - height) / maxEdge else 1f
        val squareBonus = when {
            ratioDelta <= 0.08f -> 2_000_000L
            ratioDelta <= 0.18f -> 1_000_000L
            else -> 0L
        }
        val url = image.url.lowercase()
        val musicCdnBonus = if (url.contains("googleusercontent.com")) 1_500_000L else 0L
        return area + squareBonus + musicCdnBonus
    }

    private data class ThumbnailCandidate(
        val url: String,
        val score: Long
    )

    /**
     * Extract video ID from YouTube URL
     */
    private fun extractVideoId(url: String): String {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            else -> url
        }
    }

    private fun normalizeArtistName(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isArtistMatch(candidateArtist: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return true
        val normalizedCandidate = normalizeArtistName(candidateArtist)
        if (normalizedCandidate.isBlank()) return false
        if (normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate)) {
            return true
        }

        val queryTokens = normalizedQuery.split(" ").filter { it.length >= 3 }.toSet()
        val candidateTokens = normalizedCandidate.split(" ").filter { it.length >= 3 }.toSet()
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false
        return queryTokens.intersect(candidateTokens).isNotEmpty()
    }
}
