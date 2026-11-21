package com.sonicmusic.app.data.repository

import android.content.Context
import android.telephony.TelephonyManager
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.api.RegionApi
import com.sonicmusic.app.data.remote.source.NewPipeService
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.CountryUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionRepository @Inject constructor(
    private val regionApi: RegionApi,
    private val settingsDataStore: SettingsDataStore,
    private val newPipeService: NewPipeService,
    private val youTubeiService: YouTubeiService,
    private val sessionStore: YouTubePlaybackSessionStore,
    @ApplicationContext private val context: Context
) {
    private val initMutex = Mutex()

    companion object {
        private const val SECONDARY_REGION_URL = "https://ipwho.is/"
        private const val TERTIARY_REGION_URL = "https://freeipapi.com/api/json"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    val regionCode: Flow<String?> = settingsDataStore.regionCode
    val countryCode: Flow<String?> = settingsDataStore.countryCode
    val countryName: Flow<String?> = settingsDataStore.countryName
    val isRegionManual: Flow<Boolean> = settingsDataStore.isRegionManual

    /**
     * Initializes the region by checking if it's already set and valid.
     * Skips auto-detection if user has manually selected a region.
     * Uses cache to avoid unnecessary network API calls.
     */
    suspend fun initializeRegion() {
        initMutex.withLock {
            try {
                // If user manually chose a region, keep it and restore NewPipe without running auto-detection
                val isManual = settingsDataStore.isRegionManual.first()
                if (isManual) {
                    val currentCountry = normalizeCountryCode(
                        settingsDataStore.countryCode.first()?.trim()?.takeIf { it.isNotEmpty() }
                    )
                    if (currentCountry != null) {
                        newPipeService.updateRegion(currentCountry)
                        android.util.Log.d("RegionRepository", "Preserved manual region: $currentCountry")
                        return
                    }
                }

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
                val isRegionFromIp = settingsDataStore.isRegionFromIp.first() ?: false
                val isNameJustCode = currentCountryName != null &&
                    (currentCountry != null && currentCountryName.equals(cachedCountryCode, ignoreCase = true) ||
                        isLikelyCode(currentCountryName))

                val lastDetectedAt = settingsDataStore.regionDetectedAt.first()
                val isCacheFresh = (System.currentTimeMillis() - lastDetectedAt) < CACHE_TTL_MS

                // If cache is missing, stale, or incomplete, fetch fresh region.
                if (currentRegion == null || currentCountry == null || currentCountryName == null || isNameJustCode || !isRegionFromIp || !isCacheFresh) {
                    fetchAndSaveRegion()
                } else {
                    // Canonicalize legacy country codes (e.g. UK -> GB)
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
                android.util.Log.e("RegionRepository", "Error initializing region", e)
                fallbackToLocale()
            }
        }
    }

    suspend fun refreshRegion() {
        try {
            fetchAndSaveRegion()
        } catch (_: Exception) {
            fallbackToLocale()
        }
    }

    suspend fun setManualCountry(countryCode: String) {
        val canonicalCountry = normalizeCountryCode(countryCode) ?: return
        val countryName = normalizeCountryName(null, canonicalCountry)

        settingsDataStore.setCountryCode(canonicalCountry)
        settingsDataStore.setRegionCode(canonicalCountry)
        settingsDataStore.setCountryName(countryName)
        settingsDataStore.setIsRegionFromIp(false)
        settingsDataStore.setIsRegionManual(true)
        settingsDataStore.setRegionDetectedAt(System.currentTimeMillis())

        newPipeService.updateRegion(canonicalCountry)
        android.util.Log.d("RegionRepository", "Set manual country to: $canonicalCountry ($countryName)")
    }

    suspend fun setAutoDetectRegion() {
        settingsDataStore.setIsRegionManual(false)
        refreshRegion()
    }

    private suspend fun fetchAndSaveRegion() {
        // Priority 1: Dynamic detection directly from YouTube Music / InnerTube session context
        val sessionCookies = runCatching { sessionStore.snapshot().session?.cookies }.getOrNull()
        val innertubeResponse = runCatching {
            youTubeiService.detectCountryFromInnertube(sessionCookies)
        }.getOrNull()

        if (innertubeResponse != null && saveRegionFromDetectedCountry(innertubeResponse, isFromIp = true)) {
            android.util.Log.d("RegionRepository", "Resolved region dynamically from InnerTube/YTM session: $innertubeResponse")
            return
        }

        // Priority 2: Device's network country (TelephonyManager - cellular tower / SIM)
        val telephonyCountry = detectCellularCountry()
        if (telephonyCountry != null && saveRegionFromDetectedCountry(telephonyCountry, isFromIp = false)) {
            android.util.Log.d("RegionRepository", "Resolved region from network/SIM country: $telephonyCountry")
            return
        }

        // Priority 3: External IP geolocation APIs as fallback (for Wi-Fi devices without cellular)
        val primaryResponse = runCatching { regionApi.getRegion() }.getOrNull()
        if (primaryResponse != null && saveRegionFromResponse(primaryResponse)) {
            return
        }

        val secondaryResponse = runCatching { regionApi.getRegionByUrl(SECONDARY_REGION_URL) }.getOrNull()
        if (secondaryResponse != null && saveRegionFromResponse(secondaryResponse)) {
            return
        }

        val tertiaryResponse = runCatching { regionApi.getRegionByUrl(TERTIARY_REGION_URL) }.getOrNull()
        if (tertiaryResponse != null && saveRegionFromResponse(tertiaryResponse)) {
            return
        }

        // Priority 4: Device locale (dynamic, non-hardcoded)
        android.util.Log.e("RegionRepository", "All online region detection failed, falling back to device locale")
        fallbackToLocale()
    }

    private fun detectCellularCountry(): String? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val networkCountry = telephonyManager?.networkCountryIso?.trim()?.takeIf { it.isNotEmpty() }
            val simCountry = telephonyManager?.simCountryIso?.trim()?.takeIf { it.isNotEmpty() }
            return normalizeCountryCode(networkCountry) ?: normalizeCountryCode(simCountry)
        } catch (e: Exception) {
            android.util.Log.w("RegionRepository", "Failed detecting cellular country", e)
            return null
        }
    }

    private suspend fun saveRegionFromDetectedCountry(countryCode: String, isFromIp: Boolean): Boolean {
        val country = normalizeCountryCode(countryCode) ?: return false
        val countryName = normalizeCountryName(null, country)

        settingsDataStore.setCountryCode(country)
        settingsDataStore.setRegionCode(country)
        settingsDataStore.setCountryName(countryName)
        settingsDataStore.setIsRegionFromIp(isFromIp)
        settingsDataStore.setRegionDetectedAt(System.currentTimeMillis())

        newPipeService.updateRegion(country)
        return true
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
        settingsDataStore.setIsRegionFromIp(true)
        settingsDataStore.setRegionDetectedAt(System.currentTimeMillis())

        newPipeService.updateRegion(country)
        return true
    }

    private suspend fun fallbackToLocale() {
        val country = normalizeCountryCode(Locale.getDefault().country)
            ?: CountryUtils.resolveFallbackCountryCode()
        val countryName = normalizeCountryName(Locale.getDefault().displayCountry, country)

        settingsDataStore.setCountryCode(country)
        settingsDataStore.setRegionCode(country)
        settingsDataStore.setCountryName(countryName)
        settingsDataStore.setIsRegionFromIp(false)
        settingsDataStore.setRegionDetectedAt(System.currentTimeMillis())

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
        return if (localeName.isNullOrBlank()) CountryUtils.getCountryName(countryCode) else localeName
    }

    private fun canonicalizeIsoCountryCode(code: String): String {
        return if (code == "UK") "GB" else code
    }

    private fun isLikelyCode(value: String): Boolean {
        return value.length == 2 && value.all { it.isLetter() }
    }
}
