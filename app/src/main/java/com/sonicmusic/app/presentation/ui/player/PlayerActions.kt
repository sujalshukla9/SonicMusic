package com.sonicmusic.app.presentation.ui.player

interface PlayerActions {
    fun onPlayPause()
    fun onSkipNext()
    fun onSkipPrevious()
    fun onSeekTo(positionMs: Long)
    fun onSeekPreviewChange(positionMs: Long)
}
