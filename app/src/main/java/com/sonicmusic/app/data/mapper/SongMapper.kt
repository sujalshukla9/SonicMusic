package com.sonicmusic.app.data.mapper

import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.domain.model.Song

fun Song.toEntity(): SongEntity {
    return SongEntity(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        year = year,
        category = category,
        viewCount = viewCount,
        isLiked = isLiked,
        likedAt = likedAt
    )
}

fun SongEntity.toSong(): Song {
    return Song(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        duration = duration,
        thumbnailUrl = ThumbnailUrlUtils.toHighQuality(thumbnailUrl, id) ?: thumbnailUrl,
        year = year,
        category = category,
        viewCount = viewCount,
        isLiked = isLiked,
        likedAt = likedAt
    )
}
