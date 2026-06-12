package com.sonicmusic.app.domain.model

enum class ThemeMode {
    DEFAULT,
    DYNAMIC,
    MATERIAL_YOU,
    PURE_BLACK;

    companion object {
        fun fromString(value: String): ThemeMode {
            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                DYNAMIC
            }
        }
    }
}