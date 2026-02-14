package com.sonicmusic.app.data.downloadmanager

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.entity.DownloadedSongEntity
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Download Manager for offline song downloads with encryption
 * 
 * Manages downloading songs for offline playback with AES-256-GCM encryption.
 * 
 * Features:
 * - Downloads audio streams directly (not via Android DownloadManager)
 * - Encrypts files using EncryptionService
 * - Tracks downloads in Room database
 * - Provides decrypted streams for playback
 */
@Singleton
class SongDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioStreamExtractor: AudioStreamExtractor,
    private val encryptionService: EncryptionService,
    private val downloadedSongDao: DownloadedSongDao,
    private val okHttpClient: OkHttpClient,
    private val notificationHelper: DownloadNotificationHelper
) {
    companion object {
        private const val TAG = "SongDownloadManager"
        private const val DOWNLOADS_DIRECTORY = "downloads"
        private const val TEMP_DIRECTORY = "temp"
        private const val IO_BUFFER_SIZE_BYTES = 64 * 1024
        private const val UI_PROGRESS_STEP_PERCENT = 1
        private const val UI_PROGRESS_UPDATE_INTERVAL_MS = 250L
        private const val NOTIFICATION_PROGRESS_STEP_PERCENT = 10
        private const val MAX_PARALLEL_SEGMENTS = 4
        private const val MIN_PARALLEL_DOWNLOAD_SIZE_BYTES = 3L * 1024 * 1024
        private const val MIN_SEGMENT_SIZE_BYTES = 1L * 1024 * 1024
    }

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val downloadsDir: File by lazy {
        File(context.filesDir, DOWNLOADS_DIRECTORY).apply { mkdirs() }
    }

    private val tempDir: File by lazy {
        File(context.cacheDir, TEMP_DIRECTORY).apply { mkdirs() }
    }

    private data class DownloadProbe(
        val supportsRanges: Boolean,
        val totalBytes: Long
    )

    private data class ByteSegment(
        val start: Long,
        val endInclusive: Long
    )

    /**
     * Download a song with encryption.
     * 
     * @param song The song to download
     * @param quality The stream quality to download
     */
    suspend fun downloadSong(song: Song, quality: StreamQuality = StreamQuality.HIGH): Result<File> = 
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📥 Starting download: ${song.title}")

                // Check if already downloaded
                val existing = downloadedSongDao.getDownloadedSong(song.id)
                if (existing != null && File(existing.filePath).exists()) {
                    Log.d(TAG, "✅ Already downloaded: ${song.title}")
                    return@withContext Result.success(File(existing.filePath))
                }

                notificationHelper.showIndeterminate(song.id, song.title)

                // Update progress
                updateProgress(song.id, song.title, DownloadProgress(
                    songId = song.id,
                    title = song.title,
                    progress = 0,
                    bytesDownloaded = 0,
                    bytesTotal = 0,
                    status = DownloadStatus.PENDING
                ))

                // Get stream URL
                val streamResult = audioStreamExtractor.extractAudioStreamUrl(song.id, quality)
                
                if (streamResult.isFailure) {
                    val error = streamResult.exceptionOrNull()
                    notificationHelper.showError(song.id, song.title, error?.message)
                    updateProgress(song.id, song.title, DownloadProgress(
                        songId = song.id,
                        title = song.title,
                        progress = 0,
                        bytesDownloaded = 0,
                        bytesTotal = 0,
                        status = DownloadStatus.FAILED
                    ))
                    return@withContext Result.failure(
                        error ?: Exception("Failed to get stream URL")
                    )
                }

                val streamUrl = streamResult.getOrThrow()
                Log.d(TAG, "📡 Got stream URL, starting download")

                // Download to temp file
                val tempFile = File(tempDir, "${song.id}.m4a")
                val downloadResult = downloadFile(streamUrl, tempFile, song.id, song.title)
                
                if (downloadResult.isFailure) {
                    val error = downloadResult.exceptionOrNull()
                    notificationHelper.showError(song.id, song.title, error?.message)
                    updateProgress(song.id, song.title, DownloadProgress(
                        songId = song.id,
                        title = song.title,
                        progress = 0,
                        bytesDownloaded = 0,
                        bytesTotal = 0,
                        status = DownloadStatus.FAILED
                    ))
                    return@withContext downloadResult
                }

                Log.d(TAG, "🔒 Encrypting file")
                notificationHelper.showProgress(song.id, song.title, 99)
                updateProgress(song.id, song.title, DownloadProgress(
                    songId = song.id,
                    title = song.title,
                    progress = 99,
                    bytesDownloaded = tempFile.length().toInt(),
                    bytesTotal = tempFile.length().toInt(),
                    status = DownloadStatus.DOWNLOADING
                ))

                // Encrypt the file
                val encryptedFile = File(downloadsDir, "${song.id}.enc")
                val encryptResult = encryptionService.encryptFile(tempFile, encryptedFile)
                
                // Clean up temp file
                tempFile.delete()

                if (encryptResult.isFailure) {
                    val error = encryptResult.exceptionOrNull()
                    notificationHelper.showError(song.id, song.title, "Encryption failed")
                    updateProgress(song.id, song.title, DownloadProgress(
                        songId = song.id,
                        title = song.title,
                        progress = 0,
                        bytesDownloaded = 0,
                        bytesTotal = 0,
                        status = DownloadStatus.FAILED
                    ))
                    return@withContext Result.failure(
                        error ?: Exception("Encryption failed")
                    )
                }

                // Save to database
                val entity = DownloadedSongEntity(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    filePath = encryptedFile.absolutePath,
                    fileSize = encryptedFile.length(),
                    quality = quality.name,
                    downloadedAt = System.currentTimeMillis(),
                    thumbnailUrl = song.thumbnailUrl,
                    isEncrypted = true
                )
                downloadedSongDao.insertDownloadedSong(entity)

                // Update progress to complete
                notificationHelper.showComplete(song.id, song.title)
                updateProgress(song.id, song.title, DownloadProgress(
                    songId = song.id,
                    title = song.title,
                    progress = 100,
                    bytesDownloaded = encryptedFile.length().toInt(),
                    bytesTotal = encryptedFile.length().toInt(),
                    status = DownloadStatus.COMPLETED
                ))

                Log.d(TAG, "✅ Download complete: ${song.title}")
                Result.success(encryptedFile)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Download failed: ${song.title}", e)
                notificationHelper.showError(song.id, song.title, e.message)
                updateProgress(song.id, song.title, DownloadProgress(
                    songId = song.id,
                    title = song.title,
                    progress = 0,
                    bytesDownloaded = 0,
                    bytesTotal = 0,
                    status = DownloadStatus.FAILED
                ))
                Result.failure(e)
            }
        }

    /**
     * Download a file from URL with progress tracking.
     */
    private suspend fun downloadFile(url: String, outputFile: File, songId: String, title: String): Result<File> = 
        withContext(Dispatchers.IO) {
            try {
                val probe = probeDownload(url)
                val canUseParallel = probe.supportsRanges && probe.totalBytes >= MIN_PARALLEL_DOWNLOAD_SIZE_BYTES

                if (canUseParallel) {
                    val parallelResult = downloadFileInParallel(
                        url = url,
                        outputFile = outputFile,
                        songId = songId,
                        title = title,
                        contentLength = probe.totalBytes
                    )
                    if (parallelResult.isSuccess) {
                        return@withContext parallelResult
                    }

                    Log.w(TAG, "Parallel download failed, falling back to single stream", parallelResult.exceptionOrNull())
                    outputFile.delete()
                }

                downloadFileSequential(url, outputFile, songId, title)
            } catch (e: Exception) {
                outputFile.delete()
                Result.failure(e)
            }
        }

    private fun probeDownload(url: String): DownloadProbe {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-0")
                .addHeader("Accept-Encoding", "identity")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val contentRange = response.header("Content-Range")
                val totalBytes = contentRange
                    ?.substringAfter('/')
                    ?.takeIf { it.isNotBlank() && it != "*" }
                    ?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: -1L

                val supportsRanges = response.code == 206 &&
                    !contentRange.isNullOrBlank() &&
                    contentRange.contains("bytes", ignoreCase = true)

                DownloadProbe(
                    supportsRanges = supportsRanges,
                    totalBytes = totalBytes.coerceAtLeast(-1L)
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Range probe failed, using single-stream download", e)
            DownloadProbe(supportsRanges = false, totalBytes = -1L)
        }
    }

    private suspend fun downloadFileSequential(
        url: String,
        outputFile: File,
        songId: String,
        title: String
    ): Result<File> {
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code}"))
                }

                val body = response.body ?: return Result.failure(Exception("Empty response"))
                val contentLength = body.contentLength().coerceAtLeast(0L)
                val bytesTotal = contentLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                var lastUiProgress = -1
                var lastNotificationProgress = -1
                var lastUiUpdateTimeMs = SystemClock.elapsedRealtime()
                var totalBytesRead = 0L

                body.byteStream().use { inputStream ->
                    FileOutputStream(outputFile).buffered(IO_BUFFER_SIZE_BYTES).use { outputStream ->
                        val buffer = ByteArray(IO_BUFFER_SIZE_BYTES)
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            val rawProgress = if (contentLength > 0L) {
                                ((totalBytesRead * 100) / contentLength).toInt()
                            } else {
                                0
                            }
                            val progress = rawProgress.coerceIn(0, 98) // Reserve 99-100 for encryption

                            val nowMs = SystemClock.elapsedRealtime()
                            val shouldUpdateUi = progress != lastUiProgress && (
                                lastUiProgress < 0 ||
                                    (progress - lastUiProgress) >= UI_PROGRESS_STEP_PERCENT ||
                                    (nowMs - lastUiUpdateTimeMs) >= UI_PROGRESS_UPDATE_INTERVAL_MS ||
                                    progress >= 98
                                )

                            if (shouldUpdateUi) {
                                updateProgress(songId, title, DownloadProgress(
                                    songId = songId,
                                    title = title,
                                    progress = progress,
                                    bytesDownloaded = totalBytesRead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    bytesTotal = bytesTotal,
                                    status = DownloadStatus.DOWNLOADING
                                ))
                                lastUiProgress = progress
                                lastUiUpdateTimeMs = nowMs
                            }

                            val shouldNotify = progress != lastNotificationProgress && (
                                lastNotificationProgress < 0 ||
                                    (progress - lastNotificationProgress) >= NOTIFICATION_PROGRESS_STEP_PERCENT ||
                                    progress >= 98
                                )
                            if (shouldNotify) {
                                notificationHelper.showProgress(songId, title, progress)
                                lastNotificationProgress = progress
                            }
                        }

                        outputStream.flush()
                    }
                }

                val finalDownloaded = totalBytesRead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (lastUiProgress < 98) {
                    updateProgress(songId, title, DownloadProgress(
                        songId = songId,
                        title = title,
                        progress = 98,
                        bytesDownloaded = finalDownloaded,
                        bytesTotal = bytesTotal,
                        status = DownloadStatus.DOWNLOADING
                    ))
                }
                if (lastNotificationProgress < 98) {
                    notificationHelper.showProgress(songId, title, 98)
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        }
    }

    private suspend fun downloadFileInParallel(
        url: String,
        outputFile: File,
        songId: String,
        title: String,
        contentLength: Long
    ): Result<File> {
        if (contentLength <= 0L) {
            return Result.failure(Exception("Invalid content length for parallel download"))
        }

        return try {
            val segmentCount = calculateSegmentCount(contentLength)
            val segments = buildSegments(contentLength, segmentCount)
            val bytesTotal = contentLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.setLength(contentLength)
            }

            val totalBytesRead = AtomicLong(0L)
            val progressLock = Any()
            var lastUiProgress = -1
            var lastNotificationProgress = -1
            var lastUiUpdateTimeMs = SystemClock.elapsedRealtime()

            coroutineScope {
                segments.map { segment ->
                    async(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Range", "bytes=${segment.start}-${segment.endInclusive}")
                            .addHeader("Accept-Encoding", "identity")
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.code != 206) {
                                throw IOException("Range not honored for segment ${segment.start}-${segment.endInclusive}, code=${response.code}")
                            }

                            val body = response.body ?: throw IOException("Empty segment response")
                            val expectedBytes = segment.endInclusive - segment.start + 1
                            var segmentBytesRead = 0L

                            body.byteStream().use { inputStream ->
                                RandomAccessFile(outputFile, "rw").use { raf ->
                                    raf.seek(segment.start)
                                    val buffer = ByteArray(IO_BUFFER_SIZE_BYTES)
                                    var bytesRead: Int

                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        raf.write(buffer, 0, bytesRead)
                                        segmentBytesRead += bytesRead
                                        val downloaded = totalBytesRead.addAndGet(bytesRead.toLong())
                                        val rawProgress = ((downloaded * 100) / contentLength).toInt()
                                        val progress = rawProgress.coerceIn(0, 98)
                                        val nowMs = SystemClock.elapsedRealtime()

                                        synchronized(progressLock) {
                                            val shouldUpdateUi = progress != lastUiProgress && (
                                                lastUiProgress < 0 ||
                                                    (progress - lastUiProgress) >= UI_PROGRESS_STEP_PERCENT ||
                                                    (nowMs - lastUiUpdateTimeMs) >= UI_PROGRESS_UPDATE_INTERVAL_MS ||
                                                    progress >= 98
                                                )
                                            if (shouldUpdateUi) {
                                                updateProgress(songId, title, DownloadProgress(
                                                    songId = songId,
                                                    title = title,
                                                    progress = progress,
                                                    bytesDownloaded = downloaded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                                    bytesTotal = bytesTotal,
                                                    status = DownloadStatus.DOWNLOADING
                                                ))
                                                lastUiProgress = progress
                                                lastUiUpdateTimeMs = nowMs
                                            }

                                            val shouldNotify = progress != lastNotificationProgress && (
                                                lastNotificationProgress < 0 ||
                                                    (progress - lastNotificationProgress) >= NOTIFICATION_PROGRESS_STEP_PERCENT ||
                                                    progress >= 98
                                                )
                                            if (shouldNotify) {
                                                notificationHelper.showProgress(songId, title, progress)
                                                lastNotificationProgress = progress
                                            }
                                        }
                                    }
                                }
                            }

                            if (segmentBytesRead != expectedBytes) {
                                throw IOException(
                                    "Incomplete segment ${segment.start}-${segment.endInclusive}: expected=$expectedBytes actual=$segmentBytesRead"
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            val finalDownloaded = totalBytesRead.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (lastUiProgress < 98) {
                updateProgress(songId, title, DownloadProgress(
                    songId = songId,
                    title = title,
                    progress = 98,
                    bytesDownloaded = finalDownloaded,
                    bytesTotal = bytesTotal,
                    status = DownloadStatus.DOWNLOADING
                ))
            }
            if (lastNotificationProgress < 98) {
                notificationHelper.showProgress(songId, title, 98)
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        }
    }

    private fun calculateSegmentCount(contentLength: Long): Int {
        val bySize = (contentLength / MIN_SEGMENT_SIZE_BYTES).toInt().coerceAtLeast(1)
        return min(MAX_PARALLEL_SEGMENTS, max(2, bySize))
    }

    private fun buildSegments(contentLength: Long, segmentCount: Int): List<ByteSegment> {
        val chunkSize = contentLength / segmentCount
        var start = 0L
        val segments = ArrayList<ByteSegment>(segmentCount)

        for (index in 0 until segmentCount) {
            val end = if (index == segmentCount - 1) {
                contentLength - 1
            } else {
                (start + chunkSize - 1).coerceAtLeast(start)
            }
            segments.add(ByteSegment(start = start, endInclusive = end))
            start = end + 1
        }

        return segments
    }

    /**
     * Get a decrypted stream for playback.
     * Creates a temporary decrypted file that should be cleaned up after playback.
     * 
     * @param songId The song ID to get playback file for
     * @return Result with the temporary decrypted file path
     */
    suspend fun getPlaybackFile(songId: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val entity = downloadedSongDao.getDownloadedSong(songId)
                ?: return@withContext Result.failure(Exception("Song not downloaded"))

            val encryptedFile = File(entity.filePath)
            if (!encryptedFile.exists()) {
                // Clean up orphaned database entry
                downloadedSongDao.deleteBySongId(songId)
                return@withContext Result.failure(Exception("Downloaded file not found"))
            }

            if (!entity.isEncrypted) {
                // File is not encrypted (legacy download)
                return@withContext Result.success(encryptedFile)
            }

            // Decrypt to temp file for playback
            val decryptedFile = File(tempDir, "playback_${songId}.m4a")
            
            // Reuse existing decrypted file if it exists
            if (decryptedFile.exists()) {
                return@withContext Result.success(decryptedFile)
            }

            val decryptResult = encryptionService.decryptFile(encryptedFile, decryptedFile)
            
            if (decryptResult.isFailure) {
                return@withContext Result.failure(
                    decryptResult.exceptionOrNull() ?: Exception("Decryption failed")
                )
            }

            Result.success(decryptedFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clean up temporary playback files.
     */
    fun cleanupPlaybackFiles() {
        tempDir.listFiles()?.filter { it.name.startsWith("playback_") }?.forEach { it.delete() }
    }

    /**
     * Check if a song is downloaded.
     */
    suspend fun isDownloaded(songId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = downloadedSongDao.getDownloadedSong(songId)
        entity != null && File(entity.filePath).exists()
    }

    /**
     * Get all downloaded songs as a Flow.
     */
    fun getDownloadedSongs(): Flow<List<DownloadedSongEntity>> {
        return downloadedSongDao.getAllDownloadedSongs()
    }

    /**
     * Delete a downloaded song.
     */
    suspend fun deleteDownload(songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entity = downloadedSongDao.getDownloadedSong(songId) ?: return@withContext false
            File(entity.filePath).delete()
            downloadedSongDao.deleteBySongId(songId)
            
            // Also clean up any playback file
            File(tempDir, "playback_${songId}.m4a").delete()
            
            removeProgress(songId)
            notificationHelper.cancel(songId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting download", e)
            false
        }
    }

    /**
     * Get total size of all downloads.
     */
    suspend fun getTotalDownloadSize(): Long = withContext(Dispatchers.IO) {
        downloadedSongDao.getTotalDownloadSize() ?: 0L
    }

    /**
     * Get download count.
     */
    suspend fun getDownloadCount(): Int = withContext(Dispatchers.IO) {
        downloadedSongDao.getDownloadCount()
    }

    /**
     * Clear all downloads.
     */
    suspend fun clearAllDownloads(): Boolean = withContext(Dispatchers.IO) {
        try {
            downloadsDir.listFiles()?.forEach { it.delete() }
            tempDir.listFiles()?.forEach { it.delete() }
            downloadedSongDao.deleteAll()
            _activeDownloads.value = emptyMap()
            notificationHelper.cancel(songId = "") // might need to cancel all?
            // NotificationManager.cancelAll() is not exposed in helper, but maybe we should add it if needed
            // For now, individual cancellations
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing downloads", e)
            false
        }
    }

    /**
     * Cancel an active download.
     */
    fun cancelDownload(songId: String) {
        // Note: In a full implementation, we'd track and cancel the coroutine job
        removeProgress(songId)
        notificationHelper.cancel(songId)
        
        // Clean up any partial files
        File(tempDir, "${songId}.m4a").delete()
        File(downloadsDir, "${songId}.enc").delete()
    }

    private fun updateProgress(songId: String, title: String, progress: DownloadProgress) {
        // Ensure the progress object has the correct title
        val current = _activeDownloads.value[songId]
        val newProgress = if (current?.title == title) progress else progress.copy(title = title)
        
        _activeDownloads.value = _activeDownloads.value + (songId to newProgress)
    }

    private fun removeProgress(songId: String) {
        _activeDownloads.value = _activeDownloads.value - songId
    }
}

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
    val songId: String,
    val title: String,
    val progress: Int,
    val bytesDownloaded: Int,
    val bytesTotal: Int,
    val status: DownloadStatus
)
