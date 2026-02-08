package com.sonicmusic.app.data.remote.source

import android.util.Log
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
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NewPipe Extractor Service
 * 
 * Uses the official NewPipe Extractor library for YouTube audio extraction.
 * This library handles:
 * - Cipher/signature decryption
 * - Age restriction bypass
 * - Various player client fallbacks
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
                ContentCountry.DEFAULT
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
     * Extract audio stream URL from a video ID
     */
    suspend fun getStreamUrl(videoId: String, quality: StreamQuality): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎵 Extracting audio for: $videoId")
        
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
                Log.d(TAG, "  - ${stream.format?.name}: ${stream.averageBitrate}kbps, URL: ${stream.content?.take(50)}...")
            }
            
            // Select best stream based on quality preference
            val selectedStream = selectBestAudioStream(audioStreams, quality)
            
            if (selectedStream == null) {
                Log.e(TAG, "❌ Could not select audio stream")
                return@withContext Result.failure(Exception("Could not select audio stream"))
            }
            
            // Get the stream URL - content contains the direct URL
            val streamUrl = selectedStream.content
            if (streamUrl.isNullOrEmpty()) {
                Log.e(TAG, "❌ Stream URL is empty")
                return@withContext Result.failure(Exception("Stream URL is empty"))
            }
            
            Log.d(TAG, "✅ Selected stream: ${selectedStream.averageBitrate}kbps, format: ${selectedStream.format?.name}")
            Log.d(TAG, "🔗 Stream URL: ${streamUrl.take(100)}...")
            Result.success(streamUrl)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Extraction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Select the best audio stream based on quality preference
     */
    private fun selectBestAudioStream(streams: List<AudioStream>, quality: StreamQuality): AudioStream? {
        if (streams.isEmpty()) return null
        
        // Filter out DASH/WebM streams with issues, prefer M4A/MP4
        val sortedStreams = streams
            .filter { it.content?.isNotEmpty() == true }
            .sortedWith(
                compareByDescending<AudioStream> { it.averageBitrate }
                    .thenBy { stream ->
                        // Prefer M4A/MP4 for better compatibility
                        when {
                            stream.format?.name?.contains("m4a", true) == true -> 0
                            stream.format?.name?.contains("mp4", true) == true -> 1
                            stream.format?.name?.contains("webm", true) == true -> 2
                            stream.format?.name?.contains("opus", true) == true -> 3
                            else -> 4
                        }
                    }
            )
        
        return when (quality) {
            StreamQuality.BEST, StreamQuality.HIGH -> sortedStreams.firstOrNull()
            StreamQuality.MEDIUM -> sortedStreams.getOrNull(sortedStreams.size / 2) ?: sortedStreams.firstOrNull()
            StreamQuality.LOW -> sortedStreams.lastOrNull()
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
                thumbnailUrl = thumbnailUrl
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
    suspend fun searchSongs(query: String, limit: Int = 20): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Searching: $query")
            
            val service = getYouTubeService()
            // Use correct API: getSearchExtractor(query, contentFilters, sortFilter)
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf("music_songs"), // Content filter for music
                null // Default sort
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
                            // Use maxresdefault for highest quality thumbnail
                            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
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
