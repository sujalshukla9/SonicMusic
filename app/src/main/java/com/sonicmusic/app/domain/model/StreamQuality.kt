package com.sonicmusic.app.domain.model

/**
 * Apple Music-Style Audio Quality Tiers
 * 
 * Modeled after Apple Music's quality hierarchy:
 * - DATA_SAVER: Minimal data usage
 * - HIGH_EFFICIENCY: AAC-HE, good quality at low bitrate
 * - HIGH_QUALITY: AAC 256kbps, Apple Music standard
 * - HIGH_RES: OPUS, near-lossless transparent quality
 * - HIGH_RES_LOSSLESS: Maximum available quality, OPUS at highest bitrate
 */
enum class StreamQuality(
    val bitrate: Int,
    val displayName: String,
    val description: String,
    val isHighRes: Boolean = false,
    val isLossless: Boolean = false,
    val preferredCodec: String = "any",
) {
    LOW(
        bitrate = 64,
        displayName = "Data Saver",
        description = "Lower quality, saves data",
        preferredCodec = "any",
    ),
    MEDIUM(
        bitrate = 128,
        displayName = "High Efficiency",
        description = "AAC 128 kbps · Optimized for cellular",
        preferredCodec = "aac",
    ),
    HIGH(
        bitrate = 256,
        displayName = "High Quality",
        description = "AAC 256 kbps · CD-comparable",
        preferredCodec = "aac",
    ),
    BEST(
        bitrate = 256,
        displayName = "High-Res",
        description = "OPUS 256 kbps · Transparent, near-lossless",
        isHighRes = true,
        preferredCodec = "opus",
    ),
    LOSSLESS(
        bitrate = 512,
        displayName = "High-Res Lossless",
        description = "OPUS max · Best available quality",
        isHighRes = true,
        isLossless = true,
        preferredCodec = "opus",
    );

    /**
     * Data usage multiplier relative to HIGH_QUALITY
     */
    val dataMultiplier: Float
        get() = when (this) {
            LOW -> 0.25f
            MEDIUM -> 0.5f
            HIGH -> 1.0f
            BEST -> 1.2f
            LOSSLESS -> 2.5f
        }

    /**
     * Short label for compact display (e.g., in Now Playing)
     */
    val shortLabel: String
        get() = when (this) {
            LOW -> "LOW"
            MEDIUM -> "AAC"
            HIGH -> "AAC"
            BEST -> "Hi-Res"
            LOSSLESS -> "Lossless"
        }

    companion object {
        fun fromBitrate(bitrate: Int): StreamQuality {
            return when {
                bitrate >= 512 -> LOSSLESS
                bitrate >= 256 -> BEST
                bitrate >= 192 -> HIGH
                bitrate >= 128 -> MEDIUM
                else -> LOW
            }
        }

        fun fromName(name: String): StreamQuality {
            return entries.find { it.name == name } ?: HIGH
        }
    }
}