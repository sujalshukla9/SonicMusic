package com.sonicmusic.app.domain.model

data class Artist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val songCount: Int = 0,
    val playCount: Int = 0,
    val topSongIds: List<String> = emptyList()
)