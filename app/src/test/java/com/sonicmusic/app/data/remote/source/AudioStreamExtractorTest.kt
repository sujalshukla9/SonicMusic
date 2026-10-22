package com.sonicmusic.app.data.remote.source

import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import okhttp3.OkHttpClient

class AudioStreamExtractorTest {

    private val newPipeService: NewPipeService = mock()
    private val okHttpClient = OkHttpClient()
    private val extractor = AudioStreamExtractor(newPipeService, okHttpClient)

    @Test
    fun `selectBestFormat selects Opus 48kHz over AAC 128kHz for BEST quality`() {
        val opus128 = AudioFormat(
            url = "https://stream.example.com/opus128",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrate = 132000L,
            bitrateKbps = 132,
            sampleRate = 48000,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            isAudioOnly = true,
        )

        val aac128 = AudioFormat(
            url = "https://stream.example.com/aac128",
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = 140,
            codec = "AAC-LC",
            container = "M4A",
            bitrate = 130000L,
            bitrateKbps = 130,
            sampleRate = 44100,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            isAudioOnly = true,
        )

        val lowOpus = AudioFormat(
            url = "https://stream.example.com/opus70",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 250,
            codec = "OPUS",
            container = "WebM",
            bitrate = 70000L,
            bitrateKbps = 70,
            sampleRate = 48000,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_LOW",
            isAudioOnly = true,
        )

        val candidates = listOf(lowOpus, aac128, opus128)
        val selection = extractor.selectBestFormat(candidates, StreamQuality.BEST)

        assertNotNull(selection)
        val (selected, rationale) = selection!!
        assertEquals("OPUS", selected.codec)
        assertEquals(251, selected.itag)
        assertEquals(132, selected.bitrateKbps)
        assertTrue(rationale.contains("highest fidelity"))
    }

    @Test
    fun `selectBestFormat selects AAC 256kbps over Opus 160kbps when available`() {
        val opus160 = AudioFormat(
            url = "https://stream.example.com/opus160",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrate = 160000L,
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            isAudioOnly = true,
        )

        val aac256 = AudioFormat(
            url = "https://stream.example.com/aac256",
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = 141,
            codec = "AAC-LC",
            container = "M4A",
            bitrate = 256000L,
            bitrateKbps = 256,
            sampleRate = 44100,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_HIGH",
            isAudioOnly = true,
        )

        val candidates = listOf(opus160, aac256)
        val selection = extractor.selectBestFormat(candidates, StreamQuality.BEST)

        assertNotNull(selection)
        val (selected, _) = selection!!
        assertEquals("AAC-LC", selected.codec)
        assertEquals(141, selected.itag)
        assertEquals(256, selected.bitrateKbps)
    }

    @Test
    fun `selectBestFormat prioritizes audio-only stream over muxed stream of similar quality`() {
        val muxedVideo = AudioFormat(
            url = "https://stream.example.com/video720p",
            mimeType = "video/mp4; codecs=\"avc1.4d401f, mp4a.40.2\"",
            itag = 22,
            codec = "AAC",
            container = "M4A",
            bitrate = 192000L,
            bitrateKbps = 192,
            sampleRate = 44100,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            isAudioOnly = false,
        )

        val audioOnlyOpus = AudioFormat(
            url = "https://stream.example.com/opus130",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrate = 130000L,
            bitrateKbps = 130,
            sampleRate = 48000,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            isAudioOnly = true,
        )

        val candidates = listOf(muxedVideo, audioOnlyOpus)
        val selection = extractor.selectBestFormat(candidates, StreamQuality.BEST)

        assertNotNull(selection)
        assertEquals(251, selection!!.first.itag)
        assertTrue(selection.first.isAudioOnly)
    }

    @Test
    fun `selectBestFormat falls back to muxed stream when audio-only stream is unavailable`() {
        val muxedFormat = AudioFormat(
            url = "https://stream.example.com/muxed",
            mimeType = "video/mp4; codecs=\"avc1.42001e, mp4a.40.2\"",
            itag = 18,
            codec = "AAC",
            container = "M4A",
            bitrate = 96000L,
            bitrateKbps = 96,
            sampleRate = 44100,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_LOW",
            isAudioOnly = false,
        )

        val candidates = listOf(muxedFormat)
        val selection = extractor.selectBestFormat(candidates, StreamQuality.BEST)

        assertNotNull(selection)
        assertEquals(18, selection!!.first.itag)
        assertFalse(selection.first.isAudioOnly)
    }

    @Test
    fun `selectBestFormat selects low-bitrate format when LOW data saver quality is requested`() {
        val highOpus = AudioFormat(
            url = "https://stream.example.com/opus160",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrate = 160000L,
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            isAudioOnly = true,
        )

        val lowOpus = AudioFormat(
            url = "https://stream.example.com/opus70",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 250,
            codec = "OPUS",
            container = "WebM",
            bitrate = 70000L,
            bitrateKbps = 70,
            sampleRate = 48000,
            channelCount = 2,
            audioQuality = "AUDIO_QUALITY_LOW",
            isAudioOnly = true,
        )

        val candidates = listOf(highOpus, lowOpus)
        val selection = extractor.selectBestFormat(candidates, StreamQuality.LOW)

        assertNotNull(selection)
        assertEquals(250, selection!!.first.itag)
    }

    @Test
    fun `AudioStreamInfo does not label lossy YouTube Opus or AAC streams as Hi-Res`() {
        val opusInfo = AudioStreamInfo(
            codec = "OPUS",
            bitrate = 160,
            sampleRate = 48000,
            bitDepth = 0,
            qualityTier = StreamQuality.BEST,
        )
        assertFalse(opusInfo.isHighRes)
        assertEquals("OPUS 160", opusInfo.qualityBadge)

        val aacInfo = AudioStreamInfo(
            codec = "AAC",
            bitrate = 256,
            sampleRate = 44100,
            bitDepth = 0,
            qualityTier = StreamQuality.BEST,
        )
        assertFalse(aacInfo.isHighRes)
        assertEquals("AAC 256", aacInfo.qualityBadge)
    }

    @Test
    fun `AudioStreamInfo accurately labels genuine lossless FLAC with high-res standard`() {
        val flacStandard = AudioStreamInfo(
            codec = "FLAC",
            bitrate = 850,
            sampleRate = 44100,
            bitDepth = 16,
            isLocal = true,
        )
        assertFalse(flacStandard.isHighRes)
        assertEquals("Local · FLAC", flacStandard.qualityBadge)

        val flacHiRes = AudioStreamInfo(
            codec = "FLAC",
            bitrate = 2400,
            sampleRate = 96000,
            bitDepth = 24,
            isLocal = true,
        )
        assertTrue(flacHiRes.isHighRes)
        assertEquals("Local · FLAC", flacHiRes.qualityBadge)
    }

    @Test
    fun `AudioStreamInfo fullDescription omits unknown sample rate and lossy bit depth`() {
        val streamInfo = AudioStreamInfo(
            codec = "OPUS",
            bitrate = 133,
            sampleRate = 48000,
            bitDepth = 0,
            channelCount = 2,
        )
        assertEquals("OPUS · 133 kbps · 48 kHz · Stereo", streamInfo.fullDescription)

        val unknownInfo = AudioStreamInfo(
            codec = "Unknown",
            bitrate = 0,
            sampleRate = 0,
            bitDepth = 0,
            channelCount = 0,
        )
        assertEquals("Audio Stream", unknownInfo.fullDescription)
    }
}
