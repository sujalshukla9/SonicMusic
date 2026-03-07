package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive dimension helpers that scale UI elements
 * based on the device's screen width and height.
 *
 * Breakpoints:
 *  - Compact:  < 360dp
 *  - Standard: 360–599dp
 *  - Medium:   600–839dp
 *  - Expanded: ≥ 840dp
 */
@Stable
object ResponsiveDimensions {

    // ── Cards ────────────────────────────────────────────────

    /** Width for medium song cards (Quick Picks, Recommendations). */
    fun cardWidth(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 120.dp
        screenWidthDp < 600  -> 140.dp
        screenWidthDp < 840  -> 160.dp
        else                 -> 180.dp
    }

    /** Width for large square cards (English Hits, Continue Listening). */
    fun largeCardWidth(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 136.dp
        screenWidthDp < 600  -> 160.dp
        screenWidthDp < 840  -> 180.dp
        else                 -> 200.dp
    }

    // ── Full Player ─────────────────────────────────────────

    /** Top spacing above artwork in FullPlayerScreen. */
    fun fullPlayerTopSpacing(screenHeightDp: Int): Dp {
        val raw = (screenHeightDp * 0.06f)
        return raw.coerceIn(20f, 80f).dp
    }

    /** Spacing between artwork and song-info in FullPlayerScreen. */
    fun fullPlayerArtworkSpacing(screenHeightDp: Int): Dp {
        val raw = (screenHeightDp * 0.035f)
        return raw.coerceIn(12f, 48f).dp
    }

    /** Horizontal padding around artwork in FullPlayerScreen. */
    fun fullPlayerArtworkPadding(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 20.dp
        screenWidthDp < 600  -> 30.dp
        else                 -> 40.dp
    }

    // ── Player Controls ─────────────────────────────────────

    /** Main play/pause button size. */
    fun playButtonSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 64.dp
        screenWidthDp < 600  -> 80.dp
        else                 -> 88.dp
    }

    /** Play icon size inside the play button. */
    fun playIconSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 32.dp
        screenWidthDp < 600  -> 40.dp
        else                 -> 44.dp
    }

    /** Skip prev / skip next button size. */
    fun skipButtonSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 44.dp
        screenWidthDp < 600  -> 56.dp
        else                 -> 60.dp
    }

    /** Icon size inside skip buttons. */
    fun skipIconSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 22.dp
        screenWidthDp < 600  -> 28.dp
        else                 -> 32.dp
    }

    /** Like / Repeat button size. */
    fun secondaryButtonSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 40.dp
        screenWidthDp < 600  -> 48.dp
        else                 -> 52.dp
    }

    // ── Mini Player ─────────────────────────────────────────

    /** MiniPlayer preferred height. */
    fun miniPlayerHeight(screenHeightDp: Int): Dp {
        val raw = (screenHeightDp * 0.095f)
        return raw.coerceIn(64f, 80f).dp
    }

    /** MiniPlayer album art size. */
    fun miniPlayerArtSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 44.dp
        else                 -> 52.dp
    }

    // ── Continue Listening Card ─────────────────────────────

    /** Height for the ContinueListeningCard. */
    fun continueListeningCardHeight(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 84.dp
        screenWidthDp < 600  -> 100.dp
        else                 -> 110.dp
    }

    /** Thumbnail size inside the ContinueListeningCard. */
    fun continueListeningThumbSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 60.dp
        screenWidthDp < 600  -> 72.dp
        else                 -> 80.dp
    }

    // ── Section Headers ─────────────────────────────────────

    /** Icon button size inside section headers (shuffle, play, etc.) */
    fun sectionIconButtonSize(screenWidthDp: Int): Dp = when {
        screenWidthDp < 360  -> 30.dp
        else                 -> 36.dp
    }
}

/**
 * Convenience composable that reads screen dimensions once
 * and returns them as a [ScreenDimensions] data class.
 */
@Composable
fun rememberScreenDimensions(): ScreenDimensions {
    val config = LocalConfiguration.current
    return ScreenDimensions(
        widthDp = config.screenWidthDp,
        heightDp = config.screenHeightDp
    )
}

@Stable
data class ScreenDimensions(
    val widthDp: Int,
    val heightDp: Int
)
