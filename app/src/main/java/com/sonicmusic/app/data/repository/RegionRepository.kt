package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.api.RegionApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionRepository @Inject constructor(
    private val regionApi: RegionApi,
    private val settingsDataStore: SettingsDataStore,
    private val newPipeService: com.sonicmusic.app.data.remote.source.NewPipeService
) {
    companion object {
        private const val FALLBACK_COUNTRY_CODE = "US"
        private const val SECONDARY_REGION_URL = "https://ipwho.is/"
    }

    val regionCode: Flow<String?> = settingsDataStore.regionCode
    val countryCode: Flow<String?> = settingsDataStore.countryCode
    val countryName: Flow<String?> = settingsDataStore.countryName

    /**
     * initializes the region by checking if it's already set.
     * If not, it tries to get it from the API, falling back to the device locale.
     */
    suspend fun initializeRegion() {
        try {
            val currentRegion = settingsDataStore.regionCode.first()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val cachedCountryCode = settingsDataStore.countryCode.first()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val currentCountry = normalizeCountryCode(cachedCountryCode)
            val currentCountryName = settingsDataStore.countryName.first()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val isNameJustCode = currentCountryName != null &&
                (currentCountry != null && currentCountryName.equals(cachedCountryCode, ignoreCase = true) ||
                    isLikelyCode(currentCountryName))

            // If cache is missing or stale, fetch fresh region from IP.
            if (currentRegion == null || currentCountry == null || currentCountryName == null || isNameJustCode) {
                fetchAndSaveRegion()
            } else {
                // Canonicalize legacy country codes like UK -> GB.
                if (!cachedCountryCode.equals(currentCountry, ignoreCase = true)) {
                    settingsDataStore.setCountryCode(currentCountry)
                    if (currentRegion.equals(cachedCountryCode, ignoreCase = true)) {
                        settingsDataStore.setRegionCode(normalizeRegion(currentCountry, currentCountry))
                    }
                    if (currentCountryName.equals(cachedCountryCode, ignoreCase = true)) {
                        settingsDataStore.setCountryName(normalizeCountryName(currentCountryName, currentCountry))
                    }
                }

                val canonicalRegion = normalizeRegion(currentRegion, currentCountry)
                if (canonicalRegion != currentRegion) {
                    settingsDataStore.setRegionCode(canonicalRegion)
                }

                val canonicalCountryName = normalizeCountryName(currentCountryName, currentCountry)
                if (canonicalCountryName != currentCountryName) {
                    settingsDataStore.setCountryName(canonicalCountryName)
                }

                // Restore NewPipe region from cache.
                newPipeService.updateRegion(currentCountry)
            }
        } catch (e: Exception) {
            // Never crash app startup due to region detection failures.
            android.util.Log.e("RegionRepository", "Error initializing region", e)
            fallbackToLocale()
        }
    }

    suspend fun refreshRegion() {
        try {
            fetchAndSaveRegion()
        } catch (_: Exception) {
            fallbackToLocale()
        }
    }

    private suspend fun fetchAndSaveRegion() {
        val primaryResponse = runCatching { regionApi.getRegion() }.getOrNull()
        if (primaryResponse != null && saveRegionFromResponse(primaryResponse)) {
            return
        }

        val secondaryResponse = runCatching { regionApi.getRegionByUrl(SECONDARY_REGION_URL) }.getOrNull()
        if (secondaryResponse != null && saveRegionFromResponse(secondaryResponse)) {
            return
        }

        android.util.Log.e("RegionRepository", "All region fetch attempts failed, falling back to locale")
        fallbackToLocale()
    }

    private suspend fun saveRegionFromResponse(response: com.sonicmusic.app.data.remote.model.RegionResponse): Boolean {
        val country = normalizeCountryCode(response.countryCode)
            ?: normalizeCountryCode(response.country)
            ?: return false
        if (!response.isSuccessful()) return false

        val region = normalizeRegion(response.region, country)
        val countryName = normalizeCountryName(response.country, country)

        settingsDataStore.setCountryCode(country)
        settingsDataStore.setRegionCode(region)
        settingsDataStore.setCountryName(countryName)

        // Update NewPipe region
        newPipeService.updateRegion(country)
        return true
    }

    private suspend fun fallbackToLocale() {
        val locale = Locale.getDefault()
        val country = normalizeCountryCode(locale.country) ?: FALLBACK_COUNTRY_CODE
        val countryName = normalizeCountryName(locale.displayCountry, country)

        settingsDataStore.setCountryCode(country)
        settingsDataStore.setRegionCode(country) // Use country as region fallback
        settingsDataStore.setCountryName(countryName)

        // Update NewPipe region
        newPipeService.updateRegion(country)
    }

    private fun normalizeCountryCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.US)
        if (normalized.isNullOrEmpty() || normalized.length != 2) return null
        if (!normalized.all { it.isLetter() }) return null
        return canonicalizeIsoCountryCode(normalized)
    }

    private fun normalizeRegion(region: String?, fallbackCountry: String): String {
        val normalized = region?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackCountry
        return if (normalized.length in 2..3 && normalized.all { it.isLetter() }) {
            val upper = normalized.uppercase(Locale.US)
            if (upper.length == 2) canonicalizeIsoCountryCode(upper) else upper
        } else {
            normalized
        }
    }

    private fun normalizeCountryName(countryName: String?, countryCode: String): String {
        val normalized = countryName?.trim()
        if (!normalized.isNullOrEmpty() && !isLikelyCode(normalized)) return normalized

        val localeName = Locale("", countryCode).displayCountry
        return if (localeName.isNullOrBlank()) countryCode else localeName
    }

    private fun canonicalizeIsoCountryCode(code: String): String {
        return if (code == "UK") "GB" else code
    }

    private fun isLikelyCode(value: String): Boolean {
        return value.length == 2 && value.all { it.isLetter() }
    }
}
