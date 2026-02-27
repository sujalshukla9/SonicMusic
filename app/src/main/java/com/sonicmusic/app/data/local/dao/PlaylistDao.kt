package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("""
        SELECT p.*, COUNT(ps.songId) as songCount 
        FROM playlists p 
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId 
        GROUP BY p.id 
        ORDER BY p.updatedAt DESC
    """)
    fun getPlaylistsWithSongCount(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId LIMIT 1)")
    suspend fun hasSongInPlaylist(playlistId: Long, songId: String): Boolean

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistSongs(playlistId: Long): List<PlaylistSongCrossRef>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getSongCount(playlistId: Long): Int

    @Transaction
    suspend fun updatePlaylistOrder(playlistId: Long, songIds: List<String>) {
        clearPlaylistSongs(playlistId)
        songIds.forEachIndexed { index, songId ->
            addSongToPlaylist(
                PlaylistSongCrossRef(
                    playlistId = playlistId,
                    songId = songId,
                    position = index,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
    }
}

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val description: String?,
    val coverArtUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int
)
