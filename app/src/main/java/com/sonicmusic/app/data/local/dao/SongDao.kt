package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sonicmusic.app.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun getLikedSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<SongEntity>)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("UPDATE songs SET isLiked = :liked, likedAt = :timestamp WHERE id = :songId")
    suspend fun updateLikeStatus(songId: String, liked: Boolean, timestamp: Long?)

    @Query("SELECT isLiked FROM songs WHERE id = :songId")
    suspend fun isLiked(songId: String): Boolean?

    @Query("SELECT isLiked FROM songs WHERE id = :songId")
    fun observeIsLiked(songId: String): kotlinx.coroutines.flow.Flow<Boolean?>

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)
}