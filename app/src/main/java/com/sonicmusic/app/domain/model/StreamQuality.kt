package com.sonicmusic.app.domain.model

/**
 * Audio Quality Tiers
 *
 * The audio pipeline selects the highest fidelity compatible stream
 * available from the source (e.g. OPUS ~160 kbps 48 kHz or AAC 256 kbps):
 *
 * - LOW:    Data-saver mode — ~50–64 kbps (Opus/AAC data saver)
 * - MEDIUM: Balanced — ~128 kbps (optimized for cellular)
 * - HIGH:   High Quality — ~160–256 kbps
 * - BEST:   Best Available — Original source stream quality (OPUS 160 / AAC 256)
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
        description = "Lower bitrate · Saves mobile data",
        preferredCodec = "any",
    ),
    MEDIUM(
        bitrate = 128,
        displayName = "High Efficiency",
        description = "128 kbps · Optimized for cellular",
        preferredCodec = "any",
    ),
    HIGH(
        bitrate = 160,
        displayName = "High Quality",
        description = "160–256 kbps · CD-comparable fidelity",
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
