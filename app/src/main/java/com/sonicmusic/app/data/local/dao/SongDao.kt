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
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSong(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun getLikedSongs(): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("UPDATE songs SET isLiked = :isLiked, likedAt = :timestamp WHERE id = :songId")
    suspend fun updateLikeStatus(songId: String, isLiked: Boolean, timestamp: Long?)

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>
}
