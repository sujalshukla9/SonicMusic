package com.sonicmusic.app.features.home.data

import com.sonicmusic.app.domain.model.HomeSong
import com.sonicmusic.app.domain.model.HomeSection

/**
 * Mapping layer for converting raw parsed/filtered items to clean domain entities.
 */
object HomeMapper {

    /**
     * Map [RawHomeSong] to final domain model [HomeSong].
     */
    fun mapToHomeSong(raw: RawHomeSong): HomeSong {
        return HomeSong(
            videoId = raw.videoId,
            title = raw.title,
            artists = raw.artists,
            album = raw.album,
            thumbnailUrl = raw.thumbnailUrl,
            durationText = raw.durationText,
            artistBrowseIds = raw.artistBrowseIds,
            artistRefs = raw.artistRefs
        )
    }

    /**
     * Map [RawHomeSection] to final domain model [HomeSection].
     */
    fun mapToHomeSection(raw: RawHomeSection): HomeSection {
        return HomeSection(
            title = raw.title,
            songs = raw.songs.map(::mapToHomeSong)
        )
    }
}
