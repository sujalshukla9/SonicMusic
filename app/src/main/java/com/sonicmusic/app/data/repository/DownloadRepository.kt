package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.downloadmanager.SongDownloadManager
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.entity.DownloadedSong
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing offline downloads, orchestrating DAO access,
 * and performing offline metadata searches.
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val songDownloadManager: SongDownloadManager,
    private val downloadedSongDao: DownloadedSongDao
) {
    val activeDownloads = songDownloadManager.activeDownloads

    /** One-shot error messages from the download manager (e.g. "Storage space too low"). */
    val downloadErrors = songDownloadManager.downloadErrors

    fun downloadSong(song: Song, quality: StreamQuality = StreamQuality.BEST) {
        songDownloadManager.downloadSong(song, quality)
    }

    fun pauseDownload(videoId: String) {
        songDownloadManager.pauseDownload(videoId)
    }

    fun resumeDownload(videoId: String) {
        songDownloadManager.resumeDownload(videoId)
    }

    fun cancelDownload(videoId: String) {
        songDownloadManager.cancelDownload(videoId)
    }

    suspend fun isDownloaded(songId: String): Boolean {
        return songDownloadManager.isDownloaded(songId)
    }

    fun getDownloadedSongs(): Flow<List<DownloadedSong>> {
        return songDownloadManager.getDownloadedSongs()
    }

    suspend fun deleteDownload(videoId: String): Boolean {
        return songDownloadManager.deleteDownload(videoId)
    }

    suspend fun clearAllDownloads(): Boolean {
        return songDownloadManager.clearAllDownloads()
    }

    suspend fun searchDownloadedSongs(query: String): List<DownloadedSong> {
        return downloadedSongDao.searchDownloadedSongs(query)
    }
}
