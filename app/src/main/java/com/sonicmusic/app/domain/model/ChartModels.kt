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
) {
    fun toSong(): Song {
        val durationSeconds = try {
            durationText?.let { text ->
                val parts = text.split(":")
                when (parts.size) {
                    1 -> parts[0].toIntOrNull() ?: 0
                    2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                    3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }

        return Song(
            id = videoId,
            title = title,
            artist = artists.joinToString(", ").ifBlank { "Unknown Artist" },
            thumbnailUrl = thumbnailUrl,
            album = albumName,
            albumId = albumBrowseId,
            duration = durationSeconds,
            artistId = artistBrowseIds.firstOrNull() ?: artistRefs.firstOrNull()?.browseId,
            artists = artistRefs.ifEmpty {
                artists.mapIndexed { idx, name ->
                    ArtistRef(name = name, browseId = artistBrowseIds.getOrNull(idx))
                }
            }
        )
    }
}

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
