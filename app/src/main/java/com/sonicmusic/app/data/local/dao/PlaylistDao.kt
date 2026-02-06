package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef
import com.sonicmusic.app.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Transaction
    @Query("""
        SELECT * FROM songs 
        INNER JOIN playlist_songs ON songs.id = playlist_songs.songId 
        WHERE playlist_songs.playlistId = :playlistId 
        ORDER BY playlist_songs.position ASC
    """)
    fun getPlaylistSongs(playlistId: Long): Flow<List<SongEntity>>
}
