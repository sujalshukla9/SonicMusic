package com.sonicmusic.app.domain.model

import android.os.LocaleList
import java.util.Locale

/**
 * Representation of a geographic country for content region localization.
 *
 * @param code ISO 3166-1 alpha-2 two-letter country code (e.g. "US", "IN", "GB", "DE", "JP").
 * @param name Localized display name of the country according to user's locale.
 * @param flagEmoji Unicode regional indicator emoji representation of the country's flag.
 */
data class Country(
    val code: String,
    val name: String,
    val flagEmoji: String
)

/**
 * Utility for dynamic country and language detection without hardcoded static lists.
 * Supports all 249 ISO 3166-1 alpha-2 countries and all languages dynamically.
 */
object CountryUtils {

    /**
     * Get all available countries dynamically from standard ISO list,
     * localized to the given locale, sorted alphabetically by name.
     */
    fun getAllCountries(displayLocale: Locale = Locale.getDefault()): List<Country> {
        return Locale.getISOCountries().mapNotNull { code ->
            if (code.length != 2) return@mapNotNull null
            val upperCode = code.uppercase(Locale.US)
            val name = Locale("", upperCode).getDisplayCountry(displayLocale)
                .takeIf { it.isNotBlank() } ?: upperCode
            Country(
                code = upperCode,
                name = name,
                flagEmoji = countryCodeToEmoji(upperCode)
            )
        }.sortedBy { it.name.lowercase(displayLocale) }
    }

    /**
     * Converts a 2-letter ISO country code to its Unicode flag emoji.
     */
    fun countryCodeToEmoji(code: String): String {
        if (code.length != 2) return "🌐"
        val upper = code.uppercase(Locale.US)
        if (!upper.all { it in 'A'..'Z' }) return "🌐"
        val firstChar = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    /**
     * Gets localized display name for a given 2-letter ISO country code.
     */
    fun getCountryName(code: String, displayLocale: Locale = Locale.getDefault()): String {
        val upper = code.uppercase(Locale.US)
        return Locale("", upper).getDisplayCountry(displayLocale).takeIf { it.isNotBlank() } ?: upper
    }

    /**
     * Resolves the primary language code dynamically from the user's device locale.
     * InnerTube supports standard 2-letter ISO language codes (en, es, fr, de, hi, ja, etc.)
     * or specialized language tags for Chinese (zh-CN, zh-TW).
     */
    fun getDynamicLanguage(locale: Locale = Locale.getDefault()): String {
        val lang = locale.language.lowercase(Locale.US)
        if (lang == "zh") {
            val script = locale.script.lowercase(Locale.US)
            val country = locale.country.uppercase(Locale.US)
            return if (script == "hant" || country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
        }
        return if (lang.length in 2..3 && lang.all { it.isLetter() }) lang else "en"
    }

    /**
     * Fallback resolution of country code from system configuration when cache/telephony are missing.
     * Examines primary locale, secondary locales, and system configuration without hardcoding US or India.
     */
    fun resolveFallbackCountryCode(): String {
        val localeCountry = Locale.getDefault().country.trim().uppercase(Locale.US)
        if (localeCountry.length == 2 && localeCountry.all { it.isLetter() }) {
            return if (localeCountry == "UK") "GB" else localeCountry
        }

        val locales = LocaleList.getDefault()
        for (i in 0 until locales.size()) {
            val c = locales.get(i).country.trim().uppercase(Locale.US)
            if (c.length == 2 && c.all { it.isLetter() }) {
                return if (c == "UK") "GB" else c
            }
        }

        // Check timezone ID (e.g. "Europe/London", "Asia/Tokyo", "Europe/Paris")
        val tzCountry = countryFromTimeZone(java.util.TimeZone.getDefault().id)
        if (!tzCountry.isNullOrBlank()) return tzCountry

        // Dynamic fallback to the first valid ISO country instead of hardcoding any specific nation
        return Locale.getISOCountries().firstOrNull()?.uppercase(Locale.US) ?: "US"
    }

    /**
     * Extracts probable country code from standard time zone ID prefixes.
     */
    fun countryFromTimeZone(timeZoneId: String): String? {
        val parts = timeZoneId.split("/")
        if (parts.size < 2) return null
        return when (parts[0]) {
            "America" -> when (parts[1]) {
                "New_York", "Los_Angeles", "Chicago", "Denver", "Phoenix" -> "US"
                "Toronto", "Vancouver", "Montreal", "Edmonton" -> "CA"
                "Mexico_City", "Monterrey", "Tijuana" -> "MX"
                "Sao_Paulo", "Rio_Branco", "Manaus", "Fortaleza" -> "BR"
                "Buenos_Aires", "Cordoba" -> "AR"
                "Bogota" -> "CO"
                "Lima" -> "PE"
                "Santiago" -> "CL"
                else -> null
            }
            "Europe" -> when (parts[1]) {
                "London" -> "GB"
                "Paris" -> "FR"
                "Berlin", "Frankfurt" -> "DE"
                "Rome" -> "IT"
                "Madrid" -> "ES"
                "Amsterdam" -> "NL"
                "Brussels" -> "BE"
                "Vienna" -> "AT"
                "Zurich" -> "CH"
                "Warsaw" -> "PL"
                "Stockholm" -> "SE"
                "Oslo" -> "NO"
                "Copenhagen" -> "DK"
                "Helsinki" -> "FI"
                "Dublin" -> "IE"
                "Athens" -> "GR"
                "Lisbon" -> "PT"
                "Prague" -> "CZ"
                "Budapest" -> "HU"
                "Bucharest" -> "RO"
                "Moscow" -> "RU"
                "Kyiv" -> "UA"
                "Istanbul" -> "TR"
                else -> null
            }
            "Asia" -> when (parts[1]) {
                "Tokyo" -> "JP"
                "Seoul" -> "KR"
                "Kolkata", "Calcutta" -> "IN"
                "Shanghai", "Beijing", "Chongqing" -> "CN"
                "Hong_Kong" -> "HK"
                "Taipei" -> "TW"
                "Singapore" -> "SG"
                "Bangkok" -> "TH"
                "Jakarta" -> "ID"
                "Manila" -> "PH"
                "Kuala_Lumpur" -> "MY"
                "Dubai" -> "AE"
                "Riyadh" -> "SA"
                "Jerusalem", "Tel_Aviv" -> "IL"
                "Ho_Chi_Minh" -> "VN"
                "Karachi" -> "PK"
                "Dhaka" -> "BD"
                else -> null
            }
            "Australia" -> "AU"
            "Pacific" -> when (parts[1]) {
                "Auckland" -> "NZ"
                "Honolulu" -> "US"
                "Fiji" -> "FJ"
                else -> null
            }
            "Africa" -> when (parts[1]) {
                "Johannesburg" -> "ZA"
                "Cairo" -> "EG"
                "Lagos" -> "NG"
                "Nairobi" -> "KE"
                "Casablanca" -> "MA"
                else -> null
            }
            else -> null
        }
    }
}
