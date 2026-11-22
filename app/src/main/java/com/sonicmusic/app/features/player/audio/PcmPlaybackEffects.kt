package com.sonicmusic.app.features.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/** Playback effects for decoded PCM16. Both default to passing through the original PCM. */
@OptIn(UnstableApi::class)
internal class PcmPlaybackEffects(private val onFormatChanged: () -> Unit) : BaseAudioProcessor() {
    @Volatile var spatialEnabled = false
    @Volatile var spatialStrength = 0f
    @Volatile var estimateLoudness = false
    @Volatile var trackKey: Any? = null
    @Volatile var hasStereoPcm = false
        private set

    private var previousTrack: Any? = null
    private var meanSquare = 0.0
    private var gain = 1.0
    private var spatialMix = 0f
    private var lowLeft = 0f
    private var lowRight = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        inputAudioFormat.takeIf { it.encoding == C.ENCODING_PCM_16BIT } ?: AudioFormat.NOT_SET

    override fun onFlush() {
        lowLeft = 0f
        lowRight = 0f
        setStereoAvailable(inputAudioFormat.channelCount == 2 && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
    }

    override fun onReset() {
        previousTrack = null
        meanSquare = 0.0
        gain = 1.0
        spatialMix = 0f
        setStereoAvailable(false)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return

        val key = trackKey
        if (key != previousTrack) {
            previousTrack = key
            meanSquare = 0.0
            gain = 1.0
            lowLeft = 0f
            lowRight = 0f
        }

        val frames = bytes / (2 * channels)
        val seconds = frames.toDouble() / inputAudioFormat.sampleRate
        val targetGain = if (estimateLoudness) measureGain(inputBuffer, seconds) else 1.0
        val targetSpatial = if (hasStereoPcm && spatialEnabled) spatialStrength.coerceIn(0f, 1f) * MAX_CROSSFEED else 0f
        val output = replaceOutputBuffer(bytes)
        if (gain == 1.0 && targetGain == 1.0 && spatialMix == 0f && targetSpatial == 0f) {
            output.put(inputBuffer)
        } else {
            val gainStep = (targetGain - gain) / frames.coerceAtLeast(1)
            val spatialStep = (targetSpatial - spatialMix) / frames.coerceAtLeast(1)
            val filterStep = (1.0 - exp(-2.0 * Math.PI * 650 / inputAudioFormat.sampleRate)).toFloat()
            repeat(frames) {
                gain += gainStep
                spatialMix += spatialStep
                if (hasStereoPcm) {
                    val left = inputBuffer.short.toFloat()
                    val right = inputBuffer.short.toFloat()
                    output.putShort(((left * (1f - spatialMix) + lowRight * spatialMix) * gain).toInt().toShort())
                    output.putShort(((right * (1f - spatialMix) + lowLeft * spatialMix) * gain).toInt().toShort())
                    lowLeft += (left - lowLeft) * filterStep
                    lowRight += (right - lowRight) * filterStep
                } else {
                    repeat(channels) { output.putShort((inputBuffer.short * gain).toInt().toShort()) }
                }
            }
            output.put(inputBuffer)
        }
        gain = targetGain
        spatialMix = targetSpatial
        output.flip()
    }

    private fun measureGain(buffer: ByteBuffer, seconds: Double): Double {
        var squares = 0.0
        for (index in buffer.position() until buffer.limit() - 1 step 2) {
            val sample = buffer.getShort(index) / 32768.0
            squares += sample * sample
        }
        val blockPower = squares / (buffer.remaining() / 2).coerceAtLeast(1)
        if (blockPower < SILENCE_POWER) return gain
        meanSquare += (blockPower - meanSquare) * (1.0 - exp(-seconds / 0.8))
        return (TARGET_RMS / sqrt(meanSquare)).coerceIn(MIN_GAIN, 1.0)
    }

    private fun setStereoAvailable(available: Boolean) {
        if (hasStereoPcm == available) return
        hasStereoPcm = available
        onFormatChanged()
    }

    private companion object {
        const val TARGET_RMS = 0.126 // Approximately −18 dBFS, for untagged content only.
        const val MIN_GAIN = 0.355 // Limit estimated attenuation to approximately 9 dB.
        const val SILENCE_POWER = 0.00001
        const val MAX_CROSSFEED = 0.16f
    }
}
