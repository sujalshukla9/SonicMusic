package com.sonicmusic.app.domain.audio.models

/**
 * Immutable metadata describing a musical recording from catalog discovery
 * (such as Innertube / YouTube Music discovery).
 *
 * Distinct from playback-source technical properties (codec, bitrate, container).
 */
data class TrackMetadata(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val albumBrowseId: String? = null
) {
    val durationSec: Int get() = (durationMs / 1000L).toInt()
}
