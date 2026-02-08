package com.sonicmusic.app.domain.model

data class Song(
    val id: String,
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
    val likedAt: Long? = null
) {
    fun formattedDuration(): String {
        val minutes = duration / 60
        val seconds = duration % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}