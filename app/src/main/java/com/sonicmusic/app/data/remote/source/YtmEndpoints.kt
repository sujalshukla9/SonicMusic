package com.sonicmusic.app.data.remote.source

/**
 * Sealed hierarchy and validator representing ONLY permitted YouTube Music browse IDs and endpoints.
 * Makes it structurally impossible to invoke arbitrary non-YTM namespaces.
 */
sealed class YtmBrowseId(val value: String) {

    // ── Static Permitted Browse IDs ──
    object Home : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.HOME)
    object MoodsAndGenres : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.MOODS_AND_GENRES)
    object MoodsAndGenresCategory : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.MOODS_AND_GENRES_CATEGORY)
    object Charts : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.CHARTS)
    object NewReleasesAlbums : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.NEW_RELEASES_ALBUMS)
    object NewReleasesSongs : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.NEW_RELEASES_SONGS)
    object LikedPlaylists : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.LIKED_PLAYLISTS)
    object Library : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.LIBRARY)
    object History : YtmBrowseId(YtmScopeConfig.PermittedBrowseIds.HISTORY)

    // ── Dynamic Browse IDs with prefix enforcement ──
    data class Album(val id: String) : YtmBrowseId(id) {
        init {
            require(id.startsWith("MPREb_") || id.startsWith("OLAK5uy_")) {
                "Invalid YTM Album browseId '$id'. Must start with 'MPREb_' or 'OLAK5uy_'."
            }
        }
    }

    data class Artist(val channelId: String) : YtmBrowseId(channelId) {
        init {
            require(channelId.startsWith("UC")) {
                "Invalid YTM Artist browseId '$channelId'. Must start with 'UC'."
            }
        }
    }

    data class Playlist(val playlistId: String) : YtmBrowseId(playlistId) {
        init {
            require(
                playlistId.startsWith("VL") ||
                playlistId.startsWith("PL") ||
                playlistId.startsWith("RDCLAK") ||
                playlistId.startsWith("RDAMVM") ||
                playlistId.startsWith("RDAMPL") ||
                playlistId.startsWith("RD")
            ) {
                "Invalid YTM Playlist browseId '$playlistId'."
            }
        }
    }

    data class Custom(val customBrowseId: String) : YtmBrowseId(customBrowseId) {
        init {
            require(isAllowedBrowseId(customBrowseId)) {
                "BrowseId '$customBrowseId' is not permitted within the YTM namespace."
            }
        }
    }

    companion object {
        fun isAllowedBrowseId(browseId: String): Boolean {
            if (browseId.isBlank()) return false
            if (browseId.startsWith("FEmusic_")) return true
            return YtmScopeConfig.ALLOWED_DYNAMIC_BROWSE_ID_PREFIXES.any { browseId.startsWith(it) } ||
                   browseId.startsWith("RD")
        }

        fun from(browseId: String): YtmBrowseId {
            return when {
                browseId == YtmScopeConfig.PermittedBrowseIds.HOME -> Home
                browseId == YtmScopeConfig.PermittedBrowseIds.MOODS_AND_GENRES -> MoodsAndGenres
                browseId == YtmScopeConfig.PermittedBrowseIds.MOODS_AND_GENRES_CATEGORY -> MoodsAndGenresCategory
                browseId == YtmScopeConfig.PermittedBrowseIds.CHARTS -> Charts
                browseId == YtmScopeConfig.PermittedBrowseIds.NEW_RELEASES_ALBUMS -> NewReleasesAlbums
                browseId == YtmScopeConfig.PermittedBrowseIds.NEW_RELEASES_SONGS -> NewReleasesSongs
                browseId == YtmScopeConfig.PermittedBrowseIds.LIKED_PLAYLISTS -> LikedPlaylists
                browseId == YtmScopeConfig.PermittedBrowseIds.LIBRARY -> Library
                browseId == YtmScopeConfig.PermittedBrowseIds.HISTORY -> History
                browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_") -> Album(browseId)
                browseId.startsWith("UC") -> Artist(browseId)
                browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") -> Playlist(browseId)
                else -> Custom(browseId)
            }
        }
    }
}
