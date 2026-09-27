package com.sonicmusic.app.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.windowsizeclass.WindowSizeClass

/**
 * CompositionLocal key for providing [WindowSizeClass] down the tree.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass> {
    error("No WindowSizeClass provided. Ensure calculateWindowSizeClass is called and provided at root.")
}
