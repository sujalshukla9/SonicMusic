package com.sonicmusic.app.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedMotionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalArtworkVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedPlayerArtwork(songId: String?): Modifier {
    val sharedScope = LocalSharedMotionScope.current ?: return this
    val visibility = LocalArtworkVisibility.current ?: return this
    if (songId == null || shouldReduceMotion()) return this
    return with(sharedScope) {
        sharedElement(
            state = rememberSharedContentState(songId),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> MotionTokens.spatial() }
        )
    }
}
