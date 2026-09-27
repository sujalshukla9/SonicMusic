package com.sonicmusic.app.features.player.presentation

data class PlayerUiState(
    val title: String,
    val artist: String,
    val albumArtUrl: String,
    val isPlaying: Boolean,
    val currentPositionMs: Long,
    val durationMs: Long,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val isBuffering: Boolean
)
