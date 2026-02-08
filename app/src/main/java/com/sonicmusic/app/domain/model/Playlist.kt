package com.sonicmusic.app.domain.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverArtUrl: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int = 0,
    val songs: List<Song> = emptyList()
)