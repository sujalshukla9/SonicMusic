package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artist_pages")
data class ArtistPageCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val data: String,
    val fetchedAt: Long
)
