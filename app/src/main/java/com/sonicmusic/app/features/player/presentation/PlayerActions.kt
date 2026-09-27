package com.sonicmusic.app.features.player.presentation

interface PlayerActions {
    fun onPlayPause()
    fun onSkipNext()
    fun onSkipPrevious()
    fun onSeekTo(positionMs: Long)
    fun onSeekPreviewChange(positionMs: Long)
}
