package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NewPipe Extractor Service — Apple Music-Style Quality Selection
 * 
 * Uses the official NewPipe Extractor library for YouTube audio extraction.
 * Now with codec-aware stream selection:
 * - LOSSLESS/BEST → OPUS preferred (transparent at 160kbps+)
 * - HIGH → AAC preferred (256kbps, better compatibility)
 * - MEDIUM → Any codec at 128kbps
 * - LOW → Lowest available
 */
@Singleton
class NewPipeService @Inject constructor() {

    companion object {
        private const val TAG = "NewPipeService"
        private var isInitialized = false
    }

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
                Localization.DEFAULT,
                ContentCountry.DEFAULT,
            )
            isInitialized = true
            Log.d(TAG, "✅ NewPipe Extractor initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize NewPipe", e)
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
            val formatName = selectedStream.format?.name ?: "unknown"
            val codec = AudioStreamInfo.codecFromMimeType(formatName)
            val container = AudioStreamInfo.containerFromMimeType(formatName)
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
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Extraction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Select the best audio stream based on quality preference.
     * 
     * Apple Music-style codec-aware selection:
     * - LOSSLESS: OPUS at highest bitrate (transparent quality)
     * - BEST/HIGH_RES: OPUS preferred, then highest bitrate
     * - HIGH: AAC/M4A preferred at 256kbps (compatibility)
     * - MEDIUM: Any codec at ~128kbps
     * - LOW: Lowest available
     */
    private fun selectBestAudioStream(streams: List<AudioStream>, quality: StreamQuality): AudioStream? {
        if (streams.isEmpty()) return null
        
        val validStreams = streams.filter { it.content?.isNotEmpty() == true }
        if (validStreams.isEmpty()) return null
        
        return when (quality) {
            StreamQuality.LOSSLESS, StreamQuality.BEST -> {
                // Prefer OPUS > 150kbps (usually itag 251 is ~160kbps)
                validStreams.filter { 
                    (it.format?.name?.contains("opus", true) == true || 
                     it.format?.name?.contains("webm", true) == true) && 
                    it.averageBitrate >= 150 
                }.maxByOrNull { it.averageBitrate }
                // Fallback to highest bitrate M4A/AAC
                ?: validStreams.filter { 
                     it.format?.name?.contains("m4a", true) == true || 
                     it.format?.name?.contains("mp4", true) == true
                }.maxByOrNull { it.averageBitrate }
                // Fallback to highest overall
                ?: validStreams.maxByOrNull { it.averageBitrate }
            }
            
            StreamQuality.HIGH -> {
                // Prefer AAC/M4A > 120kbps (itag 140 is 128k, 141 is 256k)
                validStreams.filter { 
                    (it.format?.name?.contains("m4a", true) == true || 
                     it.format?.name?.contains("mp4", true) == true) &&
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

    /**
     * Get song details from video ID
     */
    suspend fun getSongDetails(videoId: String): Result<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(NewPipe.getService(0), url)
            
            // Use maxresdefault for highest quality thumbnail
            val thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            
            val song = Song(
                id = videoId,
                title = streamInfo.name,
                artist = streamInfo.uploaderName ?: "Unknown Artist",
                duration = streamInfo.duration.toInt(),
                thumbnailUrl = thumbnailUrl,
            )
            
            Result.success(song)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get song details", e)
            Result.failure(e)
        }
    }

    /**
     * Search for songs
     */
    suspend fun searchSongs(query: String, limit: Int = 50): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Searching: $query")
            
            val service = getYouTubeService()
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf("music_songs"),
                null,
            )
            
            searchExtractor.fetchPage()
            
            val items = searchExtractor.initialPage.items
            val songs = items.mapNotNull { item ->
                try {
                    if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                        val videoId = extractVideoId(item.url)
                        Song(
                            id = videoId,
                            title = item.name,
                            artist = item.uploaderName ?: "Unknown",
                            duration = item.duration.toInt(),
                            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hq720.jpg",
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }.take(limit)
            
            Log.d(TAG, "✅ Found ${songs.size} songs")
            Result.success(songs)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Search failed", e)
            Result.failure(e)
        }
    }

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
}
