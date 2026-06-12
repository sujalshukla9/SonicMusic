package com.sonicmusic.app.domain.model

enum class FullPlayerStyle {
    NORMAL,
    WAVY;

    companion object {
        fun fromString(value: String): FullPlayerStyle {
            return try {
                valueOf(value)
            } catch (_: IllegalArgumentException) {
                NORMAL
            }
        }
    }
}
