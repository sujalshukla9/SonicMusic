package com.sonicmusic.app.data.remote.source

/**
 * Single source-of-truth configuration for YouTube Music (YTM) scoping across SonicMusic.
 * Centralizes client identity, permitted browse IDs, search category params, and content filtering rules.
 */
object YtmScopeConfig {

    object ClientContext {
        const val CLIENT_NAME = InnertubeConfig.ClientContext.CLIENT_NAME
        const val CLIENT_VERSION = InnertubeConfig.ClientContext.CLIENT_VERSION
        const val PLATFORM = InnertubeConfig.ClientContext.PLATFORM
        const val USER_AGENT = InnertubeConfig.ClientContext.USER_AGENT
        const val HOST = InnertubeConfig.ClientContext.HOST
    }

    object PermittedBrowseIds {
        const val HOME = "FEmusic_home"
        const val MOODS_AND_GENRES = "FEmusic_moods_and_genres"
        const val MOODS_AND_GENRES_CATEGORY = "FEmusic_moods_and_genres_category"
        const val CHARTS = "FEmusic_charts"
        const val NEW_RELEASES_ALBUMS = "FEmusic_new_releases_albums"
        const val NEW_RELEASES_SONGS = "FEmusic_new_releases"
        const val LIKED_PLAYLISTS = "FEmusic_liked_playlists"
        const val LIBRARY = "FEmusic_library_landing"
        const val HISTORY = "FEmusic_history"
    }

    val ALLOWED_DYNAMIC_BROWSE_ID_PREFIXES = listOf(
        "MPREb_", // YTM Album browse IDs
        "OLAK5uy_", // YTM Album release IDs
        "UC",     // YouTube Music Artist channels
        "VL",     // YTM Playlist wrapper
        "PL",     // YTM / YouTube Playlists
        "RDCLAK"  // YTM Radio / Curated Mixes
    )

    object SearchCategoryParams {
        // These are raw (non-URL-encoded) base64 protobuf params.
        // They are placed directly in the JSON request body — NOT in query params.
        // %3D (URL-encoded '=') must NOT be used here; plain '=' is required.
        const val SONGS = "EgWKAQIIAWoOEAMQBBAJEAoQBRAQEBU="
        const val VIDEOS = "EgWKAQIQAWoOEAMQBBAJEAoQBRAQEBU="
        const val ALBUMS = "EgWKAQIYAWoOEAMQBBAJEAoQBRAQEBU="
        const val ARTISTS = "EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU="
        const val PLAYLISTS = "EgeKAQQoAEABag4QAxAEEAkQChAFEBAQFQ=="
    }

    object ContentFilter {
        val KEEP_VIDEO_TYPES = setOf(
            "MUSIC_VIDEO_TYPE_ATV", // Audio Track Video (Official audio from label)
            "MUSIC_VIDEO_TYPE_OMV"  // Official Music Video
        )

        val REJECT_VIDEO_TYPES = setOf(
            "MUSIC_VIDEO_TYPE_UGC", // User Generated Content
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
        )

        // Case-insensitive regex blacklist for non-music / compilations / fan-edited / UGC edits
        val TITLE_BLACKLIST_REGEX = Regex(
            "(?i)(mashup|slowed|reverb|8d\\s+audio|lofi\\s+remix|tiktok|viral\\s+songs|compilation|fanmade|tribute|cover|live\\s+performance|\\bjukebox\\b|\\baudio\\s+jukebox\\b|\\bvideo\\s+jukebox\\b|\\bfull\\s+album\\b|\\bcomplete\\s+album\\b|\\bnon-?stop\\s+(audio|jukebox|mix|party)\\b)"
        )

        const val REQUIRE_ARTIST_LINK_IF_TYPE_MISSING = true
    }
}
