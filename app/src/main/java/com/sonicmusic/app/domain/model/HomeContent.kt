package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class HomeContent(
    val listenAgain: List<Song> = emptyList(),
    val quickPicks: List<Song> = emptyList(),
    val forgottenFavorites: List<Song> = emptyList(),
    val newReleases: List<Song> = emptyList(),
    val trending: List<Song> = emptyList(),
    val englishHits: List<Song> = emptyList(),
    val artists: List<ArtistSection> = emptyList(),
    val personalizedForYou: List<Song> = emptyList(),
    // YTMusic Browse-powered sections
    val trendingAlbums: List<Song> = emptyList(),
    val trendingArtists: List<Song> = emptyList(),
    val moodGenres: List<MoodGenre> = emptyList(),
    val charts: ChartSection? = null,
    val recentlyPlayed: List<Song> = emptyList(),
    val mixedForYou: List<Song> = emptyList()
)

@Immutable
data class ArtistSection(
    val artist: Artist,
    val songs: List<Song>
)