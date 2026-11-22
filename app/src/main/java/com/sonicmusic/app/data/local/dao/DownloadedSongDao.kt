package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.DownloadedSong
import com.sonicmusic.app.data.local.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongDao {
    @Query("SELECT * FROM downloaded_songs_v2 ORDER BY downloadedAt DESC")
    fun getAllDownloadedSongs(): Flow<List<DownloadedSong>>

    @Query("SELECT videoId FROM downloaded_songs_v2 WHERE status = 'COMPLETED'")
    fun observeCompletedSongIds(): Flow<List<String>>

    @Query("SELECT * FROM downloaded_songs_v2 WHERE videoId = :videoId")
    suspend fun getDownloadedSong(videoId: String): DownloadedSong?

    @Query("SELECT * FROM downloaded_songs_v2 WHERE videoId = :videoId")
    fun observeDownloadedSong(videoId: String): Flow<DownloadedSong?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedSong(song: DownloadedSong)

    @Delete
    suspend fun deleteDownloadedSong(song: DownloadedSong)

    @Query("DELETE FROM downloaded_songs_v2 WHERE videoId = :videoId")
    suspend fun deleteBySongId(videoId: String)

    @Query("SELECT SUM(fileSizeBytes) FROM downloaded_songs_v2 WHERE status = 'COMPLETED'")
    suspend fun getTotalDownloadSize(): Long?

    @Query("SELECT COUNT(*) FROM downloaded_songs_v2 WHERE status = 'COMPLETED'")
    suspend fun getDownloadCount(): Int

    @Query("DELETE FROM downloaded_songs_v2")
    suspend fun deleteAll()

    @Query("SELECT * FROM downloaded_songs_v2 WHERE status = 'COMPLETED' AND (title LIKE :query OR artists LIKE :query)")
    suspend fun searchDownloadedSongs(query: String): List<DownloadedSong>

    @Query("UPDATE downloaded_songs_v2 SET status = :status WHERE videoId = :videoId")
    suspend fun updateStatus(videoId: String, status: DownloadStatus)

    @Query("UPDATE downloaded_songs_v2 SET status = :status, fileSizeBytes = :fileSizeBytes WHERE videoId = :videoId AND status != 'COMPLETED'")
    suspend fun updateStatusAndSize(videoId: String, status: DownloadStatus, fileSizeBytes: Long)

    @Query("UPDATE downloaded_songs_v2 SET status = :status WHERE videoId = :videoId AND status != 'COMPLETED'")
    suspend fun updateIncompleteStatus(videoId: String, status: DownloadStatus)

    @Query("UPDATE downloaded_songs_v2 SET localFilePath = :filePath, fileSizeBytes = :size, status = 'COMPLETED' WHERE videoId = :videoId")
    suspend fun markCompleted(videoId: String, filePath: String, size: Long): Int

    @Query("UPDATE downloaded_songs_v2 SET localThumbnailPath = :path WHERE videoId = :videoId")
    suspend fun updateLocalThumbnail(videoId: String, path: String)

    @Query("DELETE FROM downloaded_songs_v2 WHERE status = :status")
    suspend fun deleteByStatus(status: DownloadStatus)
}
