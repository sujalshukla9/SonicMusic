package com.sonicmusic.app.data.remote.model

enum class ArtistSource {
    RUNS_PAGE_TYPE,      // Confidence: 1.0 — from pageType = ARTIST
    RUNS_BROWSE_ID,      // Confidence: 0.9 — from UC... browseId
    SHORT_BYLINE,        // Confidence: 0.85 — from shortBylineText
    TEXT_PARSING,        // Confidence: 0.6 — from text splitting
    PLAYER_AUTHOR,       // Confidence: 0.5 — from videoDetails.author  
    FALLBACK             // Confidence: 0.0 — "Unknown Artist"
}

enum class ArtistRole {
    PRIMARY,      // Main artist
    FEATURED,     // "feat." / "ft."
    PRODUCER,     // Produced by
    COMPOSER      // Written by
}

data class Run(
    val text: String,
    val browseId: String? = null,
    val pageType: String? = null
)

data class ExtractedArtist(
    val name: String,
    val browseId: String? = null,
    val role: ArtistRole = ArtistRole.PRIMARY
)

data class ArtistInfo(
    val displayName: String,
    val individuals: List<ExtractedArtist>,
    val source: ArtistSource,
    val confidence: Float
)
