package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { it.toPlaylist() }
        }
    }

    override suspend fun getPlaylistById(playlistId: Long): Result<Playlist> {
        return try {
            val playlist = playlistDao.getPlaylistById(playlistId)
            val songs = playlistDao.getPlaylistSongs(playlistId)
            val songEntities = songs.mapNotNull { songDao.getSongById(it.songId) }
            
            if (playlist != null) {
                Result.success(playlist.toPlaylist(songEntities.map { entity ->
                    Song(
                        id = entity.id,
                        title = entity.title,
                        artist = entity.artist,
                        artistId = entity.artistId,
                        album = entity.album,
                        albumId = entity.albumId,
                        duration = entity.duration,
                        thumbnailUrl = entity.thumbnailUrl,
                        year = entity.year,
                        category = entity.category,
                        viewCount = entity.viewCount
                    )
                }))
            } else {
                Result.failure(Exception("Playlist not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createPlaylist(name: String, description: String?): Result<Long> {
        return try {
            val entity = PlaylistEntity(
                name = name,
                description = description,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val id = playlistDao.insertPlaylist(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addSongToPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return try {
            val count = playlistDao.getSongCount(playlistId)
            playlistDao.addSongToPlaylist(
                com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef(
                    playlistId = playlistId,
                    songId = songId,
                    position = count,
                    addedAt = System.currentTimeMillis()
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return try {
            playlistDao.removeSongFromPlaylist(playlistId, songId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): Result<Unit> {
        return try {
            val playlist = playlistDao.getPlaylistById(playlistId)
            playlist?.let { playlistDao.deletePlaylist(it) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePlaylistOrder(playlistId: Long, songIds: List<String>): Result<Unit> {
        return try {
            playlistDao.updatePlaylistOrder(playlistId, songIds)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePlaylist(playlistId: Long, name: String, description: String?): Result<Unit> {
        return try {
            val playlist = playlistDao.getPlaylistById(playlistId)
            playlist?.let {
                val updated = it.copy(
                    name = name,
                    description = description,
                    updatedAt = System.currentTimeMillis()
                )
                playlistDao.updatePlaylist(updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun PlaylistEntity.toPlaylist(songs: List<Song> = emptyList()): Playlist {
        return Playlist(
            id = id,
            name = name,
            description = description,
            coverArtUrl = coverArtUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            songCount = songs.size,
            songs = songs
        )
    }
}