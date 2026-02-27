package com.sonicmusic.app.domain.model

enum class DarkMode {
    LIGHT, DARK, SYSTEM;

    companion object {
        fun fromString(value: String): DarkMode {
            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                SYSTEM
            }
        }
    }
}
