package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.DownloadedSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedSongDao {
    @Query("SELECT * FROM downloaded_songs ORDER BY downloadedAt DESC")
    fun getAllDownloadedSongs(): Flow<List<DownloadedSongEntity>>

    @Query("SELECT * FROM downloaded_songs WHERE songId = :songId")
    suspend fun getDownloadedSong(songId: String): DownloadedSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedSong(song: DownloadedSongEntity)

    @Delete
    suspend fun deleteDownloadedSong(song: DownloadedSongEntity)

    @Query("DELETE FROM downloaded_songs WHERE songId = :songId")
    suspend fun deleteBySongId(songId: String)

    @Query("SELECT SUM(fileSize) FROM downloaded_songs")
    suspend fun getTotalDownloadSize(): Long?

    @Query("SELECT COUNT(*) FROM downloaded_songs")
    suspend fun getDownloadCount(): Int

    @Query("DELETE FROM downloaded_songs")
    suspend fun deleteAll()
}