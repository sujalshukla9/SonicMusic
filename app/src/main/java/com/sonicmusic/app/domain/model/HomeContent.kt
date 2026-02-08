package com.sonicmusic.app.domain.model

data class HomeContent(
    val listenAgain: List<Song> = emptyList(),
    val quickPicks: List<Song> = emptyList(),
    val forgottenFavorites: List<Song> = emptyList(),
    val newReleases: List<Song> = emptyList(),
    val trending: List<Song> = emptyList(),
    val englishHits: List<Song> = emptyList(),
    val artists: List<ArtistSection> = emptyList()
)

data class ArtistSection(
    val artist: Artist,
    val songs: List<Song>
)