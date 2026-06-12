package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ArtistProfile(
    val name: String,
    val browseId: String? = null,
    val imageUrl: String? = null,
    val bannerUrl: String? = null,
    val subscribersText: String? = null,
    val description: String? = null,
    val shufflePlaylistId: String? = null,
    val radioPlaylistId: String? = null,
    val topSongs: List<Song> = emptyList(),
    val albums: List<Song> = emptyList(),
    val singles: List<Song> = emptyList(),
    val videos: List<Song> = emptyList(),
    val featuredOn: List<Song> = emptyList(),
    val relatedArtists: List<Song> = emptyList(),
    val sections: List<ArtistProfileSection> = emptyList(),
    val topSongsBrowseId: String? = null,
    val songsMoreEndpoint: String? = null,
    val albumsMoreEndpoint: String? = null,
    val singlesMoreEndpoint: String? = null,
    val isStale: Boolean = false
)

@Immutable
@Serializable
data class ArtistProfileSection(
    val type: ArtistProfileSectionType = ArtistProfileSectionType.Unknown,
    val title: String = "",
    val browseId: String? = null,
    val moreEndpoint: String? = null,
    val items: List<Song> = emptyList()
)

@Serializable
enum class ArtistProfileSectionType {
    TopSongs,
    Albums,
    Singles,
    Videos,
    FeaturedOn,
    RelatedArtists,
    Unknown
}
