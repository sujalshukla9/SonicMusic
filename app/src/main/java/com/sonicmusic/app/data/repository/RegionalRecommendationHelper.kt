package com.sonicmusic.app.data.repository

import java.util.Calendar
import java.util.Locale

internal object RegionalRecommendationHelper {

    private val GLOBAL_KEYWORDS = listOf("top hits", "viral songs", "new music", "party songs")

    fun normalizeCountryCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.US)
        if (normalized.isNullOrEmpty() || normalized.length != 2) return null
        if (!normalized.all { it.isLetter() }) return null
        return if (normalized == "UK") "GB" else normalized
    }

    fun canonicalCountryName(countryCode: String, cachedName: String?): String {
        val trimmed = cachedName?.trim()
        if (!trimmed.isNullOrEmpty() && !isLikelyCode(trimmed)) return trimmed

        val localeName = Locale("", countryCode).displayCountry
        return if (localeName.isNullOrBlank()) countryCode else localeName
    }

    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    fun regionalKeywords(countryCode: String): List<String> {
        return when (countryCode) {
            "IN" -> listOf("bollywood", "hindi", "punjabi", "indian pop")
            "US", "GB", "CA", "AU", "NZ" -> listOf("pop", "hip hop", "r&b", "indie")
            "BR" -> listOf("brazilian pop", "sertanejo", "funk brasileiro", "mpb")
            "ES", "MX", "AR", "CO", "CL" -> listOf("latin", "reggaeton", "musica latina", "espanol")
            "JP" -> listOf("j-pop", "city pop", "anime songs", "japanese music")
            "KR" -> listOf("k-pop", "korean ballad", "k-hip hop", "k-indie")
            "DE", "AT", "CH" -> listOf("german pop", "deutschrap", "electronic", "indie")
            "FR" -> listOf("french pop", "chanson", "rap francais", "electro")
            "IT" -> listOf("italian pop", "trap italiana", "indie italiano", "dance")
            "TR" -> listOf("turkish pop", "turkce rap", "anadolu rock", "slow songs")
            "ID", "MY", "SG" -> listOf("indonesian pop", "malay hits", "viral songs", "acoustic")
            else -> GLOBAL_KEYWORDS
        }
    }

    fun preferredLanguages(countryCode: String): List<String> {
        return when (countryCode) {
            "IN" -> listOf("Hindi", "English", "Punjabi")
            "ES", "MX", "AR", "CO", "CL" -> listOf("Spanish", "English")
            "BR", "PT" -> listOf("Portuguese", "English")
            "JP" -> listOf("Japanese", "English")
            "KR" -> listOf("Korean", "English")
            "FR" -> listOf("French", "English")
            "DE", "AT", "CH" -> listOf("German", "English")
            "IT" -> listOf("Italian", "English")
            "TR" -> listOf("Turkish", "English")
            else -> listOf("English")
        }
    }

    fun defaultPopularArtists(countryCode: String): List<String> {
        return when (countryCode) {
            "IN" -> listOf(
                "Arijit Singh",
                "Shreya Ghoshal",
                "Atif Aslam",
                "Pritam",
                "A.R. Rahman",
                "Neha Kakkar",
                "Jubin Nautiyal",
                "Badshah",
            )
            "US", "GB", "CA", "AU", "NZ" -> listOf(
                "Taylor Swift",
                "Drake",
                "The Weeknd",
                "Billie Eilish",
                "Dua Lipa",
                "Ed Sheeran",
                "SZA",
                "Post Malone",
            )
            "ES", "MX", "AR", "CO", "CL" -> listOf(
                "Bad Bunny",
                "Karol G",
                "Feid",
                "Rauw Alejandro",
                "Shakira",
                "J Balvin",
                "Rosalia",
                "Quevedo",
            )
            "JP" -> listOf(
                "YOASOBI",
                "Kenshi Yonezu",
                "Ado",
                "Official HIGE DANdism",
                "Vaundy",
                "King Gnu",
                "Mrs. GREEN APPLE",
                "Aimer",
            )
            "KR" -> listOf(
                "BTS",
                "BLACKPINK",
                "NewJeans",
                "SEVENTEEN",
                "IU",
                "LE SSERAFIM",
                "Stray Kids",
                "aespa",
            )
            else -> listOf(
                "Taylor Swift",
                "The Weeknd",
                "Bad Bunny",
                "Drake",
                "BTS",
                "Dua Lipa",
                "Billie Eilish",
                "Ed Sheeran",
            )
        }
    }

    fun timeBasedQuery(hour: Int, countryCode: String, countryName: String): String {
        val keyword = regionalKeywords(countryCode).random()
        val year = currentYear()

        val templates = when (hour) {
            in 5..11 -> listOf(
                "morning $keyword songs",
                "good morning music $countryName",
                "workout $keyword hits",
            )
            in 12..16 -> listOf(
                "trending $keyword songs $countryName $year",
                "afternoon chill $keyword",
                "top songs in $countryName",
            )
            in 17..20 -> listOf(
                "evening vibes $keyword",
                "popular $keyword songs",
                "sunset chill songs $countryName",
            )
            else -> listOf(
                "night chill $keyword songs",
                "late night relaxing music $countryName",
                "slow $keyword songs",
            )
        }

        return templates.random()
    }

    fun fallbackTrendingSearches(countryCode: String, countryName: String): List<String> {
        val keyword = regionalKeywords(countryCode).firstOrNull() ?: "top hits"
        val year = currentYear()

        return listOf(
            "Top Hits in $countryName",
            "Trending Songs $year",
            "$countryName Party Mix",
            "$keyword hits",
            "New releases $countryName",
            "Viral songs today",
            "Workout songs",
            "Chill vibes",
        )
    }

    private fun isLikelyCode(value: String): Boolean {
        return value.length == 2 && value.all { it.isLetter() }
    }
}
