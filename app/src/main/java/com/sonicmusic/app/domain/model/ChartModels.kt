package com.sonicmusic.app.domain.model

data class ChartSong(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val thumbnailUrl: String,
    val durationText: String?,
    val rank: Int?,
    val albumBrowseId: String? = null,
    val albumName: String? = null,
    val artistBrowseIds: List<String> = emptyList(),
    val artistRefs: List<ArtistRef> = emptyList()
)

data class ChartArtist(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String,
    val subscriberCountText: String?
)

data class ChartAlbum(
    val browseId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val year: String?
)
