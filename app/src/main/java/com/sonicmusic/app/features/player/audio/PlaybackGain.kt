package com.sonicmusic.app.features.player.audio

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.pow

@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackGain {
    private const val REPLAY_GAIN_TRACK = "REPLAYGAIN_TRACK_GAIN"
    private val replayGainValue = Regex("""([+-]?(?:\d+(?:\.\d+)?|\.\d+))\s*(?:dB)?""", RegexOption.IGNORE_CASE)

    fun replayGainLoudnessDb(metadata: Metadata?): Double? {
        if (metadata == null) return null
        for (index in 0 until metadata.length()) {
            val value = when (val tag = metadata[index]) {
                is VorbisComment -> tag.value.takeIf { tag.key.equals(REPLAY_GAIN_TRACK, ignoreCase = true) }
                is TextInformationFrame -> tag.values.firstOrNull()
                    .takeIf { tag.id == "TXXX" && tag.description.equals(REPLAY_GAIN_TRACK, ignoreCase = true) }
                else -> null
            } ?: continue
            val gain = replayGainValue.matchEntire(value.trim())?.groupValues?.get(1)?.toDoubleOrNull()
            if (gain != null && gain.isFinite()) return -gain
        }
        return null
    }

    fun normalization(loudnessDb: Double?): Float {
        val excess = loudnessDb?.takeIf(Double::isFinite)?.coerceIn(0.0, 24.0) ?: return 1f
        return 10.0.pow(-excess / 20.0).toFloat()
    }

    fun crossfade(outgoing: Float, incoming: Float): Pair<Float, Float> {
        val peak = maxOf(1f, outgoing + incoming)
        return outgoing / peak to incoming / peak
    }
}
