package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "followed_artists")
data class FollowedArtistEntity(
    @PrimaryKey
    val artistName: String,
    val browseId: String? = null,
    val thumbnailUrl: String? = null,
    val followedAt: Long = System.currentTimeMillis()
)
