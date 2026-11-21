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
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.deviceLocalSongId
import coil.ImageLoader
import coil.request.ImageRequest
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    media3Provider: Lazy<DownloadManager>,
    private val cacheProvider: Lazy<SimpleCache>,
    private val downloadedSongDao: DownloadedSongDao,
    private val audioStreamExtractor: AudioStreamExtractor
) {
    companion object {
        private const val TAG = "SongDownloadManager"
        private const val PROGRESS_INTERVAL_MS = 750L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    /** One-shot error messages for the UI layer (e.g. storage full, network errors). */
    private val _downloadErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val downloadErrors: SharedFlow<String> = _downloadErrors.asSharedFlow()

    // Track download requests and their metadata in memory to handle completions and titles
    private val downloadMetadata = ConcurrentHashMap<String, DownloadMetadata>()
    private val exporting = ConcurrentHashMap.newKeySet<String>()
    private val preparing = ConcurrentHashMap<String, Any>()
    private val filesMutex = Mutex()
    // Accessed on Media3's application looper.
    private var progressUpdates: Job? = null

    private fun storageDirectory(name: String) = File(context.getExternalFilesDir(null) ?: context.filesDir, name)

    private data class DownloadMetadata(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String,
        val quality: StreamQuality = StreamQuality.BEST
    )

    private val downloadListener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            observeTransferProgress(downloadManager)
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            handleDownloadStateChange(download, finalException)
            if (download.state == Download.STATE_DOWNLOADING) observeTransferProgress(downloadManager)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            Log.d(TAG, "Download removed in Media3: ${download.request.id}")
            removeProgress(download.request.id)
        }

        override fun onIdle(downloadManager: DownloadManager) {
            progressUpdates?.cancel()
            progressUpdates = null
        }
    }

    // Media3 state callbacks do not fire as more bytes arrive. Sample transfers without touching Room.
    private fun observeTransferProgress(manager: DownloadManager) {
        if (progressUpdates?.isActive == true) return
        progressUpdates = scope.launch(Dispatchers.Main) {
            while (true) {
                val transfers = manager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                if (transfers.isEmpty()) break

                _activeDownloads.update { tracked ->
                    val updated = tracked.toMutableMap()
                    for (transfer in transfers) {
                        val id = transfer.request.id
                        val previous = tracked[id] ?: continue
                        if (!previous.isTransferring) continue
                        updated[id] = previous.copy(status = DownloadStatus.DOWNLOADING).withTransferBytes(transfer)
                    }
                    updated
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private val media3 = scope.async(start = CoroutineStart.LAZY) {
        // Resolve the cache directory before constructing the manager on its application looper.
        cacheProvider.get()
        withContext(Dispatchers.Main) {
            media3Provider.get().apply {
                addListener(downloadListener)
                resumeDownloads()
            }
        }
    }

    init {
        // Restore active downloads list from Media3 index on startup and clean up stale REMOVED records
        scope.launch {
            try {
                val manager = media3.await()
                delay(1000) // Allow the Media3 index to settle.
                // Clean up stale REMOVED records
                downloadedSongDao.deleteByStatus(DownloadStatus.REMOVED)
                Log.d(TAG, "Cleaned up stale REMOVED download records from DB")

                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        val videoId = download.request.id
                        val entity = downloadedSongDao.getDownloadedSong(videoId)
                        if (entity != null) {
                            downloadMetadata[videoId] = DownloadMetadata(
                                videoId = videoId,
                                title = entity.title,
                                artist = entity.artists,
                                thumbnailUrl = entity.thumbnailUrl
                            )
                            if (download.state == Download.STATE_COMPLETED) {
                                scope.launch { exportCacheAndComplete(download) }
                            } else if (download.state == Download.STATE_FAILED || download.state == Download.STATE_RESTARTING) {
                                // Expired URL fallback on startup retry
                                refreshDownload(download.request, retry = true)
                            } else {
                                updateProgressFromDownload(download)
                            }
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
        val videoId = song.id
        val preparation = Any()
        if (song.id.deviceLocalSongId() != null || preparing.putIfAbsent(videoId, preparation) != null) return
        scope.launch {
            try {
                val manager = media3.await()
                val previous = downloadedSongDao.getDownloadedSong(videoId)
                if (offlineFile(previous) != null) return@launch
                val indexed = manager.downloadIndex.getDownload(videoId)
                if (indexed?.state == Download.STATE_COMPLETED && previous != null) {
                    exportCacheAndComplete(indexed)
                    return@launch
                }
                if (indexed != null && indexed.state != Download.STATE_FAILED) return@launch

                val musicDir = storageDirectory("Music").apply { mkdirs() }
                if (musicDir.usableSpace < 104_857_600L) {
                    _downloadErrors.tryEmit("Storage space too low for downloads")
                    return@launch
                }

                val initial = DownloadedSong(
                    videoId = videoId,
                    title = song.title,
                    artists = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    localFilePath = "",
                    localThumbnailPath = "",
                    fileSizeBytes = 0L,
                    durationMs = song.duration * 1000L,
                    downloadedAt = System.currentTimeMillis(),
                    status = DownloadStatus.PENDING
                )
                filesMutex.withLock {
                    if (preparing[videoId] !== preparation || offlineFile(downloadedSongDao.getDownloadedSong(videoId)) != null) return@launch
                    downloadedSongDao.insertDownloadedSong(initial)
                    downloadMetadata[videoId] = DownloadMetadata(videoId, song.title, song.artist, song.thumbnailUrl, quality)
                    updateProgress(videoId, DownloadProgress(videoId, song.title, 0, 0, 0, DownloadStatus.PENDING))
                }

                val (candidate, _) = audioStreamExtractor.extractBestCandidate(videoId, quality).getOrThrow()
                filesMutex.withLock {
                    if (preparing[videoId] !== preparation || downloadedSongDao.getDownloadedSong(videoId) != initial) return@launch
                    downloadedSongDao.updateIncompleteStatus(videoId, DownloadStatus.QUEUED)
                    val request = createRequest(videoId, candidate, quality)
                    withContext(Dispatchers.Main) {
                        manager.addDownload(request)
                        manager.resumeDownloads()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not start download for $videoId", e)
                if (preparing[videoId] === preparation) {
                    updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
                    _downloadErrors.tryEmit("Could not download ${song.title}")
                }
            } finally {
                preparing.remove(videoId, preparation)
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
                media3.await().setStopReason(videoId, 1) // Any non-zero stop reason pauses
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
            val previous = media3.await().downloadIndex.getDownload(videoId)?.request
            if (previous == null) {
                val song = downloadedSongDao.getDownloadedSong(videoId) ?: return@launch
                val quality = downloadMetadata[videoId]?.quality ?: StreamQuality.BEST
                downloadSong(Song(videoId, song.title, song.artists, duration = 0, thumbnailUrl = song.thumbnailUrl), quality)
                return@launch
            }
            refreshDownload(previous, retry = false)
        }
    }

    /**
     * Cancel and remove download.
     */
    fun cancelDownload(videoId: String) {
        scope.launch {
            Log.d(TAG, "Cancelling download: $videoId")
            deleteDownload(videoId)
        }
    }

    /**
     * Check if a song is downloaded.
     */
    suspend fun isDownloaded(songId: String): Boolean = getDownloadedFileSize(songId) != null

    fun observeDownloadedFileSize(songId: String) = downloadedSongDao.observeDownloadedSong(songId)
        .map { offlineFile(it)?.length() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun offlineFile(record: DownloadedSong?): File? {
        if (record?.status != DownloadStatus.COMPLETED) return null
        val stored = record.localFilePath.takeIf(String::isNotBlank)?.let(::File)
        if (stored != null && stored.isFile && stored.length() > 0) return stored
        return listOf("opus", "m4a", "webm", "aac", "mp3", "wav").asSequence()
            .map { File(storageDirectory("Music"), "${record.videoId}.$it") }
            .firstOrNull { it.isFile && it.length() > 0 }
    }

    /**
     * Expose downloads flow.
     */
    fun getDownloadedSongs() = downloadedSongDao.getAllDownloadedSongs()

    /**
     * Delete downloaded files and DB records.
     */
    suspend fun deleteDownload(videoId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Deleting download: $videoId")
            val manager = media3.await()
            filesMutex.withLock {
                preparing.remove(videoId)
                val entity = downloadedSongDao.getDownloadedSong(videoId)
                offlineFile(entity)?.delete()
                entity?.localThumbnailPath?.takeIf(String::isNotBlank)?.let { File(it).delete() }
                downloadedSongDao.deleteBySongId(videoId)
                downloadMetadata.remove(videoId)
                removeProgress(videoId)
                withContext(Dispatchers.Main) { manager.removeDownload(videoId) }
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
        val file = offlineFile(downloadedSongDao.getDownloadedSong(videoId))
        if (file == null) Result.failure(IOException("Downloaded audio is unavailable")) else Result.success(file)
    }

    suspend fun getDownloadedFileSize(videoId: String): Long? = withContext(Dispatchers.IO) {
        offlineFile(downloadedSongDao.getDownloadedSong(videoId))?.length()
    }

    /**
     * Clean all downloads.
     */
    suspend fun clearAllDownloads(): Boolean = withContext(Dispatchers.IO) {
        try {
            val manager = media3.await()
            filesMutex.withLock {
                preparing.clear()
                storageDirectory("Music").listFiles()?.forEach { it.delete() }
                storageDirectory("Thumbnails").listFiles()?.forEach { it.delete() }
                downloadedSongDao.deleteAll()
                downloadMetadata.clear()
                _activeDownloads.value = emptyMap()
                withContext(Dispatchers.Main) { manager.removeAllDownloads() }
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
            if (dbSong == null || state == Download.STATE_REMOVING) return@launch
            // If the song is already marked COMPLETED in the database, ignore subsequent removal/transition states
            if (dbSong.status == DownloadStatus.COMPLETED && state != Download.STATE_COMPLETED) {
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
                    refreshDownload(download.request, retry = true)
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
        val active = DownloadProgress(
            songId = videoId,
            title = title,
            progress = 0,
            bytesDownloaded = 0,
            bytesTotal = -1,
            status = status
        ).withTransferBytes(download)

        updateProgress(videoId, active)
        scope.launch {
            val dbSong = downloadedSongDao.getDownloadedSong(videoId)
            if (dbSong?.status != DownloadStatus.COMPLETED) {
                downloadedSongDao.updateStatusAndSize(videoId, status, download.contentLength)
            }
        }
    }

    private fun DownloadProgress.withTransferBytes(download: Download): DownloadProgress = copy(
        progress = download.percentDownloaded.toInt().coerceIn(0, 100),
        bytesDownloaded = download.bytesDownloaded,
        bytesTotal = download.contentLength
    )

    private fun createRequest(
        videoId: String,
        candidate: AudioStreamCandidate,
        quality: StreamQuality,
        attempt: Int = 0
    ): DownloadRequest {
        // A changed encoding must never be appended to another format's cached bytes.
        val formatKey = if (candidate.itag != null && candidate.contentLength != null) {
            "${candidate.itag}:${candidate.contentLength}"
        } else {
            java.util.UUID.randomUUID().toString()
        }
        return DownloadRequest.Builder(videoId, Uri.parse(candidate.url))
            .setMimeType(candidate.mimeType.substringBefore(';'))
            .setCustomCacheKey("$videoId:$formatKey")
            .setData("${quality.name}:$attempt".toByteArray())
            .build()
    }

    private suspend fun refreshDownload(previous: DownloadRequest, retry: Boolean) {
        val videoId = previous.id
        val data = previous.data.decodeToString()
        val quality = StreamQuality.fromName(data.substringBefore(':'))
        val previousAttempt = data.substringAfter(':', "0").toIntOrNull() ?: 0
        val attempt = if (retry) previousAttempt + 1 else 0
        if (attempt > 2) {
            updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
            return
        }

        updateDbAndProgressStatus(videoId, DownloadStatus.RETRYING)
        val excluded = if (retry) setOf(previous.uri.toString()) else emptySet()
        val result = audioStreamExtractor.extractBestCandidate(videoId, quality, forceRefresh = true, excludeUrls = excluded)
        val candidate = result.getOrNull()?.first
        if (candidate == null) {
            updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
            return
        }

        val replacement = createRequest(videoId, candidate, quality, attempt)
        val oldCacheKey = previous.customCacheKey ?: videoId
        val media3DownloadManager = media3.await()
        if (media3DownloadManager.downloadIndex.getDownload(videoId)?.request != previous) return
        if (oldCacheKey != replacement.customCacheKey) {
            cacheProvider.get().removeResource(oldCacheKey)
        }
        withContext(Dispatchers.Main) {
            media3DownloadManager.addDownload(replacement, Download.STOP_REASON_NONE)
            media3DownloadManager.resumeDownloads()
        }
    }

    private suspend fun exportCacheAndComplete(download: Download) {
        val videoId = download.request.id
        if (!exporting.add(videoId)) return
        var temporaryFile: File? = null
        try {
            val manager = media3.await()
            val record = downloadedSongDao.getDownloadedSong(videoId) ?: return
            if (offlineFile(record) == null) {
                val expectedBytes = download.contentLength.takeIf { it > 0 } ?: download.bytesDownloaded
                val cacheKey = download.request.customCacheKey ?: videoId
                if (expectedBytes <= 0 || !cacheProvider.get().isCached(cacheKey, 0, expectedBytes)) {
                    filesMutex.withLock {
                        val current = downloadedSongDao.getDownloadedSong(videoId)
                        if (current?.downloadedAt != record.downloadedAt || offlineFile(current) != null) return
                        downloadedSongDao.updateStatus(videoId, DownloadStatus.FAILED)
                        withContext(Dispatchers.Main) { manager.removeDownload(videoId) }
                    }
                    removeProgress(videoId)
                    _downloadErrors.tryEmit("Offline audio is incomplete. Download it again.")
                    return
                }
                val extension = when (download.request.mimeType) {
                    "audio/mp4" -> "m4a"
                    "audio/aac" -> "aac"
                    "audio/mpeg" -> "mp3"
                    "audio/wav", "audio/x-wav" -> "wav"
                    else -> "opus"
                }
                val musicDir = storageDirectory("Music").apply { mkdirs() }
                val output = File(musicDir, "$videoId.$extension")
                val staging = File.createTempFile("offline-", ".part", musicDir)
                temporaryFile = staging
                exportCacheToFile(cacheKey, staging, expectedBytes)
                check(staging.length() == expectedBytes) { "The offline audio write is incomplete." }

                filesMutex.withLock {
                    val current = downloadedSongDao.getDownloadedSong(videoId)
                    if (current?.downloadedAt != record.downloadedAt) return
                    check(staging.renameTo(output)) { "Could not save the downloaded audio." }
                    downloadedSongDao.markCompleted(videoId, output.absolutePath, expectedBytes)
                }
                if (record.localThumbnailPath.isBlank() && record.thumbnailUrl.isNotBlank()) {
                    scope.launch {
                        val artwork = downloadAndSaveThumbnail("$videoId-${record.downloadedAt}", record.thumbnailUrl)
                            ?: return@launch
                        filesMutex.withLock {
                            val current = downloadedSongDao.getDownloadedSong(videoId)
                            if (current?.downloadedAt == record.downloadedAt && current.status == DownloadStatus.COMPLETED) {
                                downloadedSongDao.updateLocalThumbnail(videoId, artwork.absolutePath)
                            } else {
                                artwork.delete()
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { manager.removeDownload(videoId) }
            removeProgress(videoId)
            downloadMetadata.remove(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not finalize offline audio for $videoId", e)
            updateDbAndProgressStatus(videoId, DownloadStatus.FAILED)
            _downloadErrors.tryEmit("Could not save the downloaded song")
        } finally {
            temporaryFile?.delete()
            exporting.remove(videoId)
        }
    }

    private fun exportCacheToFile(cacheKey: String, outputFile: File, length: Long) {
        val dataSource = CacheDataSource.Factory()
            .setCache(cacheProvider.get())
            .setUpstreamDataSourceFactory(null) // Do NOT hit network, must read only from cache blocks
            .createDataSource()

        // Set a valid non-null placeholder URI matching the cacheKey
        val placeholderUri = Uri.parse("https://www.youtube.com/watch?v=$cacheKey")
        val dataSpec = DataSpec.Builder()
            .setUri(placeholderUri)
            .setKey(cacheKey)
            .setLength(length)
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

            val destDir = storageDirectory("Thumbnails").apply { mkdirs() }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading thumbnail for: $videoId", e)
            null
        }
    }

    private fun updateProgress(videoId: String, progress: DownloadProgress) {
        _activeDownloads.update { it + (videoId to progress) }
    }

    private fun removeProgress(videoId: String) {
        _activeDownloads.update { it - videoId }
    }

    private fun updateDbAndProgressStatus(videoId: String, status: DownloadStatus) {
        scope.launch {
            downloadedSongDao.updateIncompleteStatus(videoId, status)
            val current = _activeDownloads.value[videoId]
            if (current != null) {
                updateProgress(videoId, current.copy(status = status))
            }
            if (status == DownloadStatus.FAILED) {
                delay(3000)
                _activeDownloads.update { downloads ->
                    if (downloads[videoId]?.status == DownloadStatus.FAILED) downloads - videoId else downloads
                }
            }
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
) {
    val isTransferring: Boolean
        get() = when (status) {
            DownloadStatus.PENDING, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.RETRYING -> true
            else -> false
        }
}
