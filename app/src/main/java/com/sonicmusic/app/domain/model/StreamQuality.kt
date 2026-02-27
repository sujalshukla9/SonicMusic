package com.sonicmusic.app.domain.model

/**
 * Audio Quality Tiers
 *
 * - DATA_SAVER: Minimal data usage
 * - HIGH_EFFICIENCY: AAC-HE, good quality at low bitrate
 * - HIGH_QUALITY: AAC 256kbps, standard quality
 * - BEST: OPUS at highest available bitrate (default)
 */
enum class StreamQuality(
    val bitrate: Int,
    val displayName: String,
    val description: String,
    val isHighRes: Boolean = false,
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
        displayName = "Very High (Opus)",
        description = "OPUS 256 kbps · Best available audio quality",
        isHighRes = true,
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
        }

    /**
     * Short label for compact display (e.g., in Now Playing)
     */
    val shortLabel: String
        get() = when (this) {
            LOW -> "LOW"
            MEDIUM -> "AAC"
            HIGH -> "AAC"
            BEST -> "OPUS"
        }

    companion object {
        fun fromBitrate(bitrate: Int): StreamQuality {
            return when {
                bitrate >= 256 -> BEST
                bitrate >= 192 -> HIGH
                bitrate >= 128 -> MEDIUM
                else -> LOW
            }
        }

        fun fromName(name: String): StreamQuality {
            // Legacy migration: LOSSLESS -> BEST
            if (name == "LOSSLESS") return BEST
            return entries.find { it.name == name } ?: BEST
        }
    }
}
