package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionProvider @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val DEFAULT_REGION = "US"
    }

    val regionCodeFlow: Flow<String> = settingsDataStore.countryCode.map {
        it?.trim()?.takeIf { it.length == 2 } ?: Locale.getDefault().country.uppercase(Locale.US).takeIf { c -> c.length == 2 } ?: DEFAULT_REGION
    }

    val regionNameFlow: Flow<String> = settingsDataStore.countryName.map {
        it?.trim() ?: Locale.getDefault().displayCountry.takeIf { c -> c.isNotBlank() } ?: "United States"
    }

    suspend fun getRegionCode(): String {
        return settingsDataStore.countryCode.first()?.trim()?.takeIf { it.length == 2 }
            ?: Locale.getDefault().country.uppercase(Locale.US).takeIf { c -> c.length == 2 }
            ?: DEFAULT_REGION
    }

    suspend fun getRegionName(): String {
        return settingsDataStore.countryName.first()?.trim()
            ?: Locale.getDefault().displayCountry.takeIf { c -> c.isNotBlank() }
            ?: "United States"
    }
}
