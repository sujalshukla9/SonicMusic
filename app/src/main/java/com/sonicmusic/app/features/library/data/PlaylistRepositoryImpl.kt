package com.sonicmusic.app.features.library.data

import androidx.room.withTransaction
import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.data.local.dao.PlaylistWithCount
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.database.SonicMusicDatabase
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.data.mapper.toSong
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.YOUTUBE_ACCOUNT_PLAYLIST_PREFIX
import com.sonicmusic.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val database: SonicMusicDatabase,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val youTubeiService: YouTubeiService
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.observePlaylistPreviews().map { rows ->
            rows.groupBy { it.playlist }.map { (playlist, previews) ->
                val songs = previews.mapNotNull { row ->
                    row.song?.let { Song(it.id, it.title, it.artist, duration = it.duration, thumbnailUrl = it.thumbnailUrl) }
                }
                playlist.toPlaylist(songs)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override suspend fun getPlaylistById(playlistId: Long): Result<Playlist> {
        return try {
            val (playlist, songs) = database.withTransaction {
                val playlist = checkNotNull(playlistDao.getPlaylistById(playlistId)) { "Playlist not found" }
                playlist to playlistDao.getPlaylistSongs(playlistId)
            }
            Result.success(withContext(Dispatchers.Default) { playlist.toPlaylist(songs.map { entity ->
                    val song = entity.toSong()
                    if (playlist.isLocal) song else song.copy(
                        thumbnailUrl = com.sonicmusic.app.core.util.ThumbnailUrlUtils.playlistTrackArtwork(
                            song.id, song.thumbnailUrl, playlist.coverArtUrl
                        )
                    )
                }) })
        } catch (e: CancellationException) {
            throw e
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
            database.withTransaction {
                val playlist = checkNotNull(playlistDao.getPlaylistById(playlistId)) { "Playlist not found" }
                check(playlist.remoteId?.startsWith(YOUTUBE_ACCOUNT_PLAYLIST_PREFIX) != true) {
                    "Synced YouTube playlists are read-only."
                }
                if (!force && playlistDao.hasSongInPlaylist(playlistId, songId)) return@withTransaction

                val now = System.currentTimeMillis()
                playlistDao.addSongToPlaylist(
                    PlaylistSongCrossRef(
                        playlistId = playlistId,
                        songId = songId,
                        position = playlistDao.getNextSongPosition(playlistId),
                        addedAt = now
                    )
                )
                val songThumbnail = songDao.getSongById(songId)?.thumbnailUrl
                playlistDao.updatePlaylist(
                    playlist.copy(coverArtUrl = songThumbnail ?: playlist.coverArtUrl, updatedAt = now)
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun hasSongInPlaylist(playlistId: Long, songId: String): Boolean {
        return playlistDao.hasSongInPlaylist(playlistId, songId)
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return try {
            database.withTransaction {
                val playlist = playlistDao.getPlaylistById(playlistId) ?: return@withTransaction
                playlistDao.removeSongFromPlaylist(playlistId, songId)
                playlistDao.updatePlaylist(
                    playlist.copy(
                        coverArtUrl = playlistDao.getFirstSongThumbnail(playlistId),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
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
            val songEntities = withContext(Dispatchers.Default) { songs.distinctBy { it.id }.map { it.toEntity() } }
            val savedId = database.withTransaction {
                val existing = playlist.remoteId?.let { id ->
                    playlistDao.getPlaylistByRemoteId(id)
                        ?: com.sonicmusic.app.data.remote.source.canonicalPublicPlaylistId(id)?.let { canonicalId ->
                            playlistDao.getPlaylistByRemoteId("VL$canonicalId")
                        }
                }
                val now = System.currentTimeMillis()
                val playlistId = if (existing != null) {
                    val updated = existing.copy(
                        name = playlist.name,
                        description = playlist.description,
                        coverArtUrl = playlist.coverArtUrl ?: existing.coverArtUrl,
                        updatedAt = now
                    )
                    playlistDao.updatePlaylist(updated)
                    existing.id
                } else {
                    val entity = PlaylistEntity(
                        name = playlist.name,
                        description = playlist.description,
                        coverArtUrl = playlist.coverArtUrl,
                        createdAt = now,
                        updatedAt = now,
                        isLocal = false,
                        remoteId = playlist.remoteId
                    )
                    playlistDao.insertPlaylist(entity)
                }

                songEntities.chunked(500).forEach { batch ->
                    val existingSongs = songDao.getSongsByIds(batch.map { it.id }).associateBy { it.id }
                    songDao.insertAll(batch.map { song ->
                        val existingSong = existingSongs[song.id]
                        song.copy(isLiked = existingSong?.isLiked ?: false, likedAt = existingSong?.likedAt)
                    })
                }

                // Rebuild cross refs
                playlistDao.clearPlaylistSongs(playlistId)
                val crossRefs = songs.mapIndexed { index, song ->
                    PlaylistSongCrossRef(
                        playlistId = playlistId,
                        songId = song.id,
                        position = index,
                        addedAt = now
                    )
                }
                playlistDao.insertPlaylistSongs(crossRefs)

                playlistId
            }
            Result.success(savedId)
        } catch (e: CancellationException) {
            throw e
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

    private fun PlaylistWithCount.toPlaylist(songs: List<Song>): Playlist {
        return Playlist(
            id = id,
            name = name,
            description = description,
            coverArtUrl = coverArtUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            songCount = songCount,
            songs = songs,
            isLocal = isLocal,
            remoteId = remoteId
        )
    }
}
