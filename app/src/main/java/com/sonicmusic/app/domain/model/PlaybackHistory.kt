package com.sonicmusic.app.domain.model

data class PlaybackHistory(
    val id: Long = 0,
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val playedAt: Long,
    val playDuration: Int,
    val completed: Boolean
)