package com.sonicmusic.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamInfoTest {

    @Test
    fun `nominalBitrateForItag resolves standard YouTube and YouTube Music itags correctly`() {
        assertEquals(160, AudioStreamInfo.nominalBitrateForItag(251))
        assertEquals(256, AudioStreamInfo.nominalBitrateForItag(141))
        assertEquals(128, AudioStreamInfo.nominalBitrateForItag(140))
        assertEquals(70, AudioStreamInfo.nominalBitrateForItag(250))
        assertEquals(50, AudioStreamInfo.nominalBitrateForItag(249))
        assertEquals(32, AudioStreamInfo.nominalBitrateForItag(599))
        assertEquals(35, AudioStreamInfo.nominalBitrateForItag(600))
        assertEquals(256, AudioStreamInfo.nominalBitrateForItag(338))
        assertEquals(192, AudioStreamInfo.nominalBitrateForItag(22))
        assertEquals(96, AudioStreamInfo.nominalBitrateForItag(18))

        assertNull(AudioStreamInfo.nominalBitrateForItag(99999))
        assertNull(AudioStreamInfo.nominalBitrateForItag(null))
    }

    @Test
    fun `nominalSampleRateForItag resolves expected sampling frequencies for itags`() {
        assertEquals(48000, AudioStreamInfo.nominalSampleRateForItag(251))
        assertEquals(48000, AudioStreamInfo.nominalSampleRateForItag(250))
        assertEquals(48000, AudioStreamInfo.nominalSampleRateForItag(249))
        assertEquals(44100, AudioStreamInfo.nominalSampleRateForItag(140))
        assertEquals(44100, AudioStreamInfo.nominalSampleRateForItag(141))
        assertEquals(22050, AudioStreamInfo.nominalSampleRateForItag(599))
        assertEquals(48000, AudioStreamInfo.nominalSampleRateForItag(600))

        assertNull(AudioStreamInfo.nominalSampleRateForItag(88888))
        assertNull(AudioStreamInfo.nominalSampleRateForItag(null))
    }

    @Test
    fun `isAacHe detects High-Efficiency AAC via codec profile and codec name`() {
        val heAacViaProfile = AudioStreamCandidate(
            url = "https://example.com/stream",
            mimeType = "audio/mp4",
            codec = "AAC",
            codecProfile = "40.5",
            bitrateKbps = 64,
            isAudioOnly = true
        )
        assertTrue(heAacViaProfile.isAacHe())

        val heAacViaCodecName = AudioStreamCandidate(
            url = "https://example.com/stream",
            mimeType = "audio/mp4",
            codec = "HE-AAC",
            bitrateKbps = 64,
            isAudioOnly = true
        )
        assertTrue(heAacViaCodecName.isAacHe())

        val standardAac = AudioStreamCandidate(
            url = "https://example.com/stream",
            mimeType = "audio/mp4",
            codec = "AAC",
            codecProfile = "40.2",
            bitrateKbps = 128,
            isAudioOnly = true
        )
        assertFalse(standardAac.isAacHe())

        val opusCandidate = AudioStreamCandidate(
            url = "https://example.com/stream",
            mimeType = "audio/webm",
            codec = "OPUS",
            bitrateKbps = 160,
            isAudioOnly = true
        )
        assertFalse(opusCandidate.isAacHe())
    }

    @Test
    fun `distinct songs produce distinct and truthful quality badges according to their actual stream parameters`() {
        val songAStream = AudioStreamInfo(
            codec = "OPUS",
            bitrate = 160,
            sampleRate = 48000,
            itag = 251,
            containerFormat = "WebM"
        )
        val songBStream = AudioStreamInfo(
            codec = "AAC",
            bitrate = 128,
            sampleRate = 44100,
            itag = 140,
            containerFormat = "M4A"
        )
        val songCStream = AudioStreamInfo(
            codec = "AAC",
            bitrate = 256,
            sampleRate = 44100,
            itag = 141,
            containerFormat = "M4A"
        )
        val songDStream = AudioStreamInfo(
            codec = "OPUS",
            bitrate = 70,
            sampleRate = 48000,
            itag = 250,
            containerFormat = "WebM"
        )

        assertEquals("OPUS 160", songAStream.qualityBadge)
        assertEquals("AAC 128", songBStream.qualityBadge)
        assertEquals("AAC 256", songCStream.qualityBadge)
        assertEquals("OPUS 70", songDStream.qualityBadge)

        // Ensure none of them falsely claim to be identical or Hi-Res
        assertFalse(songAStream.qualityBadge == songBStream.qualityBadge)
        assertFalse(songBStream.qualityBadge == songCStream.qualityBadge)
        assertFalse(songAStream.isHighRes)
        assertFalse(songBStream.isHighRes)
        assertFalse(songCStream.isHighRes)
        assertFalse(songDStream.isHighRes)
    }
}
