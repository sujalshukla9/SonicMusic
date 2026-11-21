package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.core.network.NetworkMonitor
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.dao.LocalSongDao
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.deviceLocalSongId
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
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
    private val localSongDao: LocalSongDao,
    private val songRepository: dagger.Lazy<SongRepository>,
    private val networkMonitor: NetworkMonitor
) {
    companion object {
        private const val TAG = "SongSourceResolver"
    }

    suspend fun getSongStreamSource(
        song: com.sonicmusic.app.domain.model.Song,
        onUpgradeAvailable: ((AudioStreamCandidate, AudioStreamInfo) -> Unit)? = null
    ): SongSource {
        return getSongStreamSource(
            videoId = song.id,
            title = song.title,
            artist = song.artist,
            durationSec = song.duration,
            onUpgradeAvailable = onUpgradeAvailable
        )
    }

    suspend fun getSongStreamSource(
        videoId: String,
        title: String? = null,
        artist: String? = null,
        durationSec: Int? = null,
        onUpgradeAvailable: ((AudioStreamCandidate, AudioStreamInfo) -> Unit)? = null
    ): SongSource {
        // A saved device song keeps its MediaStore id across process restarts.
        videoId.deviceLocalSongId()?.let { id ->
            val song = localSongDao.getLocalSongById(id) ?: return SongSource.Unavailable
            val source = song.filePath
            val exists = withContext(Dispatchers.IO) {
                source.startsWith("content://") || (source.startsWith('/') && File(source).isFile)
            }
            if (!exists) return SongSource.Unavailable
            return SongSource.Local(
                source,
                AudioStreamInfo(codec = "Local", isLocal = true, contentLength = song.fileSize.takeIf { it > 0 })
            )
        }

        // 1. Check Room DB if song is downloaded
        val downloadedSong = downloadedSongDao.getDownloadedSong(videoId)
        if (downloadedSong != null && downloadedSong.status == DownloadStatus.COMPLETED) {
            val file = File(downloadedSong.localFilePath)
            val size = withContext(Dispatchers.IO) { if (file.isFile) file.length() else null }
            if (size != null) {
                Log.d(TAG, "Found offline local file: ${downloadedSong.localFilePath}")
                val localInfo = try {
                    songRepository.get().getStreamWithInfo(
                        songId = videoId,
                        quality = StreamQuality.BEST,
                        title = title,
                        artist = artist,
                        durationSec = durationSec
                    ).getOrNull()?.takeIf { it.first == file.absolutePath }?.second
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val info = localInfo ?: AudioStreamInfo(
                    codec = "Local",
                    isLocal = true,
                    contentLength = size.takeIf { it > 0 }
                )
                return SongSource.Local(downloadedSong.localFilePath, info)
            } else {
                Log.w(TAG, "Offline file not found on disk, updating DB status to REMOVED: $videoId")
                downloadedSongDao.updateStatus(videoId, DownloadStatus.REMOVED)
            }
        }

        // 2. Else if online -> resolve via multi-source intelligent pipeline
        if (networkMonitor.isConnectedNow()) {
            return try {
                Log.d(TAG, "Fetching remote stream URL online for: $videoId ($title by $artist)")
                val (streamUrl, info) = songRepository.get().getStreamWithInfo(
                    songId = videoId,
                    quality = StreamQuality.BEST,
                    forceRefresh = false,
                    title = title,
                    artist = artist,
                    durationSec = durationSec,
                    onUpgradeAvailable = onUpgradeAvailable
                ).getOrThrow()
                SongSource.Remote(streamUrl, info)
            } catch (e: CancellationException) {
                throw e
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
