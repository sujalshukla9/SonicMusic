package com.sonicmusic.app.features.artists.data

import com.sonicmusic.app.domain.model.ArtistProfile
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song

internal fun ArtistProfile.normalizedArtistCatalog(): ArtistProfile {
    fun collect(type: ArtistProfileSectionType, preview: List<Song>): List<Song> =
        (preview + sections.filter { it.type == type }.flatMap { it.items })
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.id }

    val releases = collect(ArtistProfileSectionType.Singles, singles).filter { it.contentType == ContentType.ALBUM }
    val singleIds = releases.mapTo(hashSetOf()) { it.id }
    return copy(
        topSongs = collect(ArtistProfileSectionType.TopSongs, topSongs)
            .filter { it.contentType == ContentType.SONG || it.contentType == ContentType.UNKNOWN },
        albums = collect(ArtistProfileSectionType.Albums, albums)
            .filter { it.contentType == ContentType.ALBUM && it.id !in singleIds },
        singles = releases,
        videos = collect(ArtistProfileSectionType.Videos, videos)
            .filter { it.contentType == ContentType.VIDEO || it.contentType == ContentType.SONG }
            .map { if (it.contentType == ContentType.VIDEO) it else it.copy(contentType = ContentType.VIDEO) },
        featuredOn = collect(ArtistProfileSectionType.FeaturedOn, featuredOn).filter { it.contentType == ContentType.PLAYLIST },
        relatedArtists = collect(ArtistProfileSectionType.RelatedArtists, relatedArtists).filter { it.contentType == ContentType.ARTIST }
    )
}
