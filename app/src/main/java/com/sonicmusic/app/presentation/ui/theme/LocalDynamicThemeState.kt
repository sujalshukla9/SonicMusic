package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.sonicmusic.app.domain.model.ThemeMode

data class DynamicThemeState(
    val seedColor: Color,
    val isDark: Boolean,
    val themeMode: ThemeMode
)

// compositionLocalOf (not static) — seedColor changes every song, so only
// composables that read this local should recompose, not the entire tree.
val LocalDynamicThemeState = compositionLocalOf {
    DynamicThemeState(
        seedColor = Color(0xFF4A7DFF),
        isDark = true,
        themeMode = ThemeMode.DEFAULT
    )
}
