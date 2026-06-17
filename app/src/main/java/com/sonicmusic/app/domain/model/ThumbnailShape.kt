package com.sonicmusic.app.domain.model

enum class ThumbnailShape(val displayName: String) {
    SQUARE("Square"),
    ROUNDED_SQUARE("Rounded Square"),
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    COOKIE("Cookie"),
    CLOVER("Clover"),
    SCALLOP("Scallop"),
    BURST("Burst"),
    PILL("Pill"),
    ARCH("Arch");

    companion object {
        fun fromString(value: String?): ThumbnailShape {
            return entries.find { it.name == value } ?: ROUNDED_SQUARE
        }
    }
}
