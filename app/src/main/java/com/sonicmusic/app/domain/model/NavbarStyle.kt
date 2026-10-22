package com.sonicmusic.app.domain.model

enum class NavbarStyle {
    MODERN,
    CLASSICAL;

    companion object {
        fun fromString(value: String?): NavbarStyle {
            return try {
                value?.let { valueOf(it) } ?: MODERN
            } catch (_: Exception) {
                MODERN
            }
        }
    }
}
