package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ContinuationShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val songs: List<Song>
)

@Immutable
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
    val continuationShelves: List<ContinuationShelf> = emptyList(),
    val continuationToken: String? = null
)

@Immutable
data class ArtistSection(
    val artist: Artist,
    val songs: List<Song>
)