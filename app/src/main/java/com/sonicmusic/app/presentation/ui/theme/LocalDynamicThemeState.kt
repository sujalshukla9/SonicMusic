package com.sonicmusic.app.presentation.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.sonicmusic.app.domain.model.ThemeMode

data class DynamicThemeState(
    val seedColor: Color,
    val isDark: Boolean,
    val themeMode: ThemeMode
)

val LocalDynamicThemeState = staticCompositionLocalOf {
    DynamicThemeState(
        seedColor = Color(0xFF4A7DFF),
        isDark = true,
        themeMode = ThemeMode.DEFAULT
    )
}
