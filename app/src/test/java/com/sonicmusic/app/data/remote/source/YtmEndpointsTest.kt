package com.sonicmusic.app.data.remote.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtmEndpointsTest {

    @Test
    fun `permitted static browseIds are recognized`() {
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_home"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_charts"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_moods_and_genres"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_new_releases_albums"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_new_releases"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_liked_playlists"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_library_landing"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("FEmusic_history"))
    }

    @Test
    fun `valid dynamic prefixes are recognized`() {
        assertTrue(YtmBrowseId.isAllowedBrowseId("MPREb_abc123"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("OLAK5uy_def456"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("UC1234567890abcdef"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("VLPL123456"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("PL123456"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("RDCLAK5uy_xyz"))
        assertTrue(YtmBrowseId.isAllowedBrowseId("RDAMVM123"))
    }

    @Test
    fun `non-YTM browseIds are rejected`() {
        assertFalse(YtmBrowseId.isAllowedBrowseId("FEwhat_to_watch"))
        assertFalse(YtmBrowseId.isAllowedBrowseId("FEsubscriptions"))
        assertFalse(YtmBrowseId.isAllowedBrowseId("FEtrending"))
        assertFalse(YtmBrowseId.isAllowedBrowseId("random_non_ytm_id"))
        assertFalse(YtmBrowseId.isAllowedBrowseId(""))
    }

    @Test
    fun `YtmBrowseId factory constructs correct types`() {
        assertEquals(YtmBrowseId.Home, YtmBrowseId.from("FEmusic_home"))
        assertEquals(YtmBrowseId.Charts, YtmBrowseId.from("FEmusic_charts"))
        assertEquals(YtmBrowseId.Album("MPREb_test"), YtmBrowseId.from("MPREb_test"))
        assertEquals(YtmBrowseId.Artist("UC123"), YtmBrowseId.from("UC123"))
        assertEquals(YtmBrowseId.Playlist("PL123"), YtmBrowseId.from("PL123"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `YtmBrowseId Album rejects invalid prefix`() {
        YtmBrowseId.Album("INVALID_ALBUM_ID")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `YtmBrowseId Artist rejects non UC prefix`() {
        YtmBrowseId.Artist("INVALID_ARTIST_ID")
    }
}
