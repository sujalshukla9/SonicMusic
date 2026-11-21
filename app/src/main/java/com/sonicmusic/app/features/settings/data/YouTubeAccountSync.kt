package com.sonicmusic.app.features.settings.data

import androidx.room.withTransaction
import com.sonicmusic.app.data.local.database.SonicMusicDatabase
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.YOUTUBE_ACCOUNT_PLAYLIST_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeAccountSync @Inject constructor(
    private val api: YouTubeAccountApi,
    private val database: SonicMusicDatabase
) {
    private val playlists = database.playlistDao()

    val connection = playlists.observePlaylistByRemoteIdPattern("$LIKES_PREFIX%")
        .map { entry ->
            entry?.remoteId?.removePrefix(LIKES_PREFIX)?.let { YouTubeConnection(it, entry.updatedAt) }
        }

    suspend fun sync(token: String, connectedChannelId: String?): YouTubeSyncResult = withContext(Dispatchers.IO) {
        val channelId = api.channelId(token) ?: throw NoYouTubeChannelException()
        if (connectedChannelId != null && channelId != connectedChannelId) {
            throw YouTubeAccountChangedException()
        }
        val likedMusic = api.likedMusic(token)
        val accountPlaylists = api.playlists(token)
        saveSnapshot(channelId, likedMusic, accountPlaylists)
        YouTubeSyncResult(accountPlaylists.size, likedMusic.size)
    }

    internal suspend fun saveSnapshot(
        channelId: String,
        likedMusic: List<Song>,
        accountPlaylists: List<YouTubeAccountPlaylist>
    ) {
        val playlistPrefix = "$YOUTUBE_ACCOUNT_PLAYLIST_PREFIX$channelId:"
        val allPlaylists = buildMap {
            put(
                "$LIKES_PREFIX$channelId",
                YouTubeAccountPlaylist("likes", "Liked music on YouTube", likedMusic.firstOrNull()?.thumbnailUrl, likedMusic)
            )
            accountPlaylists.forEach { playlist -> put("$playlistPrefix${playlist.id}", playlist) }
        }
        val songs = allPlaylists.values.flatMap(YouTubeAccountPlaylist::songs).distinctBy(Song::id)
        val now = System.currentTimeMillis()
        database.withTransaction {
            val existing = playlists.getPlaylistsByRemoteIdPattern(ACCOUNT_PATTERN).associateBy(PlaylistEntity::remoteId)
            database.songDao().insertMissingSongs(songs.map { it.toEntity() })
            for ((remoteId, playlist) in allPlaylists) {
                val current = existing[remoteId]
                val entry = PlaylistEntity(
                    id = current?.id ?: 0,
                    name = playlist.title,
                    coverArtUrl = playlist.coverUrl,
                    createdAt = current?.createdAt ?: now,
                    updatedAt = now,
                    isLocal = false,
                    remoteId = remoteId
                )
                val id = if (current == null) {
                    playlists.insertPlaylist(entry)
                } else {
                    playlists.updatePlaylist(entry)
                    current.id
                }
                playlists.updatePlaylistOrder(id, playlist.songs.map(Song::id))
            }
            existing.values.filter { it.remoteId !in allPlaylists }.forEach { playlists.deletePlaylist(it) }
        }
    }

    suspend fun disconnect(token: String, channelId: String) {
        if (api.channelId(token) != channelId) throw YouTubeAccountChangedException()
        api.revoke(token)
        clearPlaylists()
    }

    suspend fun clearPlaylists() {
        database.withTransaction {
            playlists.getPlaylistsByRemoteIdPattern(ACCOUNT_PATTERN).forEach { playlists.deletePlaylist(it) }
        }
    }

    private companion object {
        const val LIKES_PREFIX = "${YOUTUBE_ACCOUNT_PLAYLIST_PREFIX}likes:"
        const val ACCOUNT_PATTERN = "$YOUTUBE_ACCOUNT_PLAYLIST_PREFIX%"
    }
}

data class YouTubeConnection(val channelId: String, val lastSyncedAt: Long)
data class YouTubeSyncResult(val playlistCount: Int, val likedCount: Int)
class NoYouTubeChannelException : Exception()
class YouTubeAccountChangedException : Exception()
