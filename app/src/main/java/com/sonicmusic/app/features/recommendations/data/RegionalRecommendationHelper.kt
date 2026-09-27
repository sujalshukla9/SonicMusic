package com.sonicmusic.app.features.recommendations.data

import java.util.Calendar
import java.util.Locale

/**
 * Dynamic helper for regional music discovery.
 * 
 * In compliance with SonicMusic architectural rules:
 * - NO hardcoded country-specific music datasets (no hardcoded artists or fixed country lists).
 * - Dynamically adapts to any region detected via InnerTube/YouTube Music context.
 * - Derives language and country display names from standard system Locale data.
 */
internal object RegionalRecommendationHelper {

    private val UNIVERSAL_MUSIC_KEYWORDS = listOf("top hits", "trending songs", "new music", "viral hits", "popular tracks")

    fun normalizeCountryCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.US)
        if (normalized.isNullOrEmpty() || normalized.length != 2) return null
        if (!normalized.all { it.isLetter() }) return null
        return if (normalized == "UK") "GB" else normalized
    }

    fun canonicalCountryName(countryCode: String, cachedName: String?): String {
        val trimmed = cachedName?.trim()
        if (!trimmed.isNullOrEmpty() && !isLikelyCode(trimmed)) return trimmed

        val localeName = Locale("", countryCode).getDisplayCountry(Locale.getDefault())
        return if (localeName.isNullOrBlank()) countryCode else localeName
    }

    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    /**
     * Universal music discovery keywords that work dynamically in any region
     * without hardcoding per-country dictionaries.
     */
    fun regionalKeywords(countryCode: String): List<String> {
        return UNIVERSAL_MUSIC_KEYWORDS
    }

    /**
     * Dynamically determines preferred languages from device and country locale,
     * avoiding static country-to-language mappings.
     */
    fun preferredLanguages(countryCode: String): List<String> {
        val countryLocale = Locale("", countryCode)
        val defaultLocale = Locale.getDefault()

        val languages = linkedSetOf<String>()
        val defaultLang = defaultLocale.displayLanguage
        if (defaultLang.isNotBlank()) {
            languages.add(defaultLang)
        }

        val countryLang = countryLocale.displayLanguage
        if (countryLang.isNotBlank()) {
            languages.add(countryLang)
        }

        languages.add("English")
        return languages.toList()
    }

    /**
     * Generates dynamic contextual discovery queries based on time of day
     * and resolved country name, without hardcoded country music terms.
     */
    fun timeBasedQuery(hour: Int, countryCode: String, countryName: String): String {
        val keyword = UNIVERSAL_MUSIC_KEYWORDS.random()
        val year = currentYear()

        val templates = when (hour) {
            in 5..11 -> listOf(
                "morning $keyword",
                "acoustic morning tracks",
                "workout $keyword",
                "popular music in $countryName"
            )
            in 12..16 -> listOf(
                "trending $keyword in $countryName $year",
                "afternoon chill tracks",
                "popular tracks in $countryName",
                "popular music $year"
            )
            in 17..20 -> listOf(
                "evening chill $keyword",
                "popular $keyword $countryName",
                "sunset acoustic vibes",
                "trending music $countryName $year"
            )
            else -> listOf(
                "late night chill music",
                "relaxing $keyword",
                "slow music vibes",
                "popular tracks in $countryName"
            )
        }

        return templates.random()
    }

    fun fallbackTrendingSearches(countryCode: String, countryName: String): List<String> {
        val year = currentYear()
        return listOf(
            "Top Hits in $countryName",
            "Trending Songs $year",
            "Top Music Charts $year",
            "New Music Releases",
            "Global Viral Hits",
            "Workout Music",
            "Chill Acoustic Vibes"
        )
    }

    private fun isLikelyCode(value: String): Boolean {
        return value.length == 2 && value.all { it.isLetter() }
    }
}
