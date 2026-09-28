package com.sonicmusic.app.features.player.presentation.sheets

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sonicmusic.app.domain.model.Song

/**
 * Context source where the song was selected.
 * Used by [GlobalSongActionSheet] to show contextually relevant actions.
 */
enum class SongActionSource {
    HOME,
    SEARCH,
    QUEUE,
    ALBUM,
    ARTIST,
    PLAYLIST,
    LIBRARY,
    LIKED_SONGS,
    HISTORY,
    DOWNLOADS,
    RECOMMENDATION,
    OTHER
}

/**
 * Centralized, singleton state controller for the global song action sheet.
 */
@Stable
class SongActionSheetController {
    var isVisible by mutableStateOf(false)
        private set

    var selectedSong by mutableStateOf<Song?>(null)
        private set

    var source by mutableStateOf(SongActionSource.OTHER)
        private set

    var queueIndex by mutableStateOf<Int?>(null)
        private set

    var playlistId by mutableStateOf<Long?>(null)
        private set

    /**
     * Open the global song action sheet for a canonical [Song].
     */
    fun show(
        song: Song,
        source: SongActionSource = SongActionSource.OTHER,
        queueIndex: Int? = null,
        playlistId: Long? = null
    ) {
        this.selectedSong = song
        this.source = source
        this.queueIndex = queueIndex
        this.playlistId = playlistId
        this.isVisible = true
    }

    /**
     * Dismiss the sheet and safely clear all state to avoid stale data.
     */
    fun dismiss() {
        this.isVisible = false
        this.selectedSong = null
        this.source = SongActionSource.OTHER
        this.queueIndex = null
        this.playlistId = null
    }
}

val LocalSongActionSheetController = compositionLocalOf<SongActionSheetController> {
    error("No SongActionSheetController provided. Make sure to wrap with CompositionLocalProvider.")
}
