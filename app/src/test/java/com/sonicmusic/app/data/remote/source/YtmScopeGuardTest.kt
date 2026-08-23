package com.sonicmusic.app.data.remote.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtmScopeGuardTest {

    @Test
    fun `checkVideoType returns true for official ATV and OMV`() {
        assertEquals(true, YtmScopeGuard.checkVideoType("MUSIC_VIDEO_TYPE_ATV"))
        assertEquals(true, YtmScopeGuard.checkVideoType("MUSIC_VIDEO_TYPE_OMV"))
        assertEquals(true, YtmScopeGuard.checkVideoType("  MUSIC_VIDEO_TYPE_ATV  "))
    }

    @Test
    fun `checkVideoType returns false for UGC and podcasts`() {
        assertEquals(false, YtmScopeGuard.checkVideoType("MUSIC_VIDEO_TYPE_UGC"))
        assertEquals(false, YtmScopeGuard.checkVideoType("MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))
        assertEquals(false, YtmScopeGuard.checkVideoType("OTHER_TYPE"))
    }

    @Test
    fun `checkVideoType returns null when videoType is missing or unknown`() {
        assertNull(YtmScopeGuard.checkVideoType(null))
        assertNull(YtmScopeGuard.checkVideoType(""))
        assertNull(YtmScopeGuard.checkVideoType("MUSIC_VIDEO_TYPE_UNKNOWN"))
    }

    @Test
    fun `hasValidArtistLink returns true for verified, topic, or channel linked artists`() {
        // Verified
        assertTrue(
            YtmScopeGuard.hasValidArtistLink(
                artistName = "Taylor Swift",
                artistId = null,
                channelId = null,
                isVerified = true,
                isOfficialArtist = false
            )
        )

        // Topic channel
        assertTrue(
            YtmScopeGuard.hasValidArtistLink(
                artistName = "Adele - Topic",
                artistId = null,
                channelId = null,
                isVerified = false,
                isOfficialArtist = false
            )
        )

        // VEVO channel
        assertTrue(
            YtmScopeGuard.hasValidArtistLink(
                artistName = "BillieEilishVEVO",
                artistId = null,
                channelId = null,
                isVerified = false,
                isOfficialArtist = false
            )
        )

        // UC channel ID
        assertTrue(
            YtmScopeGuard.hasValidArtistLink(
                artistName = "Artist Name",
                artistId = "UC1234567890",
                channelId = null,
                isVerified = false,
                isOfficialArtist = false
            )
        )
    }

    @Test
    fun `hasValidArtistLink returns false for unknown or unlinked empty artists`() {
        assertFalse(
            YtmScopeGuard.hasValidArtistLink(
                artistName = "Unknown Artist",
                artistId = null,
                channelId = null,
                isVerified = false,
                isOfficialArtist = false
            )
        )

        assertFalse(
            YtmScopeGuard.hasValidArtistLink(
                artistName = "",
                artistId = null,
                channelId = null,
                isVerified = false,
                isOfficialArtist = false
            )
        )
    }

    @Test
    fun `isTitleBlacklisted filters out UGC mashups, speed edits, and live bootlegs`() {
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Song Name (Slowed + Reverb)"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Artist - Hit Song (TikTok Remix)"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Top 10 Viral Songs Compilation"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Mega Mashup 2026"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Song (8D Audio)"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Lofi Remix for Studying"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Artist - Live Performance at Festival"))
        assertTrue(YtmScopeGuard.isTitleBlacklisted("Fanmade tribute cover"))
    }

    @Test
    fun `isTitleBlacklisted permits legitimate songs`() {
        assertFalse(YtmScopeGuard.isTitleBlacklisted("Blinding Lights"))
        assertFalse(YtmScopeGuard.isTitleBlacklisted("Starboy (feat. Daft Punk)"))
        assertFalse(YtmScopeGuard.isTitleBlacklisted("Shape of You (Official Audio)"))
        assertFalse(YtmScopeGuard.isTitleBlacklisted("Midnight City"))
    }

    @Test
    fun `deduplicateByVideoId removes duplicate items preserving order`() {
        val items = listOf(
            RawShelfItem(videoId = "vid1", title = "Song 1", artistName = "Artist A", thumbnailUrl = ""),
            RawShelfItem(videoId = "vid2", title = "Song 2", artistName = "Artist B", thumbnailUrl = ""),
            RawShelfItem(videoId = "vid1", title = "Song 1 Dupe", artistName = "Artist A", thumbnailUrl = ""),
            RawShelfItem(videoId = "vid3", title = "Song 3", artistName = "Artist C", thumbnailUrl = "")
        )

        val deduped = YtmScopeGuard.deduplicateByVideoId(items) { it.videoId }
        assertEquals(3, deduped.size)
        assertEquals("vid1", deduped[0].videoId)
        assertEquals("Song 1", deduped[0].title)
        assertEquals("vid2", deduped[1].videoId)
        assertEquals("vid3", deduped[2].videoId)
    }

    @Test
    fun `enforce full pipeline filters out UGC, invalid items, blacklisted titles, and duplicates`() {
        val rawBatch = listOf(
            // 1. Valid ATV official song -> KEEP
            RawShelfItem(
                videoId = "song1",
                title = "Blinding Lights",
                artistName = "The Weeknd",
                artistId = "UCxxx",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                thumbnailUrl = ""
            ),
            // 2. UGC upload -> REJECT
            RawShelfItem(
                videoId = "ugc1",
                title = "Random Vlog Background Music",
                artistName = "RandomUser",
                videoType = "MUSIC_VIDEO_TYPE_UGC",
                thumbnailUrl = ""
            ),
            // 3. Official Music Video -> KEEP
            RawShelfItem(
                videoId = "song2",
                title = "Starboy",
                artistName = "The Weeknd",
                videoType = "MUSIC_VIDEO_TYPE_OMV",
                thumbnailUrl = ""
            ),
            // 4. Blacklisted title (Slowed + Reverb) -> REJECT
            RawShelfItem(
                videoId = "song3",
                title = "Starboy (Slowed + Reverb)",
                artistName = "The Weeknd",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                thumbnailUrl = ""
            ),
            // 5. Missing videoType with verified artist link -> KEEP (fallback)
            RawShelfItem(
                videoId = "song4",
                title = "Save Your Tears",
                artistName = "The Weeknd - Topic",
                videoType = null,
                isVerified = true,
                thumbnailUrl = ""
            ),
            // 6. Duplicate of song1 -> REJECT (dedup)
            RawShelfItem(
                videoId = "song1",
                title = "Blinding Lights",
                artistName = "The Weeknd",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                thumbnailUrl = ""
            )
        )

        val clean = YtmScopeGuard.enforce(rawBatch)
        assertEquals(3, clean.size)
        assertEquals("song1", clean[0].videoId)
        assertEquals("song2", clean[1].videoId)
        assertEquals("song4", clean[2].videoId)
    }
}
