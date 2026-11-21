package com.sonicmusic.app.shared.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.player.presentation.sheets.LocalSongActionSheetController
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.theme.pressable

/**
 * Reusable modifier combining subtle spring feedback, M3 ripple, normal tap handling,
 * and long-press gesture detection that dispatches to the global [LocalSongActionSheetController].
 */
@Composable
fun Modifier.songPressable(
    song: Song,
    source: SongActionSource,
    enabled: Boolean = true,
    queueIndex: Int? = null,
    playlistId: Long? = null,
    targetScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    val actionSheetController = LocalSongActionSheetController.current
    val haptic = LocalHapticFeedback.current

    return this.pressable(
        onClick = onClick,
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            actionSheetController.show(
                song = song,
                source = source,
                queueIndex = queueIndex,
                playlistId = playlistId
            )
        },
        enabled = enabled,
        targetScale = targetScale
    )
}

@Composable
fun Modifier.songCombinedClickable(
    song: Song,
    source: SongActionSource,
    enabled: Boolean = true,
    queueIndex: Int? = null,
    playlistId: Long? = null,
    onClick: () -> Unit
): Modifier = songPressable(song, source, enabled, queueIndex, playlistId, targetScale = 0.985f, onClick = onClick)
