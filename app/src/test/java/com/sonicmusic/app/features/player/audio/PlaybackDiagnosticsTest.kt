package com.sonicmusic.app.features.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {

    private val diagnostics = PlaybackDiagnostics()

    @Test
    fun `stale stream info is cleared immediately when song changes`() {
        diagnostics.onSongChanged("song_A")
        val candidateA = AudioStreamCandidate(
            url = "https://example.com/songA",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true
        )
        diagnostics.onCandidateSelected("song_A", candidateA)

        val stateA = diagnostics.uiState.value
        assertNotNull(stateA)
        assertEquals("song_A", stateA!!.songId)
        assertEquals("OPUS", stateA.codec)
        assertEquals(160, stateA.sourceBitrateKbps)

        // User switches to song_B
        diagnostics.onSongChanged("song_B")

        val stateB = diagnostics.uiState.value
        assertNotNull(stateB)
        assertEquals("song_B", stateB!!.songId)
        // Candidate metadata from song_A must not leak into song_B
        assertEquals(PlaybackStatus.PREPARING, stateB.playbackStatus)
        assertNull(stateB.sourceBitrateKbps)
        assertNull(stateB.sampleRateHz)
    }

    @Test
    fun `Media3 playing format update captures decoder and output without overwriting source metadata`() {
        diagnostics.onSongChanged("song_1")
        val candidate = AudioStreamCandidate(
            url = "https://example.com/song1",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 160,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true
        )
        diagnostics.onCandidateSelected("song_1", candidate)

        // ExoPlayer decoder initializes
        diagnostics.onDecoderInitialized("c2.android.opus.decoder")

        // ExoPlayer reports actual playback format
        val media3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_OPUS)
            .setSampleRate(48000)
            .setChannelCount(2)
            .setAverageBitrate(158000)
            .build()
        diagnostics.onPlayingFormatChanged(media3Format)

        val state = diagnostics.uiState.value
        assertNotNull(state)
        assertEquals("c2.android.opus.decoder", state!!.decoderName)
        assertEquals(160, state.sourceBitrateKbps) // Source reported bitrate preserved
        assertEquals(48000, state.playingSampleRate)
        assertEquals(2, state.playingChannelCount)
    }

    @Test
    fun `truthful formatting shows Bitrate unavailable when source bitrate is absent`() {
        diagnostics.onSongChanged("song_no_br")
        val candidate = AudioStreamCandidate(
            url = "https://example.com/stream",
            mimeType = "audio/webm",
            codec = "OPUS",
            container = "WebM",
            bitrateKbps = 0, // Absent
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true
        )
        diagnostics.onCandidateSelected("song_no_br", candidate)

        val state = diagnostics.uiState.value
        assertNotNull(state)
        assertEquals("OPUS · Bitrate unavailable · 48 kHz · Stereo", state!!.formattedDescription)
    }

    @Test
    fun `truthful formatting shows full description when source bitrate is present`() {
        diagnostics.onSongChanged("song_with_br")
        val candidate = AudioStreamCandidate(
            url = "https://example.com/stream",
            mimeType = "audio/mp4",
            codec = "AAC",
            container = "M4A",
            bitrateKbps = 256,
            sampleRate = 48000,
            channelCount = 2,
            isAudioOnly = true
        )
        diagnostics.onCandidateSelected("song_with_br", candidate)

        val state = diagnostics.uiState.value
        assertNotNull(state)
        assertEquals("AAC · 256 kbps · 48 kHz · Stereo", state!!.formattedDescription)
    }

    @Test
    fun `playback status transitions accurately reflect recovering and error states`() {
        diagnostics.onSongChanged("song_err")

        diagnostics.onPlaybackStatusChanged(PlaybackStatus.RECOVERING, "Refreshing expired stream...")
        assertEquals(PlaybackStatus.RECOVERING, diagnostics.uiState.value?.playbackStatus)
        assertEquals("Refreshing expired stream...", diagnostics.uiState.value?.statusMessage)

        diagnostics.onPlaybackStatusChanged(PlaybackStatus.ERROR, "Network timeout")
        assertEquals(PlaybackStatus.ERROR, diagnostics.uiState.value?.playbackStatus)
        assertEquals("Network timeout", diagnostics.uiState.value?.statusMessage)
    }

    @Test
    fun `local playback correctly marks state as local without phantom source network name`() {
        diagnostics.onSongChanged("local_song_1", isLocal = true)
        val localInfo = AudioStreamInfo(
            codec = "FLAC",
            bitrate = 850,
            sampleRate = 44100,
            channelCount = 2,
            isLocal = true,
            containerFormat = "FLAC"
        )
        diagnostics.onCandidateSelected("local_song_1", localInfo)

        val state = diagnostics.uiState.value
        assertNotNull(state)
        assertTrue(state!!.isLocal)
        assertEquals("FLAC", state.codec)
        assertEquals(850, state.sourceBitrateKbps)
    }
}
