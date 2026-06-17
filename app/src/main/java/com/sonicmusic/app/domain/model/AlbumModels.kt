package com.sonicmusic.app.domain.model

data class AlbumDetails(
    val browseId: String,
    val title: String,
    val artists: List<String>,
    val year: String?,
    val albumType: String?, // Album/Single/EP
    val thumbnailUrl: String, // highest res
    val trackCount: Int,
    val songs: List<AlbumTrack>,
    val videos: List<AlbumVideo> // empty if none found
)

data class AlbumTrack(
    val videoId: String,
    val trackNumber: Int,
    val title: String,
    val durationText: String,
    val artists: List<String>,
    val albumBrowseId: String,   // parent album's browseId
    val albumName: String
)

data class AlbumVideo(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val durationText: String?
)
