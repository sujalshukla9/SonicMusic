package com.sonicmusic.app.domain.model

/**
 * Rich metadata about the currently playing audio stream.
 * 
 * Inspired by Apple Music-style quality badges.
 * Tracks codec, bitrate, sample rate, bit depth, and quality tier.
 */
data class AudioStreamInfo(
    val codec: String = "Unknown",
    val bitrate: Int = 0,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val qualityTier: StreamQuality = StreamQuality.HIGH,
    val containerFormat: String = "",
    val channelCount: Int = 2,
    val isEnhanced: Boolean = false,
    val isLocal: Boolean = false,
    /**
     * True only when the selected InnerTube playback rendition has explicit
     * Dolby Atmos/JOC metadata. Generic Dolby, multichannel, AC-4, and spatial
     * flags are insufficient. This does not indicate device capability.
     */
    val hasDolbyAtmos: Boolean = false,
) {
    /**
     * Whether this stream qualifies as high-res
     */
    val isHighRes: Boolean
        get() = qualityTier.isHighRes || bitrate >= 256 || sampleRate > 44100

    /**
     * Human-readable quality badge like Apple Music shows
     * e.g., "AAC 256", "AAC", "Hi-Res"
     */
    val qualityBadge: String
        get() = when {
            isEnhanced && isHighRes -> "Enhanced · Hi-Res"
            isEnhanced -> "Enhanced"
            // AAC at 256kbps (our primary best quality)
            (codec.equals("AAC", true) || codec.equals(" AAC-LC", true)) && bitrate >= 256 -> " AAC 256"
            codec.equals("AAC-HE", true) -> " HE-AAC"
            codec.equals("AAC", true) || codec.equals(" AAC-LC", true) -> " AAC"
            // OPUS only shown when it's actually selected (last resort)
            codec.equals("OPUS", true) && bitrate >= 256 -> " Hi-Res"
            codec.equals("OPUS", true) -> " OPUS"
            else -> codec.uppercase()
        }


    /**
     * Full quality description for settings/info display
     * e.g., "OPUS · 256 kbps · 48 kHz · 16-bit"
     */
    val fullDescription: String
        get() {
            val parts = mutableListOf<String>()
            parts.add(codec.uppercase())
            if (bitrate > 0) parts.add("${bitrate} kbps")
            parts.add("${sampleRate / 1000f}".removeSuffix(".0") + " kHz")
            parts.add("${bitDepth}-bit")
            return parts.joinToString(" · ")
        }

    /**
     * Compact signal path description
     * e.g., "OPUS 256kbps → AudioEngine → DAC"
     */
    fun getSignalPath(outputDevice: String = "Speaker"): String {
        val source = "${codec.uppercase()} ${bitrate}kbps"
        return "$source → AudioEngine → $outputDevice"
    }

    companion object {
        /**
         * Detect codec from MIME type
         */
        fun codecFromMimeType(mimeType: String): String = when {
            mimeType.contains("opus", true) -> " OPUS"
            mimeType.contains("mp4a.40.2", true) -> " AAC-LC"
            mimeType.contains("mp4a.40.5", true) -> " AAC-HE"
            mimeType.contains("mp4a", true) -> " AAC"
            mimeType.contains("vorbis", true) -> " Vorbis"
            mimeType.contains("flac", true) -> " FLAC"
            else -> "Unknown"
        }

        /**
         * Detect container format from MIME type
         */
        fun containerFromMimeType(mimeType: String): String = when {
            mimeType.contains("webm", true) -> " WebM"
            mimeType.contains("mp4", true) -> " M4A"
            mimeType.contains("ogg", true) -> " OGG"
            else -> "Unknown"
        }

        /**
         * Determine quality tier from stream parameters.
         *
         * AAC ≥256kbps → BEST  (our primary quality target)
         * AAC ≥128kbps → HIGH
         * OPUS ≥256kbps → HIGH (high-quality but not our preferred codec)
         * others        → MEDIUM / LOW by bitrate
         */
        fun qualityTierFromStream(codec: String, bitrate: Int): StreamQuality = when {
            codec.equals("AAC-LC", true) && bitrate >= 256 -> StreamQuality.BEST
            codec.equals("AAC", true) && bitrate >= 256 -> StreamQuality.BEST
            codec.equals("AAC-LC", true) && bitrate >= 128 -> StreamQuality.HIGH
            codec.equals("AAC", true) && bitrate >= 128 -> StreamQuality.HIGH
            codec.equals("AAC-HE", true) && bitrate >= 64 -> StreamQuality.HIGH
            codec.equals("OPUS", true) && bitrate >= 256 -> StreamQuality.HIGH
            bitrate >= 256 -> StreamQuality.HIGH
            bitrate >= 128 -> StreamQuality.MEDIUM
            else -> StreamQuality.LOW
        }


        /**
         * Estimate sample rate from itag
         * YouTube typically serves 48kHz for OPUS, 44.1kHz for AAC
         */
        fun sampleRateFromCodec(codec: String): Int = when {
            codec.equals("OPUS", true) -> 48000
            codec.equals("AAC-LC", true) -> 44100
            codec.equals("AAC-HE", true) -> 44100
            codec.equals("AAC", true) -> 44100
            else -> 44100
        }
    }
}
