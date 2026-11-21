package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.domain.model.CountryUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionProvider @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {
    val regionCodeFlow: Flow<String> = settingsDataStore.countryCode.map { stored ->
        stored?.trim()?.takeIf { it.length == 2 } ?: CountryUtils.resolveFallbackCountryCode()
    }

    val regionNameFlow: Flow<String> = combine(settingsDataStore.countryCode, settingsDataStore.countryName) { code, name ->
        name?.trim()?.takeIf { it.isNotBlank() }
            ?: code?.takeIf { it.isNotBlank() }?.let { CountryUtils.getCountryName(it) }
            ?: CountryUtils.getCountryName(CountryUtils.resolveFallbackCountryCode())
    }

    val languageCodeFlow: Flow<String> = settingsDataStore.languageCode.map { stored ->
        stored?.trim()?.takeIf { it.isNotBlank() } ?: CountryUtils.getDynamicLanguage()
    }

    suspend fun getRegionCode(): String {
        return settingsDataStore.countryCode.first()?.trim()?.takeIf { it.length == 2 }
            ?: CountryUtils.resolveFallbackCountryCode()
    }

    suspend fun getRegionName(): String {
        val cachedName = settingsDataStore.countryName.first()?.trim()
        if (!cachedName.isNullOrBlank() && cachedName.length > 2) return cachedName
        return CountryUtils.getCountryName(getRegionCode())
    }

    suspend fun getLanguageCode(): String {
        return settingsDataStore.languageCode.first()?.trim()?.takeIf { it.isNotBlank() }
            ?: CountryUtils.getDynamicLanguage()
    }
}
