package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import com.sonicmusic.app.presentation.ui.LocalWindowSizeClass

/**
 * Responsive dimensions tokens that adapt based on the active [WindowSizeClass].
 */
object Dimens {

    /** Preferred album art size for the full player */
    val albumArtSize: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 360.dp
                WindowWidthSizeClass.Medium -> 440.dp
                WindowWidthSizeClass.Expanded -> 520.dp
                else -> 360.dp
            }
        }

    /** Size for the main play/pause button in classic controls */
    val playButtonSize: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 72.dp
                WindowWidthSizeClass.Medium -> 80.dp
                WindowWidthSizeClass.Expanded -> 88.dp
                else -> 72.dp
            }
        }

    /** Play icon size inside the play button */
    val playIconSize: Dp
        @Composable
        get() = playButtonSize / 2

    /** Size for the skip buttons (next/prev) in classic controls */
    val skipButtonSize: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 48.dp
                WindowWidthSizeClass.Medium -> 56.dp
                WindowWidthSizeClass.Expanded -> 64.dp
                else -> 48.dp
            }
        }

    /** Skip icon size inside skip buttons */
    val skipIconSize: Dp
        @Composable
        get() = skipButtonSize / 2

    /** Size for modern player controls buttons/pills */
    val modernControlButtonSize: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 64.dp
                WindowWidthSizeClass.Medium -> 72.dp
                WindowWidthSizeClass.Expanded -> 80.dp
                else -> 64.dp
            }
        }

    /** Size of items/cards on home shelves */
    val cardWidth: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 140.dp
                WindowWidthSizeClass.Medium -> 160.dp
                WindowWidthSizeClass.Expanded -> 180.dp
                else -> 140.dp
            }
        }

    /** Size of larger square cards on home shelves */
    val largeCardWidth: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 160.dp
                WindowWidthSizeClass.Medium -> 180.dp
                WindowWidthSizeClass.Expanded -> 200.dp
                else -> 160.dp
            }
        }

    /** Height of the continue listening hero card */
    val continueListeningCardHeight: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 100.dp
                WindowWidthSizeClass.Medium -> 110.dp
                WindowWidthSizeClass.Expanded -> 120.dp
                else -> 100.dp
            }
        }

    /** Thumbnail size inside the continue listening card */
    val continueListeningThumbSize: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 72.dp
                WindowWidthSizeClass.Medium -> 80.dp
                WindowWidthSizeClass.Expanded -> 88.dp
                else -> 72.dp
            }
        }

    /** Standard horizontal margins for content lists/headers */
    val screenPadding: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 16.dp
                WindowWidthSizeClass.Medium -> 24.dp
                WindowWidthSizeClass.Expanded -> 32.dp
                else -> 16.dp
            }
        }

    /** Bottom Navigation Bar Height in compact layouts */
    val bottomNavHeight: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 80.dp
                WindowWidthSizeClass.Medium -> 84.dp
                WindowWidthSizeClass.Expanded -> 88.dp
                else -> 80.dp
            }
        }

    /** Height of the MiniPlayer */
    val miniPlayerHeight: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 64.dp
                WindowWidthSizeClass.Medium -> 72.dp
                WindowWidthSizeClass.Expanded -> 80.dp
                else -> 64.dp
            }
        }

    /** Thumbnail size inside the MiniPlayer */
    val miniPlayerArtSize: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 44.dp
                WindowWidthSizeClass.Medium -> 48.dp
                WindowWidthSizeClass.Expanded -> 52.dp
                else -> 44.dp
            }
        }

    /** Maximum width constraint for player panels and list contents on large screens */
    val maxContentWidth: Dp
        @Composable
        get() = 600.dp

    /** Top padding above artwork in FullPlayerScreen */
    val playerTopSpacing: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.heightSizeClass) {
                WindowHeightSizeClass.Compact -> 16.dp
                WindowHeightSizeClass.Medium -> 40.dp
                else -> 64.dp
            }
        }

    /** Spacing between artwork and info in FullPlayerScreen */
    val playerArtworkSpacing: Dp
        @Composable
        get() {
            val windowSize = LocalWindowSizeClass.current
            return when (windowSize.heightSizeClass) {
                WindowHeightSizeClass.Compact -> 12.dp
                WindowHeightSizeClass.Medium -> 24.dp
                else -> 36.dp
            }
        }
}
