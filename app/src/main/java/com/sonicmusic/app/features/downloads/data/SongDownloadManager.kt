package com.sonicmusic.app.features.downloads.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.entity.DownloadedSong
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import coil.ImageLoader
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class SongDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media3DownloadManager: DownloadManager,
    private val simpleCache: SimpleCache,
    private val downloadedSongDao: DownloadedSongDao,
    private val audioStreamExtractor: AudioStreamExtractor
) {
    companion object {
        private const val TAG = "SongDownloadManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    /** One-shot error messages for the UI layer (e.g. storage full, network errors). */
    private val _downloadErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val downloadErrors: SharedFlow<String> = _downloadErrors.asSharedFlow()

    // Track download requests and their metadata in memory to handle completions and titles
    private val downloadMetadata = ConcurrentHashMap<String, DownloadMetadata>()

    private data class DownloadMetadata(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String
    )

    init {
        // Ensure downloads are resumed in Media3 (since they are paused by default on initialization without a service wrapper)
        media3DownloadManager.resumeDownloads()

        // Observe Media3 download changes
        media3DownloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                handleDownloadStateChange(download, finalException)
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                Log.d(TAG, "Download removed in Media3: ${download.request.id}")
                removeProgress(download.request.id)
            }
        })

        // Restore active downloads list from Media3 index on startup and clean up stale REMOVED records
        scope.launch {
            delay(1000) // Allow DB/Media3 to settle
            try {
                // Clean up stale REMOVED records
                downloadedSongDao.deleteByStatus(DownloadStatus.REMOVED)
                Log.d(TAG, "Cleaned up stale REMOVED download records from DB")

                val cursor = media3DownloadManager.downloadIndex.getDownloads()
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    val videoId = download.request.id
                    val entity = downloadedSongDao.getDownloadedSong(videoId)
                    if (entity != null && download.state != Download.STATE_COMPLETED) {
                        downloadMetadata[videoId] = DownloadMetadata(
                            videoId = videoId,
                            title = entity.title,
                            artist = entity.artists,
                            thumbnailUrl = entity.thumbnailUrl
                        )
                        // If it was downloading before crash, resume it
                        if (download.state == Download.STATE_FAILED || download.state == Download.STATE_RESTARTING) {
                            // Expired URL fallback on startup retry
                            retryDownload(videoId)
                        } else {
                            updateProgressFromDownload(download)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring downloads on startup", e)
            }
        }
    }

    /**
     * Download a song using Media3 DownloadManager.
     */
    fun downloadSong(song: Song, quality: StreamQuality = StreamQuality.BEST) {
        scope.launch {
            val videoId = song.id
            Log.d(TAG, "Requesting download: ${song.title}")

            // 1. Check storage space
            val destMusicDir = File(context.getExternalFilesDir(null), "Music").apply { mkdirs() }
            if (destMusicDir.usableSpace < 104_857_600L) { // 100 MB
                Log.w(TAG, "Disk space too low for download: ${destMusicDir.usableSpace} bytes available")
                _downloadErrors.tryEmit("Storage space too low for downloads")
                return@launch
            }

            val destThumbDir = File(context.getExternalFilesDir(null), "Thumbnails").apply { mkdirs() }
            val localFilePath = File(destMusicDir, "$videoId.opus").absolutePath // default placeholder

            val initialEntity = DownloadedSong(
                videoId = videoId,
                title = song.title,
                artists = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                localFilePath = localFilePath,
                localThumbnailPath = "",
                fileSizeBytes = 0L,
                durationMs = 0L,
                downloadedAt = System.currentTimeMillis(),
                status = DownloadStatus.PENDING
            )
            downloadedSongDao.insertDownloadedSong(initialEntity)
            downloadMetadata[videoId] = DownloadMetadata(videoId, song.title, song.artist, song.thumbnailUrl)

            updateProgress(
                videoId,
                DownloadProgress(
                    songId = videoId,
                    title = song.title,
                    progress = 0,
                    bytesDownloaded = 0,
                    bytesTotal = 0,
                    status = DownloadStatus.PENDING
                )
            )

            // 2. Resolve Stream URL
            val streamResult = audioStreamExtractor.extractAudioStream(videoId, quality)
            if (streamResult.isFailure) {
                Log.e(TAG, "Failed to extract stream URL for download: $videoId")
                updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
                return@launch
            }

            val (streamUrl, streamInfo) = streamResult.getOrThrow()
            val bypassedUrl = applySpeedBypass(streamUrl)
            
            // 3. Download and compress thumbnail
            val localThumbnailFile = downloadAndSaveThumbnail(videoId, song.thumbnailUrl)
            val thumbnailPath = localThumbnailFile?.absolutePath ?: ""

            // Update database with local thumbnail path and status = QUEUED
            val queuedEntity = initialEntity.copy(
                localThumbnailPath = thumbnailPath,
                status = DownloadStatus.QUEUED,
                durationMs = song.duration * 1000L
            )
            downloadedSongDao.insertDownloadedSong(queuedEntity)
            updateDbAndProgressStatus(videoId, DownloadStatus.QUEUED)

            // 4. Start Media3 Download
            val downloadRequest = DownloadRequest.Builder(videoId, Uri.parse(bypassedUrl))
                .setCustomCacheKey(videoId)
                .build()

            withContext(Dispatchers.Main) {
                media3DownloadManager.addDownload(downloadRequest)
                media3DownloadManager.resumeDownloads()
            }
        }
    }

    /**
     * Pause a download.
     */
    fun pauseDownload(videoId: String) {
        scope.launch {
            Log.d(TAG, "Pausing download: $videoId")
            withContext(Dispatchers.Main) {
                media3DownloadManager.setStopReason(videoId, 1) // Any non-zero stop reason pauses
            }
            updateDbAndProgressStatus(videoId, DownloadStatus.PAUSED)
        }
    }

    /**
     * Resume a paused download.
     */
    fun resumeDownload(videoId: String) {
        scope.launch {
            Log.d(TAG, "Resuming download: $videoId")
            // Re-resolve URL to ensure it hasn't expired before resuming
            updateDbAndProgressStatus(videoId, DownloadStatus.RETRYING)
            val _metadata = downloadMetadata[videoId] ?: return@launch
            
            val streamResult = audioStreamExtractor.extractAudioStream(videoId, StreamQuality.BEST)
            if (streamResult.isFailure) {
                Log.e(TAG, "Failed to re-resolve URL during resume for: $videoId")
                updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
                return@launch
            }

            val (streamUrl, _) = streamResult.getOrThrow()
            val bypassedUrl = applySpeedBypass(streamUrl)
            
            val downloadRequest = DownloadRequest.Builder(videoId, Uri.parse(bypassedUrl))
                .setCustomCacheKey(videoId)
                .build()

            withContext(Dispatchers.Main) {
                // Remove stop reasons to resume download with new URL
                media3DownloadManager.addDownload(downloadRequest)
                media3DownloadManager.setStopReason(videoId, Download.STOP_REASON_NONE)
            }
        }
    }

    /**
     * Cancel and remove download.
     */
    fun cancelDownload(videoId: String) {
        scope.launch {
            Log.d(TAG, "Cancelling download: $videoId")
            withContext(Dispatchers.Main) {
                media3DownloadManager.removeDownload(videoId)
            }
            deleteDownload(videoId)
        }
    }

    /**
     * Check if a song is downloaded.
     */
    suspend fun isDownloaded(songId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = downloadedSongDao.getDownloadedSong(songId)
        if (entity != null && entity.status == DownloadStatus.COMPLETED) {
            val file = File(entity.localFilePath)
            file.exists()
        } else {
            false
        }
    }

    /**
     * Expose downloads flow.
     */
    fun getDownloadedSongs() = downloadedSongDao.getAllDownloadedSongs()

    /**
     * Restore a previously deleted [DownloadedSong] by re-inserting its DB record.
     * Used by the Undo action after a delete. The physical file is preserved if it
     * hasn't been cleaned up yet — if the file is missing, the next play will fall
     * back to streaming automatically.
     */
    suspend fun restoreDownloadedSong(song: DownloadedSong) = withContext(Dispatchers.IO) {
        try {
            downloadedSongDao.insertDownloadedSong(song)
            Log.d(TAG, "↩️ Restored deleted download record: ${song.videoId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore download record: ${song.videoId}", e)
        }
    }

    /**
     * Delete downloaded files and DB records.
     */
    suspend fun deleteDownload(videoId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Deleting download: $videoId")
            val entity = downloadedSongDao.getDownloadedSong(videoId)
            if (entity != null) {
                if (entity.localFilePath.isNotEmpty()) {
                    File(entity.localFilePath).delete()
                }
                if (entity.localThumbnailPath.isNotEmpty()) {
                    File(entity.localThumbnailPath).delete()
                }
            }
            downloadedSongDao.deleteBySongId(videoId)
            downloadMetadata.remove(videoId)
            removeProgress(videoId)
            withContext(Dispatchers.Main) {
                media3DownloadManager.removeDownload(videoId)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete download: $videoId", e)
            false
        }
    }

    /**
     * Get path of the downloaded file.
     */
    suspend fun getPlaybackFile(videoId: String): Result<File> = withContext(Dispatchers.IO) {
        val entity = downloadedSongDao.getDownloadedSong(videoId)
            ?: return@withContext Result.failure(Exception("Song not downloaded"))
        
        if (entity.status != DownloadStatus.COMPLETED) {
            return@withContext Result.failure(Exception("Song download not completed"))
        }

        val file = File(entity.localFilePath)
        if (file.exists()) {
            Result.success(file)
        } else {
            // Edge case: file deleted from disk externally
            downloadedSongDao.updateStatus(videoId, DownloadStatus.REMOVED)
            Result.failure(Exception("Downloaded file not found on disk"))
        }
    }

    /**
     * Clean all downloads.
     */
    suspend fun clearAllDownloads(): Boolean = withContext(Dispatchers.IO) {
        try {
            val context = this@SongDownloadManager.context
            val musicDir = File(context.getExternalFilesDir(null), "Music")
            musicDir.listFiles()?.forEach { it.delete() }
            val thumbDir = File(context.getExternalFilesDir(null), "Thumbnails")
            thumbDir.listFiles()?.forEach { it.delete() }

            downloadedSongDao.deleteAll()
            downloadMetadata.clear()
            _activeDownloads.value = emptyMap()
            withContext(Dispatchers.Main) {
                media3DownloadManager.removeAllDownloads()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all downloads", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL FLOW MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    private fun handleDownloadStateChange(download: Download, finalException: Exception?) {
        val videoId = download.request.id
        val state = download.state

        Log.d(TAG, "onDownloadChanged: id=$videoId state=$state error=${finalException?.message}")

        scope.launch {
            val dbSong = downloadedSongDao.getDownloadedSong(videoId)
            // If the song is already marked COMPLETED in the database, ignore subsequent removal/transition states
            if (dbSong?.status == DownloadStatus.COMPLETED && state != Download.STATE_COMPLETED) {
                Log.d(TAG, "onDownloadChanged: Ignoring state=$state for completed song $videoId")
                return@launch
            }

            if (state == Download.STATE_COMPLETED) {
                exportCacheAndComplete(download)
                return@launch
            }

            if (state == Download.STATE_FAILED) {
                val isHttpError = finalException?.cause is IOException ||
                        finalException?.message?.contains("403") == true ||
                        finalException?.message?.contains("410") == true
                
                if (isHttpError) {
                    Log.w(TAG, "Download failed due to HTTP / Expired URL. Retrying URL resolution...")
                    retryDownload(videoId)
                } else {
                    updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
                }
                return@launch
            }

            updateProgressFromDownload(download)
        }
    }

    private fun updateProgressFromDownload(download: Download) {
        val videoId = download.request.id
        val status = when (download.state) {
            Download.STATE_QUEUED -> DownloadStatus.QUEUED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_STOPPED -> DownloadStatus.PAUSED
            Download.STATE_RESTARTING -> DownloadStatus.RETRYING
            else -> DownloadStatus.UNKNOWN
        }

        val metadata = downloadMetadata[videoId]
        val title = metadata?.title ?: "Unknown Song"
        val progress = download.percentDownloaded.toInt().coerceIn(0, 100)
        val totalBytes = if (download.contentLength == C.LENGTH_UNSET.toLong()) -1L else download.contentLength

        val active = DownloadProgress(
            songId = videoId,
            title = title,
            progress = progress,
            bytesDownloaded = download.bytesDownloaded,
            bytesTotal = totalBytes,
            status = status
        )

        updateProgress(videoId, active)
        scope.launch {
            val dbSong = downloadedSongDao.getDownloadedSong(videoId)
            if (dbSong?.status != DownloadStatus.COMPLETED) {
                downloadedSongDao.updateStatusAndSize(videoId, status, download.contentLength)
            }
        }
    }

    private fun retryDownload(videoId: String) {
        scope.launch {
            updateDbAndProgressStatus(videoId, DownloadStatus.RETRYING)
            val streamResult = audioStreamExtractor.extractAudioStream(videoId, StreamQuality.BEST)
            if (streamResult.isFailure) {
                Log.e(TAG, "Failed to re-resolve URL during download retry for: $videoId")
                updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
                return@launch
            }

            val (streamUrl, _) = streamResult.getOrThrow()
            val bypassedUrl = applySpeedBypass(streamUrl)
            val downloadRequest = DownloadRequest.Builder(videoId, Uri.parse(bypassedUrl))
                .setCustomCacheKey(videoId)
                .build()

            withContext(Dispatchers.Main) {
                media3DownloadManager.addDownload(downloadRequest)
                media3DownloadManager.resumeDownloads()
            }
        }
    }

    private fun exportCacheAndComplete(download: Download) {
        val videoId = download.request.id
        scope.launch {
            updateDbAndProgressStatus(videoId, DownloadStatus.DOWNLOADING) // Still processing
            
            try {
                // Resolve extension from streamUrl or default to opus
                val streamUrl = download.request.uri.toString()
                val ext = if (streamUrl.contains("mime=audio%2Fmp4") || streamUrl.contains(" mime=audio/mp4") || download.request.mimeType?.contains(" mp4") == true) " m4a" else " opus"
                
                val destMusicDir = File(context.getExternalFilesDir(null), "Music").apply { mkdirs() }
                val destFile = File(destMusicDir, "$videoId.$ext")

                Log.d(TAG, "Exporting Media3 cache block stream to single progressive file: ${destFile.absolutePath}")

                // Sequential cache read using CacheDataSource and save to local disk
                exportCacheToFile(videoId, destFile)

                // Save thumbnail if not already present
                val metadata = downloadMetadata[videoId]
                var thumbPath = ""
                if (metadata != null) {
                    val thumbFile = File(File(context.getExternalFilesDir(null), "Thumbnails"), "$videoId.webp")
                    if (!thumbFile.exists()) {
                        downloadAndSaveThumbnail(videoId, metadata.thumbnailUrl)
                    }
                    thumbPath = thumbFile.absolutePath
                }

                // Update Room entry on complete
                val size = destFile.length()
                
                val entity = downloadedSongDao.getDownloadedSong(videoId)
                if (entity != null) {
                    val updatedEntity = entity.copy(
                        localFilePath = destFile.absolutePath,
                        localThumbnailPath = thumbPath,
                        fileSizeBytes = size,
                        status = DownloadStatus.COMPLETED
                    )
                    downloadedSongDao.insertDownloadedSong(updatedEntity)
                }

                // Remove from Media3 DownloadManager to free up the temporary Cache space
                withContext(Dispatchers.Main) {
                    media3DownloadManager.removeDownload(videoId)
                }

                updateProgress(
                    videoId,
                    DownloadProgress(
                        songId = videoId,
                        title = metadata?.title ?: "Unknown Song",
                        progress = 100,
                        bytesDownloaded = size,
                        bytesTotal = size,
                        status = DownloadStatus.COMPLETED
                    )
                )

                scope.launch {
                    delay(2000)
                    removeProgress(videoId)
                }

                Log.d(TAG, "Export and completion successful: $videoId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export cache file for completion: $videoId", e)
                // Clean up partially-written output file to avoid corrupt files on disk
                try {
                    val destMusicDir = File(context.getExternalFilesDir(null), "Music")
                    destMusicDir.listFiles()?.filter { it.nameWithoutExtension == videoId }?.forEach { it.delete() }
                } catch (cleanupError: Exception) {
                    Log.e(TAG, "Failed to clean up partial file for: $videoId", cleanupError)
                }
                updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
            }
        }
    }

    private fun exportCacheToFile(cacheKey: String, outputFile: File) {
        val dataSource = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(null) // Do NOT hit network, must read only from cache blocks
            .createDataSource()

        // Set a valid non-null placeholder URI matching the cacheKey
        val placeholderUri = Uri.parse("https://www.youtube.com/watch?v=$cacheKey")
        val dataSpec = DataSpec.Builder()
            .setUri(placeholderUri)
            .setKey(cacheKey)
            .build()

        try {
            dataSource.open(dataSpec)
            FileOutputStream(outputFile).use { out ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != C.RESULT_END_OF_INPUT) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        } finally {
            dataSource.close()
        }
    }

    private suspend fun downloadAndSaveThumbnail(videoId: String, url: String): File? = withContext(Dispatchers.IO) {
        try {
            if (url.isBlank()) return@withContext null
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(640, 640)
                .allowHardware(false) // Required to export to bitmap
                .build()
            val result = loader.execute(request)
            val drawable = result.drawable
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@withContext null

            val destDir = File(context.getExternalFilesDir(null), "Thumbnails").apply { mkdirs() }
            val destFile = File(destDir, "$videoId.webp")
            FileOutputStream(destFile).use { out ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 95, out)
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading thumbnail for: $videoId", e)
            null
        }
    }

    private fun updateProgress(videoId: String, progress: DownloadProgress) {
        _activeDownloads.value = _activeDownloads.value + (videoId to progress)
    }

    private fun removeProgress(videoId: String) {
        _activeDownloads.value = _activeDownloads.value - videoId
    }

    private fun updateDbAndProgressStatus(videoId: String, status: DownloadStatus) {
        scope.launch {
            downloadedSongDao.updateStatus(videoId, status)
            val current = _activeDownloads.value[videoId]
            if (current != null) {
                updateProgress(videoId, current.copy(status = status))
            }
            if (status == DownloadStatus.FAILED || status == DownloadStatus.COMPLETED) {
                delay(3000)
                removeProgress(videoId)
            }
        }
    }

    private fun applySpeedBypass(streamUrl: String): String {
        return if (streamUrl.contains("range=")) {
            streamUrl
        } else {
            val separator = if (streamUrl.contains("?")) "&" else "?"
            "${streamUrl}${separator}range=0-999999999"
        }
    }
}

/**
 * Progress structure for UI observation.
 */
data class DownloadProgress(
    val songId: String,
    val title: String,
    val progress: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val status: DownloadStatus
)
