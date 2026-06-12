package com.sonicmusic.app.data.remote.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistExtractorTest {

    @Test
    fun testCategory1_SingleArtistWithPageType() {
        // [Song] [•] [The Weeknd ↗ARTIST] [•] [After Hours ↗ALBUM] [•] [2020]
        val runs = listOf(
            Run("Song "),
            Run(" • "),
            Run("The Weeknd", "UC...", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" • "),
            Run("After Hours", "MPRE...", "MUSIC_PAGE_TYPE_ALBUM"),
            Run(" • "),
            Run("2020")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("The Weeknd", result.displayName)
        assertEquals(1.0f, result.confidence)
        assertEquals(1, result.individuals.size)
        assertEquals("UC...", result.individuals[0].browseId)
    }

    @Test
    fun testCategory2_MultipleArtistsWithPageType() {
        // [Song] [•] [Drake ↗ARTIST] [ & ] [21 Savage ↗ARTIST] [•] [Her Loss ↗ALBUM]
        val runs = listOf(
            Run("Song "),
            Run(" • "),
            Run("Drake", "UC_Drake", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" & "),
            Run("21 Savage", "UC_21", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" • "),
            Run("Her Loss", "MPRE...", "MUSIC_PAGE_TYPE_ALBUM")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("Drake & 21 Savage", result.displayName)
        assertEquals(1.0f, result.confidence)
        assertEquals(2, result.individuals.size)
        assertEquals("Drake", result.individuals[0].name)
        assertEquals("21 Savage", result.individuals[1].name)
    }

    @Test
    fun testCategory3_FeaturedArtist() {
        // [Calvin Harris ↗ARTIST] [ feat. ] [Rihanna ↗ARTIST] [•] [Motion ↗ALBUM]
        val runs = listOf(
            Run("Calvin Harris", "UC_Calvin", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" feat. "),
            Run("Rihanna", "UC_Rihanna", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" • "),
            Run("Motion", "MPRE...", "MUSIC_PAGE_TYPE_ALBUM")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("Calvin Harris & Rihanna", result.displayName)
        assertEquals(1.0f, result.confidence)
        assertEquals(2, result.individuals.size)
    }

    @Test
    fun testCategory4_BandNameWithAmpersand() {
        // [Song] [•] [Simon & Garfunkel ↗ARTIST] [•] [Greatest Hits ↗ALBUM]
        val runs = listOf(
            Run("Song "),
            Run(" • "),
            Run("Simon & Garfunkel", "UC_Band", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" • "),
            Run("Greatest Hits", "MPRE...", "MUSIC_PAGE_TYPE_ALBUM")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("Simon & Garfunkel", result.displayName)
        assertEquals(1.0f, result.confidence)
        assertEquals(1, result.individuals.size)
        assertEquals("Simon & Garfunkel", result.individuals[0].name)
    }

    @Test
    fun testCategory5_NoEndpointsTextParsing() {
        // [Song] [•] [Billie Eilish] [•] [Happier Than Ever] [•] [2021]
        val runs = listOf(
            Run("Song "),
            Run(" • "),
            Run("Billie Eilish"),
            Run(" • "),
            Run("Happier Than Ever"),
            Run(" • "),
            Run("2021")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("Billie Eilish", result.displayName)
        assertEquals(0.6f, result.confidence)
    }

    @Test
    fun testCategory6_PlayerAuthorFallback() {
        // Request with runs = null, playerAuthor = "Adele - Topic"
        val result = ArtistExtractor.extract(
            runs = null,
            playerAuthor = "Adele - Topic",
            playerChannelId = "UC_Adele"
        )
        assertEquals("Adele", result.displayName)
        assertEquals(0.5f, result.confidence)
    }

    @Test
    fun testCategory7_EmptyOrNullInput() {
        // runs = null, playerAuthor = null
        val result = ArtistExtractor.extract(null)
        assertEquals("Unknown Artist", result.displayName)
        assertEquals(0.0f, result.confidence)
    }

    @Test
    fun testCategory8_NonLatinScript() {
        // [曲] [•] [米津玄師 ↗ARTIST] [•] [STRAY SHEEP ↗ALBUM] [•] [2020]
        val runs = listOf(
            Run("曲 "),
            Run(" • "),
            Run("米津玄師", "UC_Yonezu", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" • "),
            Run("STRAY SHEEP", "MPRE_Stray", "MUSIC_PAGE_TYPE_ALBUM"),
            Run(" • "),
            Run("2020")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("米津玄師", result.displayName)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun testCategory9_ViewCountInSubtitle() {
        // [Taylor Swift ↗ARTIST] [•] [1.2B views]
        val runs = listOf(
            Run("Taylor Swift", "UC_Taylor", "MUSIC_PAGE_TYPE_ARTIST"),
            Run(" • "),
            Run("1.2B views")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("Taylor Swift", result.displayName)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun testCategory10_ShortBylineQueue() {
        // Input shortBylineRuns: [Doja Cat]
        val shortBylineRuns = listOf(
            Run("Doja Cat", "UC_Doja", "MUSIC_PAGE_TYPE_ARTIST")
        )
        val result = ArtistExtractor.extract(
            runs = null,
            shortBylineRuns = shortBylineRuns
        )
        assertEquals("Doja Cat", result.displayName)
        assertEquals(0.9f, result.confidence)
    }
    
    @Test
    fun testTextParsing_Compilation() {
        val runs = listOf(
            Run("Album "),
            Run(" • "),
            Run("Various Artists"),
            Run(" • "),
            Run("2022")
        )
        val result = ArtistExtractor.extract(runs)
        assertEquals("Various Artists", result.displayName)
    }
    
    @Test
    fun testPlayerFallback_Vevo() {
        val result = ArtistExtractor.extract(
            runs = null,
            playerAuthor = "TheWeekndVEVO",
            playerChannelId = "UC_Vevo"
        )
        assertEquals("TheWeeknd", result.displayName)
        assertEquals(0.5f, result.confidence)
    }
}
