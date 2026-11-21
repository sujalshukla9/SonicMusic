package com.sonicmusic.app.domain.model

/**
 * Audio Quality Tiers
 *
 * The audio pipeline selects the highest fidelity compatible stream
 * available from the source in High Quality and Best Available modes:
 *
 * - LOW:    Data-saver mode — ~50–64 kbps (Opus/AAC data saver)
 * - MEDIUM: Balanced — ~128 kbps (optimized for cellular)
 * - HIGH:   High Quality — highest compatible quality
 * - BEST:   Best Available — highest compatible quality
 */
enum class StreamQuality(
    val bitrate: Int,
    val displayName: String,
    val description: String,
    val isHighRes: Boolean = false,
    val preferredCodec: String = "best",
) {
    LOW(
        bitrate = 64,
        displayName = "Data Saver",
        description = "Prefer lower-bitrate audio to save data",
        preferredCodec = "any",
    ),
    MEDIUM(
        bitrate = 128,
        displayName = "High Efficiency",
        description = "Prefer audio around 128 kbps",
        preferredCodec = "any",
    ),
    HIGH(
        bitrate = 160,
        displayName = "High Quality",
        description = "Highest compatible quality available",
        preferredCodec = "best",
    ),
    BEST(
        bitrate = 256,
        displayName = "Best Available",
        description = "Highest available original audio stream",
        isHighRes = false,
        preferredCodec = "best",
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
            MEDIUM -> "MED"
            HIGH -> "HIGH"
            BEST -> "BEST"
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
