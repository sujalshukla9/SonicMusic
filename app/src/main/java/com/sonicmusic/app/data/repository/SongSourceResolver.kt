package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.core.network.NetworkMonitor
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import java.io.File
import com.sonicmusic.app.domain.model.AudioStreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback source types with atomic verified stream metadata.
 */
sealed class SongSource {
    data class Local(val filePath: String, val info: AudioStreamInfo) : SongSource()
    data class Remote(val streamUrl: String, val info: AudioStreamInfo) : SongSource()
    data object Unavailable : SongSource()
}

/**
 * Resolves the stream source for a video ID.
 * Chooses Local file if downloaded, Remote URL if online, or Unavailable if offline.
 */
@Singleton
class SongSourceResolver @Inject constructor(
    private val downloadedSongDao: DownloadedSongDao,
    private val songRepository: dagger.Lazy<SongRepository>,
    private val networkMonitor: NetworkMonitor
) {
    companion object {
        private const val TAG = "SongSourceResolver"
    }

    suspend fun getSongStreamSource(videoId: String): SongSource {
        // 1. Check Room DB if song is downloaded
        val downloadedSong = downloadedSongDao.getDownloadedSong(videoId)
        if (downloadedSong != null && downloadedSong.status == DownloadStatus.COMPLETED) {
            val file = File(downloadedSong.localFilePath)
            if (file.exists()) {
                Log.d(TAG, "Found offline local file: ${downloadedSong.localFilePath}")
                val localResult = runCatching {
                    songRepository.get().getStreamWithInfo(videoId, StreamQuality.BEST).getOrNull()
                }.getOrNull()
                val info = localResult?.second ?: AudioStreamInfo(
                    codec = "Local",
                    isLocal = true,
                    contentLength = file.length().takeIf { it > 0 }
                )
                return SongSource.Local(downloadedSong.localFilePath, info)
            } else {
                // Song deleted from disk externally but DB still has entry -> detect on access, mark as REMOVED, prompt re-download
                Log.w(TAG, "Offline file not found on disk, updating DB status to REMOVED: $videoId")
                downloadedSongDao.updateStatus(videoId, DownloadStatus.REMOVED)
            }
        }

        // 2. Else if online -> resolve via Innertube player endpoint
        if (networkMonitor.isConnectedNow()) {
            return try {
                Log.d(TAG, "Fetching remote stream URL online for: $videoId")
                val (streamUrl, info) = songRepository.get().getStreamWithInfo(videoId, StreamQuality.BEST).getOrThrow()
                SongSource.Remote(streamUrl, info)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch remote stream URL", e)
                SongSource.Unavailable
            }
        }

        // 3. Else -> return Unavailable
        Log.w(TAG, "Offline and song is not downloaded: $videoId")
        return SongSource.Unavailable
    }
}
