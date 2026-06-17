package com.sonicmusic.app.domain.model

enum class PlayerControlStyle {
    CLASSIC,
    MODERN;

    companion object {
        fun fromString(value: String?): PlayerControlStyle {
            return try {
                value?.let { valueOf(it) } ?: CLASSIC
            } catch (_: Exception) {
                CLASSIC
            }
        }
    }
}
