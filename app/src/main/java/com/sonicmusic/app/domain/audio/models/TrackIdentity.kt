package com.sonicmusic.app.domain.audio.models

/**
 * Immutable canonical descriptor of a musical track's recording identity.
 *
 * Used for track identification, version conflict detection, and metadata validation.
 */
data class TrackIdentity(
    val trackId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val version: String? = null,
    val artworkUrl: String? = null,
) {
    val primaryArtist: String
        get() = artists.firstOrNull().orEmpty()

    val durationSec: Int
        get() = ((durationMs ?: 0L) / 1000L).toInt()

    companion object {
        fun from(
            trackId: String,
            title: String,
            artist: String? = null,
            durationSec: Int? = null,
            album: String? = null,
            artworkUrl: String? = null
        ): TrackIdentity {
            val artistList = if (!artist.isNullOrBlank()) {
                artist.split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            } else emptyList()

            return TrackIdentity(
                trackId = trackId,
                title = title,
                artists = if (artistList.isNotEmpty()) artistList else listOfNotNull(artist?.takeIf { it.isNotBlank() }),
                album = album,
                durationMs = durationSec?.let { it.toLong() * 1000L },
                artworkUrl = artworkUrl
            )
        }
    }
}
