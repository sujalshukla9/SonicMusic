package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val duration: Int, // seconds
    val thumbnailUrl: String,
    val year: Int?,
    val category: String,
    val viewCount: Long?,
    val isLiked: Boolean = false,
    val likedAt: Long? = null,
    val cachedStreamUrl: String? = null,
    val cacheExpiry: Long? = null
)
