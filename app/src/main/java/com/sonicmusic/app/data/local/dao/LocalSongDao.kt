package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.LocalSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSongDao {
    @Query("SELECT * FROM local_songs ORDER BY title ASC")
    fun getAllLocalSongs(): Flow<List<LocalSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<LocalSongEntity>)

    @Query("DELETE FROM local_songs")
    suspend fun deleteAll()

    @Query("SELECT * FROM local_songs WHERE id = :songId")
    suspend fun getLocalSongById(songId: Long): LocalSongEntity?

    @Query("SELECT COUNT(*) FROM local_songs")
    suspend fun getCount(): Int
}