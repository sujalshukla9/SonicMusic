package com.sonicmusic.app.data.mapper

import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.ArtistRef
import com.sonicmusic.app.domain.model.Song
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val jsonSerializer = Json { ignoreUnknownKeys = true; isLenient = true }

fun Song.toEntity(): SongEntity {
    val artistsToPersist = artists.ifEmpty { resolvedArtists }
    val jsonString = if (artistsToPersist.isNotEmpty()) {
        runCatching { jsonSerializer.encodeToString(artistsToPersist) }.getOrNull()
    } else null

    return SongEntity(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = albumName ?: album,
        albumId = albumBrowseId ?: albumId,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        year = year,
        category = category,
        viewCount = viewCount,
        isLiked = isLiked,
        likedAt = likedAt,
        contentType = contentType,
        artistsJson = jsonString
    )
}

fun SongEntity.toSong(): Song {
    val json = artistsJson
    val decodedArtists: List<ArtistRef> = if (!json.isNullOrBlank()) {
        runCatching {
            jsonSerializer.decodeFromString<List<ArtistRef>>(json)
        }.getOrDefault(emptyList())
    } else emptyList()

    val song = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        duration = duration,
        thumbnailUrl = ThumbnailUrlUtils.toHighQuality(thumbnailUrl, id) ?: thumbnailUrl,
        year = year,
        category = category,
        viewCount = viewCount,
        isLiked = isLiked,
        likedAt = likedAt,
        contentType = contentType,
        albumBrowseId = albumId,
        albumName = album,
        artists = decodedArtists
    )

    return if (decodedArtists.isEmpty() && (artist.isNotBlank() || !artistId.isNullOrBlank())) {
        song.copy(artists = song.resolvedArtists)
    } else {
        song
    }
}
