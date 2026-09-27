package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Clean UI representation of a song or album item in a feed shelf.
 * Fully compatible with the existing [Song] data class.
 */
@Immutable
@Serializable
data class ShelfItem(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Int = 0,
    val thumbnailUrl: String,
    val viewCount: Long? = null,
    val contentType: ContentType = ContentType.SONG,
    val artists: List<ArtistRef> = emptyList()
) {
    /**
     * Map this [ShelfItem] to the standard [Song] domain model.
     */
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        contentType = contentType,
        artists = if (artists.isNotEmpty()) artists else {
            val ids = artistId?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            if (artist.isNotBlank()) listOf(ArtistRef(artist, ids.firstOrNull())) else emptyList()
        }
    )
}
