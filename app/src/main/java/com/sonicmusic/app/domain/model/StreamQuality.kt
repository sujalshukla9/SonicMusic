package com.sonicmusic.app.domain.model

enum class StreamQuality(val bitrate: Int, val displayName: String) {
    LOW(64, "Low (64 kbps)"),
    MEDIUM(128, "Medium (128 kbps)"),
    HIGH(192, "High (192 kbps)"),
    BEST(256, "Best (256 kbps)");

    companion object {
        fun fromBitrate(bitrate: Int): StreamQuality {
            return entries.find { it.bitrate == bitrate } ?: HIGH
        }
    }
}