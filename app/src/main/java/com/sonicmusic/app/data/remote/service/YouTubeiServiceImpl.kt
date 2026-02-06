package com.sonicmusic.app.data.remote.service

import com.sonicmusic.app.data.remote.api.YouTubeiService
import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject

class YouTubeiServiceImpl @Inject constructor() : YouTubeiService {

    override suspend fun searchSongs(query: String): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val service = ServiceList.YouTube
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                null
            )
            searchExtractor.fetchPage()
            
            val songs = searchExtractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .map { item ->
                    Song(
                        id = item.url.replace("https://www.youtube.com/watch?v=", ""), // Simple ID extraction
                        title = item.name,
                        artist = item.uploaderName,
                        artistId = item.uploaderUrl, // This might need parsing
                        album = null,
                        albumId = null,
                        duration = item.duration.toInt(),
                        thumbnailUrl = item.thumbnails.firstOrNull()?.url ?: "",
                        isLiked = false
                    )
                }
            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
             val service = ServiceList.YouTube
             val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
             extractor.fetchPage()
             // Logic to pick best audio stream
             val audioStream = extractor.audioStreams
                 .filter { it.codec == "m4a" || it.codec == "webm" }
                 .maxByOrNull { it.averageBitrate }
                 
             if (audioStream != null) {
                 Result.success(audioStream.content)
             } else {
                 Result.failure(Exception("No suitable audio stream found"))
             }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getSongDetails(videoId: String): Result<Song> {
        TODO("Not yet implemented")
    }
}
