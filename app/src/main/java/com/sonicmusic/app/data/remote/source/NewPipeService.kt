package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * NewPipe Extractor Service — Apple Music-Style Quality Selection
 * 
 * Uses the official NewPipe Extractor library for YouTube audio extraction.
 * Now with codec-aware stream selection:
 * - BEST → OPUS preferred at highest available bitrate
 * - HIGH → AAC preferred (256kbps, better compatibility)
 * - MEDIUM → Any codec at 128kbps
 * - LOW → Lowest available
 */
@Singleton
class NewPipeService @Inject constructor() {

    companion object {
        private const val TAG = "NewPipeService"
        private var isInitialized = false
        private const val CAPTCHA_COOLDOWN_MS = 15 * 60 * 1000L
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
                NewPipeDownloader.getInstance(),
                ENGLISH_LOCALIZATION,
                ContentCountry.DEFAULT,
            )
            isInitialized = true
            Log.d(TAG, "✅ NewPipe Extractor initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize NewPipe", e)
        }
    }

    /**
     * Update NewPipe Content Country
     */
    fun updateRegion(countryCode: String) {
        try {
            NewPipe.init(
                NewPipeDownloader.getInstance(),
                ENGLISH_LOCALIZATION,
                ContentCountry(countryCode)
            )
            Log.d(TAG, "🌍 NewPipe region updated to: $countryCode")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update NewPipe region", e)
        }
    }

    /**
     * Get the YouTube service from NewPipe
     */
    private fun getYouTubeService(): YoutubeService {
        return NewPipe.getService(ServiceList.YouTube.serviceId) as YoutubeService
    }

    /**
     * Extract audio stream URL + metadata from a video ID.
     * Returns URL and AudioStreamInfo for quality badge display.
     */
    suspend fun getStreamUrl(
        videoId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        if (isCaptchaCooldownActive()) {
            return@withContext Result.failure(Exception("NewPipe temporarily blocked by reCAPTCHA"))
        }

        Log.d(TAG, "🎵 Extracting audio for: $videoId (tier: ${quality.displayName})")
        
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(NewPipe.getService(0), url)
            
            val audioStreams = streamInfo.audioStreams
            Log.d(TAG, "📊 Found ${audioStreams.size} audio streams")
            
            if (audioStreams.isEmpty()) {
                Log.e(TAG, "❌ No audio streams available")
                return@withContext Result.failure(Exception("No audio streams available"))
            }
            
            // Log available streams for debugging
            audioStreams.forEach { stream ->
                Log.d(TAG, "  📡 ${stream.format?.name}: ${stream.averageBitrate}kbps")
            }
            
            // Select best stream based on quality preference with codec awareness
            val selectedStream = selectBestAudioStream(audioStreams, quality)
            
            if (selectedStream == null) {
                Log.e(TAG, "❌ Could not select audio stream")
                return@withContext Result.failure(Exception("Could not select audio stream"))
            }
            
            // Get the stream URL
            val streamUrl = selectedStream.content
            if (streamUrl.isNullOrEmpty()) {
                Log.e(TAG, "❌ Stream URL is empty")
                return@withContext Result.failure(Exception("Stream URL is empty"))
            }
            
            // Build AudioStreamInfo for quality badge display
            val mimeType = selectedStream.format?.mimeType ?: selectedStream.format?.name ?: "unknown"
            val codec = AudioStreamInfo.codecFromMimeType(mimeType)
            val container = AudioStreamInfo.containerFromMimeType(mimeType)
            val bitrate = selectedStream.averageBitrate
            val sampleRate = AudioStreamInfo.sampleRateFromCodec(codec)
            val tier = AudioStreamInfo.qualityTierFromStream(codec, bitrate)
            
            val info = AudioStreamInfo(
                codec = codec,
                bitrate = bitrate,
                sampleRate = sampleRate,
                bitDepth = 16, // YouTube serves 16-bit
                qualityTier = tier,
                containerFormat = container,
                channelCount = 2,
            )
            
            Log.d(TAG, "✅ Selected: ${info.fullDescription}")
            Log.d(TAG, "🏷️ Quality: ${info.qualityBadge}")
            Log.d(TAG, "🔗 URL: ${streamUrl.take(80)}...")
            
            Result.success(Pair(streamUrl, info))
            
        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "⚠️ NewPipe blocked by reCAPTCHA. Cooling down before next attempt.")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Extraction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Select the best audio stream based on quality preference.
     * 
     * Codec-aware selection:
     * - BEST: OPUS preferred, then highest bitrate
     * - HIGH: AAC/M4A preferred at 256kbps (compatibility)
     * - MEDIUM: Any codec at ~128kbps
     * - LOW: Lowest available
     */
    private fun selectBestAudioStream(streams: List<AudioStream>, quality: StreamQuality): AudioStream? {
        if (streams.isEmpty()) return null
        
        val validStreams = streams.filter { it.content.isNotEmpty() }
        if (validStreams.isEmpty()) return null
        
        return when (quality) {
            StreamQuality.BEST -> {
                // Highest real source quality first (bitrate, codec tiebreak).
                validStreams.maxByOrNull(::bestSourceScore)
            }
            
            StreamQuality.HIGH -> {
                // Prefer AAC/M4A > 120kbps (itag 140 is 128k, 141 is 256k)
                validStreams.filter { 
                    val mime = it.format?.mimeType ?: it.format?.name.orEmpty()
                    (mime.contains("m4a", true) || 
                     mime.contains("mp4", true)) &&
                    it.averageBitrate >= 120
                }.maxByOrNull { it.averageBitrate }
                // Fallback to Opus optimized
                ?: validStreams.filter { it.averageBitrate >= 120 }.maxByOrNull { it.averageBitrate }
                // Fallback
                ?: validStreams.maxByOrNull { it.averageBitrate }
            }
            
            StreamQuality.MEDIUM -> {
                // Target ~128kbps
                 validStreams.filter { it.averageBitrate in 100..160 }
                    .maxByOrNull { it.averageBitrate } // Get the highest in this range
                    ?: validStreams.sortedBy { kotlin.math.abs(it.averageBitrate - 128) }.firstOrNull()
            }
            
            StreamQuality.LOW -> {
                // Lowest bitrate available
                validStreams.minByOrNull { it.averageBitrate }
            }
        }
    }

    private fun bestSourceScore(stream: AudioStream): Long {
        val mimeType = stream.format?.mimeType ?: stream.format?.name.orEmpty()
        val codecBonus = when {
            mimeType.contains("opus", true) || mimeType.contains("webm", true) -> 20L
            mimeType.contains("m4a", true) || mimeType.contains("mp4", true) -> 10L
            else -> 0L
        }
        return (stream.averageBitrate.toLong() * 1_000L) + codecBonus
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
            
            val song = Song(
                id = videoId,
                title = streamInfo.name,
                artist = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                    runs = null,
                    playerAuthor = streamInfo.uploaderName ?: "Unknown Artist"
                ).displayName,
                duration = streamInfo.duration.toInt(),
                thumbnailUrl = thumbnailUrl,
            )
            
            Result.success(song)
        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "⚠️ NewPipe song details blocked by reCAPTCHA.")
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

            Log.d(TAG, "🔍 Searching: $query (limit=$safeLimit, offset=$safeOffset)")

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

            Log.d(TAG, "✅ Found ${songs.size} songs (unique=${uniqueSongs.size}, pages=$fetchedPages)")
            Result.success(songs)

        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "⚠️ NewPipe search blocked by reCAPTCHA for query=\"$query\"")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Search failed", e)
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
            Log.d(TAG, "🎤 Fetching songs for artist: $artistName (limit=$safeLimit)")

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
            Log.d(TAG, "✅ Found ${songs.size} songs for artist: $artistName (pages=$fetchedPages)")
            Result.success(songs)

        } catch (e: ReCaptchaException) {
            markCaptchaBlocked()
            Log.w(TAG, "⚠️ NewPipe artist search blocked by reCAPTCHA for artist=\"$artistName\"")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Artist songs fetch failed", e)
            Result.failure(e)
        }
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

            Song(
                id = videoId,
                title = item.name,
                artist = com.sonicmusic.app.data.remote.model.ArtistExtractor.extract(
                    runs = null,
                    playerAuthor = item.uploaderName ?: "Unknown"
                ).displayName,
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
