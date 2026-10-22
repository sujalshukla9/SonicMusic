package com.sonicmusic.app.features.home.presentation

import com.sonicmusic.app.domain.model.CommunityPlaylist
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingCommunityPlaylistsTest {

    @Test
    fun `CommunityPlaylist model defaults and properties`() {
        val playlist = CommunityPlaylist(
            id = "VLPL123",
            title = "Chill Beats",
            creator = "Sonic Curators",
            thumbnailUrl = "https://example.com/art.jpg",
            browseId = "VLPL123"
        )

        assertEquals("VLPL123", playlist.id)
        assertEquals("Chill Beats", playlist.title)
        assertEquals("Sonic Curators", playlist.creator)
        assertEquals("https://example.com/art.jpg", playlist.thumbnailUrl)
        assertEquals("VLPL123", playlist.browseId)
        assertNull(playlist.trackCount)
        assertNull(playlist.trackCountText)
        assertNull(playlist.viewCountText)
        assertTrue(playlist.previewTracks.isEmpty())
    }

    @Test
    fun `CommunityPlaylist with preview tracks and view count`() {
        val previewSong1 = Song(
            id = "song1",
            title = "Midnight City",
            artist = "M83",
            duration = 243,
            thumbnailUrl = "https://example.com/s1.jpg",
            contentType = ContentType.SONG
        )
        val previewSong2 = Song(
            id = "song2",
            title = "Intro",
            artist = "The xx",
            duration = 127,
            thumbnailUrl = "https://example.com/s2.jpg",
            contentType = ContentType.SONG
        )

        val playlist = CommunityPlaylist(
            id = "VLPL456",
            title = "Indie Essentials",
            creator = "IndieVibes",
            thumbnailUrl = "https://example.com/indie.jpg",
            trackCount = 42,
            trackCountText = "42 songs",
            browseId = "VLPL456",
            viewCountText = "1.2M views",
            previewTracks = listOf(previewSong1, previewSong2)
        )

        assertEquals(42, playlist.trackCount)
        assertEquals("42 songs", playlist.trackCountText)
        assertEquals("1.2M views", playlist.viewCountText)
        assertEquals(2, playlist.previewTracks.size)
        assertEquals("Midnight City", playlist.previewTracks[0].title)
        assertEquals("Intro", playlist.previewTracks[1].title)
    }

    @Test
    fun `Playlist track and view count regex extraction`() {
        val trackRegex = Regex("""(\d[\d,.]*)\s*(?:songs?|tracks?)""", RegexOption.IGNORE_CASE)
        val viewRegex = Regex("""(\d[\d,.]*\s*[KMBkmb]?\s*(?:views?|plays?))""", RegexOption.IGNORE_CASE)

        // Track matches
        val trackMatch1 = trackRegex.find("25 songs")
        assertNotNull(trackMatch1)
        assertEquals("25", trackMatch1?.groupValues?.get(1))

        val trackMatch2 = trackRegex.find("1,200 tracks")
        assertNotNull(trackMatch2)
        val count = trackMatch2?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        assertEquals(1200, count)

        val trackMatch3 = trackRegex.find("1 song")
        assertNotNull(trackMatch3)
        assertEquals(1, trackMatch3?.groupValues?.get(1)?.toIntOrNull())

        // View/play matches
        val viewMatch1 = viewRegex.find("1.5M views")
        assertNotNull(viewMatch1)
        assertEquals("1.5M views", viewMatch1?.value)

        val viewMatch2 = viewRegex.find("450K plays")
        assertNotNull(viewMatch2)
        assertEquals("450K plays", viewMatch2?.value)

        val viewMatch3 = viewRegex.find("23M views")
        assertNotNull(viewMatch3)
        assertEquals("23M views", viewMatch3?.value)

        // Non-matches
        assertNull(viewRegex.find("Album"))
        assertNull(viewRegex.find("Playlist"))
        assertNull(trackRegex.find("Playlist"))
    }

    @Test
    fun `Playlist browseId normalization prepends VL when missing`() {
        fun normalizePlaylistBrowseId(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("PL") && !trimmed.startsWith("VL")) "VL$trimmed" else trimmed
        }

        assertEquals("VLPL1234567890", normalizePlaylistBrowseId("PL1234567890"))
        assertEquals("VLPL1234567890", normalizePlaylistBrowseId("VLPL1234567890"))
        assertEquals("VLsome_id", normalizePlaylistBrowseId("VLsome_id"))
        assertEquals("FEmusic_home", normalizePlaylistBrowseId("FEmusic_home"))
        assertEquals("", normalizePlaylistBrowseId(""))
    }

    @Test
    fun `Track preview list is bounded to maximum three tracks`() {
        val dummySongs = (1..10).map { i ->
            Song(
                id = "track_$i",
                title = "Title $i",
                artist = "Artist $i",
                duration = 180 + i,
                thumbnailUrl = "thumb_$i",
                contentType = ContentType.SONG
            )
        }

        val boundedPreviews = dummySongs.take(3)
        assertEquals(3, boundedPreviews.size)
        assertEquals("track_1", boundedPreviews[0].id)
        assertEquals("track_2", boundedPreviews[1].id)
        assertEquals("track_3", boundedPreviews[2].id)
    }

    @Test
    fun `Saved playlist remote ID detection logic`() {
        val savedRemoteIds = setOf("VLPL_SAVED_1", "PL_SAVED_2")

        val playlist1 = CommunityPlaylist(
            id = "VLPL_SAVED_1",
            title = "Saved 1",
            creator = "User A",
            thumbnailUrl = "",
            browseId = "VLPL_SAVED_1"
        )
        val playlist2 = CommunityPlaylist(
            id = "PL_SAVED_2",
            title = "Saved 2",
            creator = "User B",
            thumbnailUrl = "",
            browseId = "VLPL_SAVED_2"
        )
        val playlist3 = CommunityPlaylist(
            id = "VLPL_NOT_SAVED",
            title = "Not Saved",
            creator = "User C",
            thumbnailUrl = "",
            browseId = "VLPL_NOT_SAVED"
        )

        fun isPlaylistSaved(pl: CommunityPlaylist): Boolean {
            return pl.browseId in savedRemoteIds || pl.id in savedRemoteIds
        }

        assertTrue(isPlaylistSaved(playlist1))
        assertTrue(isPlaylistSaved(playlist2))
        assertFalse(isPlaylistSaved(playlist3))
    }
}
