package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef
import com.sonicmusic.app.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("""
        SELECT p.*, (SELECT COUNT(*) FROM playlist_songs WHERE playlistId = p.id) AS songCount,
            s.id AS preview_id, s.title AS preview_title, s.artist AS preview_artist,
            s.duration AS preview_duration, s.thumbnailUrl AS preview_thumbnailUrl
        FROM playlists p
        LEFT JOIN playlist_songs ps ON ps.id IN (
            SELECT id FROM playlist_songs WHERE playlistId = p.id ORDER BY position, id LIMIT 4
        )
        LEFT JOIN songs s ON s.id = ps.songId
        ORDER BY p.updatedAt DESC, p.id DESC, ps.position, ps.id
    """)
    fun observePlaylistPreviews(): Flow<List<PlaylistPreviewRow>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(crossRefs: List<PlaylistSongCrossRef>)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId LIMIT 1)")
    suspend fun hasSongInPlaylist(playlistId: Long, songId: String): Boolean

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("""
        SELECT s.* FROM playlist_songs ps JOIN songs s ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId ORDER BY ps.position, ps.id
    """)
    suspend fun getPlaylistSongs(playlistId: Long): List<SongEntity>

    @Query("""
        SELECT s.thumbnailUrl FROM playlist_songs ps LEFT JOIN songs s ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId ORDER BY ps.position, ps.id LIMIT 1
    """)
    suspend fun getFirstSongThumbnail(playlistId: Long): String?

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getNextSongPosition(playlistId: Long): Int

    @Transaction
    suspend fun updatePlaylistOrder(playlistId: Long, songIds: List<String>) {
        clearPlaylistSongs(playlistId)
        val crossRefs = songIds.mapIndexed { index, songId ->
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = songId,
                position = index,
                addedAt = System.currentTimeMillis()
            )
        }
        insertPlaylistSongs(crossRefs)
    }

    @Query("SELECT * FROM playlists WHERE remoteId LIKE :pattern")
    suspend fun getPlaylistsByRemoteIdPattern(pattern: String): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE remoteId LIKE :pattern LIMIT 1")
    fun observePlaylistByRemoteIdPattern(pattern: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getPlaylistByRemoteId(remoteId: String): PlaylistEntity?
}

data class PlaylistPreviewRow(
    @Embedded val playlist: PlaylistWithCount,
    @Embedded(prefix = "preview_") val song: PlaylistPreviewSong?
)

data class PlaylistPreviewSong(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val thumbnailUrl: String
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val description: String?,
    val coverArtUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int,
    val isLocal: Boolean = true,
    val remoteId: String? = null
)
