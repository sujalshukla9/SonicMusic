package com.sonicmusic.app.domain.model

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val duration: Int, // seconds
    val thumbnailUrl: String,
    val isLiked: Boolean = false,
    val streamUrl: String? = null
)
