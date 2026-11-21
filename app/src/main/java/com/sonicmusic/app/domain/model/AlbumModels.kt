package com.sonicmusic.app.domain.model

import com.sonicmusic.app.core.util.ThumbnailUrlUtils

data class AlbumDetails(
    val browseId: String,
    val title: String,
    val artists: List<String>,
    val year: String?,
    val albumType: String?, // Album/Single/EP
    val thumbnailUrl: String, // highest res
    val trackCount: Int,
    val songs: List<AlbumTrack>,
    val videos: List<AlbumVideo>, // empty if none found
    val artistBrowseId: String? = null,
    val artistRefs: List<ArtistRef> = emptyList(),
    val hasMoreTracks: Boolean = false
)

data class AlbumTrack(
    val videoId: String,
    val trackNumber: Int,
    val title: String,
    val durationText: String,
    val artists: List<String>,
    val albumBrowseId: String,   // parent album's browseId
    val albumName: String,
    val artistBrowseIds: List<String> = emptyList(),
    val artistRefs: List<ArtistRef> = emptyList(),
    val thumbnailUrl: String? = null
)

data class AlbumVideo(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val durationText: String?
)

fun AlbumTrack.toSong(albumArtUrl: String? = null): Song {
    val refs = if (artistRefs.isNotEmpty()) {
        artistRefs
    } else {
        artists.mapIndexed { index, name ->
            ArtistRef(name, artistBrowseIds.getOrNull(index))
        }
    }
    val combinedArtistId = if (artistBrowseIds.isNotEmpty()) {
        artistBrowseIds.filter { it.isNotBlank() }.joinToString(",").takeIf { it.isNotBlank() }
    } else {
        refs.mapNotNull { it.browseId }.joinToString(",").takeIf { it.isNotBlank() }
    }
    val durationSeconds = try {
        val parts = durationText.split(":")
        when (parts.size) {
            1 -> parts[0].toIntOrNull() ?: 0
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    } catch (_: Exception) { 0 }

    return Song(
        id = videoId,
        title = title,
        artist = artists.joinToString(", ").ifBlank { "Unknown artist" },
        artistId = combinedArtistId,
        album = albumName,
        albumId = albumBrowseId,
        duration = durationSeconds,
        thumbnailUrl = ThumbnailUrlUtils.albumTrackArtwork(videoId, thumbnailUrl, albumArtUrl),
        category = "Music",
        contentType = ContentType.SONG,
        albumBrowseId = albumBrowseId,
        albumName = albumName,
        artists = refs
    )
}

fun AlbumVideo.toSong(albumArtist: String = ""): Song {
    val durationSeconds = try {
        durationText?.let { text ->
            val parts = text.split(":")
            when (parts.size) {
                1 -> parts[0].toIntOrNull() ?: 0
                2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) { 0 }

    return Song(
        id = videoId,
        title = title,
        artist = albumArtist,
        album = "Videos",
        albumId = null,
        duration = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        category = "Video",
        contentType = ContentType.VIDEO,
        artists = if (albumArtist.isNotBlank()) listOf(ArtistRef(albumArtist, null)) else emptyList()
    )
}
