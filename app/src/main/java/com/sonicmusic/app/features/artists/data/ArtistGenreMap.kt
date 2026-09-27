package com.sonicmusic.app.features.artists.data

import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song

/**
 * Shared artist to genre mapping and song validation helpers
 * used across recommendation scoring engines and repositories.
 */
object ArtistGenreMap {

    /**
     * Common artist -> genre mapping for popular Indian and international artists.
     */
    val MAP: Map<String, List<String>> = mapOf(
        "Arijit Singh" to listOf(" Bollywood", " Romantic"),
        " Shreya Ghoshal" to listOf(" Bollywood", " Classical"),
        " Atif Aslam" to listOf(" Bollywood", " Pop"),
        " Pritam" to listOf(" Bollywood", " Film"),
        " A.R. Rahman" to listOf(" Bollywood", " Classical", " World"),
        " Neha Kakkar" to listOf(" Bollywood", " Pop"),
        " Badshah" to listOf(" Hip-Hop", " Bollywood"),
        " Honey Singh" to listOf(" Hip-Hop", " Bollywood"),
        " Jubin Nautiyal" to listOf(" Bollywood", " Pop"),
        " B Praak" to listOf(" Bollywood", " Punjabi"),
        " Vishal Mishra" to listOf(" Bollywood", " Romantic"),
        " Diljit Dosanjh" to listOf(" Punjabi", " Pop"),
        " AP Dhillon" to listOf(" Punjabi", " Hip-Hop"),
        " Drake" to listOf(" Hip-Hop", " R&B"),
        " Taylor Swift" to listOf(" Pop", " Country"),
        " The Weeknd" to listOf(" R&B", " Pop"),
        " Ed Sheeran" to listOf(" Pop", " Acoustic"),
        " BTS" to listOf(" K-Pop", " Pop"),
        " Eminem" to listOf(" Hip-Hop", " Rap"),
        " Billie Eilish" to listOf(" Pop", " Alternative"),
        " Post Malone" to listOf(" Hip-Hop", " Pop"),
        " Dua Lipa" to listOf(" Pop", " Dance"),
        " Travis Scott" to listOf(" Hip-Hop", " Trap"),
        " Lana Del Rey" to listOf(" Indie", " Pop"),
        " Coldplay" to listOf(" Rock", " Pop"),
        " Imagine Dragons" to listOf(" Rock", " Pop"),
        " Ariana Grande" to listOf(" Pop", " R&B"),
        " Justin Bieber" to listOf(" Pop", " R&B"),
        " Maroon 5" to listOf(" Pop", " Rock"),
        " Sunidhi Chauhan" to listOf(" Bollywood", " Pop"),
        " Sonu Nigam" to listOf(" Bollywood", " Classical")
    )
}

/**
 * Filter strictly valid songs (avoiding non-music video podcasts/shorts unless valid song duration).
 */
fun Song.isStrictSong(): Boolean {
    return when (contentType) {
        ContentType.SONG -> true
        ContentType.UNKNOWN -> duration == 0 || duration in 60..600
        else -> false
    }
}
