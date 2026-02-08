package com.sonicmusic.app.data.downloadmanager

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.sonicmusic.app.data.local.entity.DownloadedSongEntity
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Download Manager for offline song downloads
 * 
 * Manages downloading songs for offline playback
 */
@Singleton
class SongDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioStreamExtractor: AudioStreamExtractor
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    
    private val _downloadQueue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadTask>> = _downloadQueue.asStateFlow()

    companion object {
        const val DOWNLOADS_DIRECTORY = "SonicMusic/Downloads"
    }

    /**
     * Download a song for offline playback
     */
    suspend fun downloadSong(song: Song, quality: StreamQuality): Result<Long> = withContext(Dispatchers.IO) {
        try {
            // Get stream URL
            val streamResult = audioStreamExtractor.extractAudioStream(song.id, quality)
            
            if (streamResult.isFailure) {
                return@withContext Result.failure(
                    streamResult.exceptionOrNull() ?: Exception("Failed to get stream URL")
                )
            }

            val streamUrl = streamResult.getOrThrow()
            
            // Create download request
            val request = DownloadManager.Request(Uri.parse(streamUrl))
                .setTitle(song.title)
                .setDescription(song.artist)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_MUSIC,
                    "$DOWNLOADS_DIRECTORY/${song.id}.m4a"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            // Enqueue download
            val downloadId = downloadManager.enqueue(request)
            
            // Add to queue tracking
            addToQueue(DownloadTask(
                downloadId = downloadId,
                song = song,
                quality = quality,
                status = DownloadStatus.PENDING
            ))
            
            Result.success(downloadId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get download progress
     */
    fun getDownloadProgress(downloadId: Long): Flow<DownloadProgress> = flow {
        val query = DownloadManager.Query().setFilterById(downloadId)
        
        while (true) {
            val cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                
                val progress = if (bytesTotal > 0) {
                    (bytesDownloaded * 100 / bytesTotal)
                } else 0

                val downloadStatus = when (status) {
                    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                    DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                    else -> DownloadStatus.UNKNOWN
                }

                emit(DownloadProgress(
                    downloadId = downloadId,
                    progress = progress,
                    bytesDownloaded = bytesDownloaded,
                    bytesTotal = bytesTotal,
                    status = downloadStatus
                ))

                if (status == DownloadManager.STATUS_SUCCESSFUL || 
                    status == DownloadManager.STATUS_FAILED) {
                    break
                }
            }
            cursor.close()
            kotlinx.coroutines.delay(500) // Check every 500ms
        }
    }

    /**
     * Cancel a download
     */
    fun cancelDownload(downloadId: Long) {
        downloadManager.remove(downloadId)
        removeFromQueue(downloadId)
    }

    /**
     * Get downloaded file path
     */
    fun getDownloadedFilePath(songId: String): String? {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "$DOWNLOADS_DIRECTORY/$songId.m4a"
        )
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Check if a song is downloaded
     */
    fun isDownloaded(songId: String): Boolean {
        return getDownloadedFilePath(songId) != null
    }

    /**
     * Delete downloaded song
     */
    fun deleteDownload(songId: String): Boolean {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "$DOWNLOADS_DIRECTORY/$songId.m4a"
        )
        return file.delete()
    }

    /**
     * Get total size of all downloads
     */
    fun getTotalDownloadSize(): Long {
        val downloadsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            DOWNLOADS_DIRECTORY
        )
        if (!downloadsDir.exists()) return 0
        
        return downloadsDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /**
     * Clear all downloads
     */
    fun clearAllDownloads(): Boolean {
        val downloadsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            DOWNLOADS_DIRECTORY
        )
        if (!downloadsDir.exists()) return true
        
        return downloadsDir.listFiles()?.all { it.delete() } ?: true
    }

    private fun addToQueue(task: DownloadTask) {
        _downloadQueue.value = _downloadQueue.value + task
    }

    private fun removeFromQueue(downloadId: Long) {
        _downloadQueue.value = _downloadQueue.value.filter { it.downloadId != downloadId }
    }
}

/**
 * Represents a download task
 */
data class DownloadTask(
    val downloadId: Long,
    val song: Song,
    val quality: StreamQuality,
    val status: DownloadStatus
)

/**
 * Download status
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    UNKNOWN
}

/**
 * Download progress information
 */
data class DownloadProgress(
    val downloadId: Long,
    val progress: Int,
    val bytesDownloaded: Int,
    val bytesTotal: Int,
    val status: DownloadStatus
)