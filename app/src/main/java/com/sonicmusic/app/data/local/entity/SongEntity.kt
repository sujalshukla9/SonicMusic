package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["isLiked"]),
        Index(value = ["artist"])
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Int,
    val thumbnailUrl: String,
    val year: Int? = null,
    val category: String = "Music",
    val viewCount: Long? = null,
    val isLiked: Boolean = false,
    val likedAt: Long? = null,
    val cachedStreamUrl: String? = null,
    val cacheExpiry: Long? = null
)