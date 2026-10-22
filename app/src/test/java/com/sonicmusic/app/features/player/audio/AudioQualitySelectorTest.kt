package com.sonicmusic.app.features.player.audio

import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualitySelectorTest {

    private val selector = AudioQualitySelector()

    @Test
    fun `Opus 160kbps ranks above AAC 128kbps due to fullband frequency response`() {
        val opus160 = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=1",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true,
            sourceName = "NewPipe"
        )

        val aac128 = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=2",
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = 140,
            codec = "AAC",
            container = "M4A",
            bitrateKbps = 128,
            sampleRate = 44100,
            channelCount = 2,
            isAudioOnly = true,
            sourceName = "NewPipe"
        )

        val candidates = listOf(aac128, opus160)
        val selection = selector.selectBestCandidate(candidates, StreamQuality.BEST)

        assertNotNull(selection)
        assertEquals("OPUS", selection!!.candidate.codec)
        assertEquals(160, selection.candidate.bitrateKbps)
        assertEquals(251, selection.candidate.itag)
    }

    @Test
    fun `AAC 256kbps ranks above Opus 160kbps when high-bitrate AAC source is available`() {
        val opus160 = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=1",
            mimeType = "audio/webm; codecs=\"opus\"",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true,
            sourceName = "NewPipe"
        )

        val aac256 = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=3",
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            itag = 141,
            codec = "AAC",
            container = "M4A",
            bitrateKbps = 256,
            sampleRate = 44100,
            channelCount = 2,
            isAudioOnly = true,
            sourceName = "Innertube-IOS"
        )

        val candidates = listOf(opus160, aac256)
        val selection = selector.selectBestCandidate(candidates, StreamQuality.BEST)

        assertNotNull(selection)
        assertEquals("AAC", selection!!.candidate.codec)
        assertEquals(256, selection.candidate.bitrateKbps)
        assertEquals(141, selection.candidate.itag)
    }

    @Test
    fun `audio-only stream is preferred over muxed video-containing stream of equal bitrate`() {
        val audioOnly = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=audio",
            mimeType = "audio/mp4",
            itag = 140,
            codec = "AAC",
            container = "M4A",
            bitrateKbps = 128,
            sampleRate = 44100,
            channelCount = 2,
            isAudioOnly = true
        )

        val muxedVideo = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=video",
            mimeType = "video/mp4",
            itag = 18,
            codec = "AAC",
            container = "MP4",
            bitrateKbps = 128,
            sampleRate = 44100,
            channelCount = 2,
            isAudioOnly = false
        )

        val selection = selector.selectBestCandidate(listOf(muxedVideo, audioOnly), StreamQuality.BEST)
        assertNotNull(selection)
        assertTrue(selection!!.candidate.isAudioOnly)
        assertEquals("audio", selection.candidate.url.substringAfter("id="))
    }

    @Test
    fun `missing bitrate or sample-rate metadata is handled gracefully without crashing`() {
        val candidateWithoutBitrate = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=unknown",
            mimeType = "audio/webm",
            itag = null,
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 0,
            sampleRate = 0,
            channelCount = 0,
            isAudioOnly = true
        )

        val candidateWithMetadata = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=known",
            mimeType = "audio/webm",
            itag = 250,
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 70,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true
        )

        val selection = selector.selectBestCandidate(listOf(candidateWithoutBitrate, candidateWithMetadata), StreamQuality.BEST)
        assertNotNull(selection)
        // Candidate with verified higher bitrate and sample rate is chosen
        assertEquals("known", selection!!.candidate.url.substringAfter("id="))
    }

    @Test
    fun `blank or invalid URLs are eliminated from candidates`() {
        val blankUrlCandidate = AudioStreamCandidate(
            url = "   ",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            isAudioOnly = true
        )

        val validCandidate = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=9999999999&id=valid",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 128,
            isAudioOnly = true
        )

        val selection = selector.selectBestCandidate(listOf(blankUrlCandidate, validCandidate), StreamQuality.BEST)
        assertNotNull(selection)
        assertEquals("valid", selection!!.candidate.url.substringAfter("id="))
    }

    @Test
    fun `expired stream URLs are rejected when safety margin is exceeded`() {
        val pastTimestampMs = System.currentTimeMillis() - 100_000L
        val expiredCandidate = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=${pastTimestampMs / 1000}&id=expired",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            isAudioOnly = true,
            expiresAtMs = pastTimestampMs
        )

        assertTrue(expiredCandidate.isExpired())

        val futureTimestampMs = System.currentTimeMillis() + 3_600_000L
        val validCandidate = AudioStreamCandidate(
            url = "https://rr.googlevideo.com/videoplayback?expire=${futureTimestampMs / 1000}&id=fresh",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 128,
            isAudioOnly = true,
            expiresAtMs = futureTimestampMs
        )

        assertFalse(validCandidate.isExpired())

        val selection = selector.selectBestCandidate(listOf(expiredCandidate, validCandidate), StreamQuality.BEST)
        assertNotNull(selection)
        assertEquals("fresh", selection!!.candidate.url.substringAfter("id="))
    }

    @Test
    fun `selectFallbackCandidate excludes failed URLs and returns next best stream`() {
        val candidate1 = AudioStreamCandidate(
            url = "https://stream1.example.com/failed",
            mimeType = "audio/webm",
            itag = 251,
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true
        )

        val candidate2 = AudioStreamCandidate(
            url = "https://stream2.example.com/fallback",
            mimeType = "audio/mp4",
            itag = 140,
            codec = "AAC",
            container = "M4A",
            bitrateKbps = 128,
            sampleRate = 44100,
            channelCount = 2,
            isAudioOnly = true
        )

        val candidates = listOf(candidate1, candidate2)

        // Exclude the failed URL (candidate1)
        val fallback = selector.selectFallbackCandidate(
            candidates = candidates,
            failedUrls = setOf("https://stream1.example.com/failed"),
            requestedQuality = StreamQuality.BEST
        )

        assertNotNull(fallback)
        assertEquals("https://stream2.example.com/fallback", fallback!!.url)
        assertEquals("AAC", fallback.codec)
    }

    @Test
    fun `empty candidate list returns null safely without exception`() {
        val result = selector.selectBestCandidate(emptyList(), StreamQuality.BEST)
        assertNull(result)
    }

    @Test
    fun `extractExpiryFromUrl correctly extracts YouTube expire epoch timestamp`() {
        val url = "https://rr2---sn-4g5edn6s.googlevideo.com/videoplayback?expire=1776260840&ei=test&ip=1.1.1.1"
        val expiryMs = AudioStreamCandidate.extractExpiryFromUrl(url)

        assertNotNull(expiryMs)
        assertEquals(1776260840_000L, expiryMs)
    }
}
