package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.data.local.dao.PlaylistWithCount
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val youTubeiService: YouTubeiService
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getPlaylistsWithSongCount().map { withCounts ->
            withCounts.map { item ->
                val songs = try {
                    playlistDao.getPlaylistSongs(item.id)
                        .take(4)
                        .mapNotNull { songDao.getSongById(it.songId) }
                        .map { entity ->
                            Song(
                                id = entity.id,
                                title = entity.title,
                                artist = entity.artist,
                                duration = entity.duration,
                                thumbnailUrl = entity.thumbnailUrl
                            )
                        }
                } catch (e: Exception) {
                    emptyList()
                }
                item.toPlaylist().copy(songs = songs)
            }
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

    override suspend fun addSongToPlaylist(playlistId: Long, songId: String, force: Boolean): Result<Unit> {
        return try {
            val playlist = playlistDao.getPlaylistById(playlistId)
                ?: return Result.failure(Exception("Playlist not found"))

            if (!force && playlistDao.hasSongInPlaylist(playlistId, songId)) {
                // Idempotency: skip insertion but return success
                return Result.success(Unit)
            }

            val count = playlistDao.getSongCount(playlistId)
            playlistDao.addSongToPlaylist(
                com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef(
                    playlistId = playlistId,
                    songId = songId,
                    position = count,
                    addedAt = System.currentTimeMillis()
                )
            )

            val songThumbnail = songDao.getSongById(songId)?.thumbnailUrl
            playlistDao.updatePlaylist(
                playlist.copy(
                    coverArtUrl = songThumbnail ?: playlist.coverArtUrl,
                    updatedAt = System.currentTimeMillis()
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun hasSongInPlaylist(playlistId: Long, songId: String): Boolean {
        return playlistDao.hasSongInPlaylist(playlistId, songId)
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return try {
            playlistDao.removeSongFromPlaylist(playlistId, songId)

            val playlist = playlistDao.getPlaylistById(playlistId)
            if (playlist != null) {
                val nextCoverArt = playlistDao.getPlaylistSongs(playlistId)
                    .firstOrNull()
                    ?.songId
                    ?.let { firstSongId -> songDao.getSongById(firstSongId)?.thumbnailUrl }

                playlistDao.updatePlaylist(
                    playlist.copy(
                        coverArtUrl = nextCoverArt,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

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

    override suspend fun updatePlaylist(playlistId: Long, name: String, description: String?, coverArtUrl: String?): Result<Unit> {
        return try {
            val playlist = playlistDao.getPlaylistById(playlistId)
            playlist?.let {
                val updated = it.copy(
                    name = name,
                    description = description,
                    coverArtUrl = coverArtUrl ?: it.coverArtUrl,
                    updatedAt = System.currentTimeMillis()
                )
                playlistDao.updatePlaylist(updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchRemotePlaylist(playlistId: String): Result<Pair<Playlist, List<Song>>> {
        return youTubeiService.getPlaylistDetailsFromInnertube(playlistId)
    }

    override suspend fun saveRemotePlaylist(playlist: Playlist, songs: List<Song>): Result<Long> {
        return try {
            val existing = playlist.remoteId?.let { playlistDao.getPlaylistByRemoteId(it) }
            val playlistId = if (existing != null) {
                val updated = existing.copy(
                    name = playlist.name,
                    description = playlist.description,
                    coverArtUrl = playlist.coverArtUrl ?: existing.coverArtUrl,
                    updatedAt = System.currentTimeMillis()
                )
                playlistDao.updatePlaylist(updated)
                existing.id
            } else {
                val entity = PlaylistEntity(
                    name = playlist.name,
                    description = playlist.description,
                    coverArtUrl = playlist.coverArtUrl,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    isLocal = false,
                    remoteId = playlist.remoteId
                )
                playlistDao.insertPlaylist(entity)
            }

            // Save remote songs to local songs table, merging like status
            songs.forEach { song ->
                val existingSong = songDao.getSongById(song.id)
                val songEntity = SongEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    artistId = song.artistId,
                    album = song.album,
                    albumId = song.albumId,
                    duration = song.duration,
                    thumbnailUrl = song.thumbnailUrl,
                    year = song.year,
                    category = song.category ?: "Music",
                    viewCount = song.viewCount,
                    isLiked = existingSong?.isLiked ?: false,
                    likedAt = existingSong?.likedAt,
                    contentType = song.contentType
                )
                songDao.insertSong(songEntity)
            }

            // Rebuild cross refs
            playlistDao.clearPlaylistSongs(playlistId)
            val crossRefs = songs.mapIndexed { index, song ->
                com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef(
                    playlistId = playlistId,
                    songId = song.id,
                    position = index,
                    addedAt = System.currentTimeMillis()
                )
            }
            playlistDao.insertPlaylistSongs(crossRefs)

            Result.success(playlistId)
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
            songs = songs,
            isLocal = isLocal,
            remoteId = remoteId
        )
    }

    private fun PlaylistWithCount.toPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            description = description,
            coverArtUrl = coverArtUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            songCount = songCount,
            songs = emptyList(),
            isLocal = isLocal,
            remoteId = remoteId
        )
    }
}
