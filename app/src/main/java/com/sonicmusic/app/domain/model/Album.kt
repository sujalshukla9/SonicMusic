package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Album type classification.
 */
@Serializable
enum class AlbumType {
    @SerialName("album")
    ALBUM,
    @SerialName("single")
    SINGLE,
    @SerialName("ep")
    EP
}

/**
 * Domain model representing a music album.
 *
 * Used in Trending Albums, New Releases (album view), and Artist profile sections.
 */
@Immutable
@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val thumbnailUrl: String,
    val year: Int? = null,
    val songCount: Int? = null,
    val type: AlbumType = AlbumType.ALBUM
)
