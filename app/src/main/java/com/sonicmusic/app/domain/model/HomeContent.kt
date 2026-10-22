package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ContinuationShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val songs: List<Song>
)

@Immutable
@Serializable
data class HomeContent(
    val listenAgain: List<Song> = emptyList(),
    val quickPicks: List<Song> = emptyList(),
    val forgottenFavorites: List<Song> = emptyList(),
    val englishHits: List<Song> = emptyList(),
    val artists: List<ArtistSection> = emptyList(),
    // YTMusic Browse-powered sections
    val trendingAlbums: List<Song> = emptyList(),
    val charts: ChartSection? = null,
    val recentlyPlayed: List<Song> = emptyList(),
    val mixedForYou: List<Song> = emptyList(),
    val communityPlaylists: List<CommunityPlaylist> = emptyList(),
    val continuationShelves: List<ContinuationShelf> = emptyList(),
    val continuationToken: String? = null
) {
    fun hasAnyContent(): Boolean =
        listenAgain.isNotEmpty() ||
        quickPicks.isNotEmpty() ||
        forgottenFavorites.isNotEmpty() ||
        englishHits.isNotEmpty() ||
        artists.isNotEmpty() ||
        trendingAlbums.isNotEmpty() ||
        charts?.songs?.isNotEmpty() == true ||
        recentlyPlayed.isNotEmpty() ||
        mixedForYou.isNotEmpty() ||
        communityPlaylists.isNotEmpty() ||
        continuationShelves.isNotEmpty()
}

@Immutable
@Serializable
data class ArtistSection(
    val artist: Artist,
    val songs: List<Song>
)