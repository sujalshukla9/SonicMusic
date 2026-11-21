package com.sonicmusic.app.features.albums.data

import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.data.remote.source.canonicalPublicPlaylistId
import com.sonicmusic.app.domain.model.AlbumTrack
import com.sonicmusic.app.domain.model.AlbumDetails
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.AlbumRepository
import com.sonicmusic.app.features.library.data.ACCOUNT_PLAYLIST_ROUTE
import com.sonicmusic.app.features.library.data.YouTubeMusicLibrary
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val accountLibrary: YouTubeMusicLibrary
) : AlbumRepository {

    private data class CacheEntry(
        val data: AlbumDetails,
        val timestamp: Long
    )

    private val cache = LruCache<String, CacheEntry>(50)
    private val cacheTtlMs = 24 * 60 * 60 * 1000L // 24 hours

    override suspend fun getCachedAlbumDetails(browseId: String): AlbumDetails? {
        if (!browseId.startsWith(ACCOUNT_PLAYLIST_ROUTE)) return null
        return try {
            val page = accountLibrary.cachedPlaylistPage(browseId) ?: return null
            playlistDetails(browseId, "", "", page.items.mapNotNull(HomeShelfItem::playableSong), page.hasMore)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getAlbumDetails(browseId: String, forceRefresh: Boolean, append: Boolean): Result<AlbumDetails> {
        val normalizedId = normalizeAlbumId(browseId)
        if (normalizedId.isBlank()) {
            return Result.failure(IllegalArgumentException("Album browseId is empty"))
        }

        if (normalizedId.startsWith(ACCOUNT_PLAYLIST_ROUTE)) {
            return try {
                val page = accountLibrary.playlistPage(normalizedId, forceRefresh, append)
                Result.success(playlistDetails(normalizedId, "", "", page.items.mapNotNull(HomeShelfItem::playableSong), page.hasMore))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cache.get(normalizedId)
            if (cached != null && now - cached.timestamp < cacheTtlMs) {
                android.util.Log.d("AlbumRepo", "Album cache hit for ID: $normalizedId")
                return Result.success(cached.data)
            }
        }

        android.util.Log.d("AlbumRepo", "Album cache miss for ID: $normalizedId. Fetching from network.")
        val result = if (canonicalPublicPlaylistId(normalizedId) != null) {
            youTubeiService.getPlaylistDetailsFromInnertube(normalizedId, audioTracksOnly = true).map { (playlist, songs) ->
                playlistDetails(normalizedId, playlist.name, playlist.coverArtUrl.orEmpty(), songs)
            }
        } else {
            youTubeiService.getAlbumDetailsFromInnertube(normalizedId)
        }
        return result.onSuccess { details -> cache.put(normalizedId, CacheEntry(details, now)) }
    }

    private fun playlistDetails(
        id: String,
        title: String,
        artwork: String,
        songs: List<Song>,
        hasMore: Boolean = false
    ) = AlbumDetails(
        browseId = id,
        title = title,
        artists = emptyList(),
        year = null,
        albumType = "Playlist",
        thumbnailUrl = artwork,
        trackCount = songs.size,
        songs = songs.mapIndexed { index, song ->
            AlbumTrack(
                videoId = song.id,
                trackNumber = index + 1,
                title = song.title,
                durationText = song.formattedDuration(),
                artists = song.resolvedArtists.map { it.name }.ifEmpty { listOf(song.artist) },
                albumBrowseId = song.albumBrowseId.orEmpty(),
                albumName = song.albumName.orEmpty(),
                artistRefs = song.resolvedArtists,
                thumbnailUrl = song.thumbnailUrl
            )
        },
        videos = emptyList(),
        hasMoreTracks = hasMore
    )

    private fun normalizeAlbumId(id: String): String {
        val trimmed = id.trim()
        return when {
            trimmed.startsWith("VLMPRE") -> trimmed.removePrefix("VL")
            else -> trimmed
        }
    }
}
