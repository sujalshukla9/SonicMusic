package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.api.RegionApi
import com.sonicmusic.app.data.remote.model.RegionResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner::class)
class RegionRepositoryTest {

    @Mock
    private lateinit var regionApi: RegionApi

    @Mock
    private lateinit var settingsDataStore: SettingsDataStore

    @Mock
    private lateinit var newPipeService: com.sonicmusic.app.data.remote.source.NewPipeService

    @Test
    fun `initializeRegion when region already exists does not call API`() = runTest {
        // Arrange
        whenever(settingsDataStore.regionCode).thenReturn(flowOf("US"))
        whenever(settingsDataStore.countryCode).thenReturn(flowOf("US"))
        whenever(settingsDataStore.countryName).thenReturn(flowOf("United States"))
        
        val repository = RegionRepository(regionApi, settingsDataStore, newPipeService)

        // Act
        repository.initializeRegion()

        // Assert
        verify(regionApi, never()).getRegion()
        verify(newPipeService).updateRegion("US")
    }

    @Test
    fun `initializeRegion when countryName is missing calls API`() = runTest {
        // Arrange
        whenever(settingsDataStore.regionCode).thenReturn(flowOf("US"))
        whenever(settingsDataStore.countryCode).thenReturn(flowOf("US"))
        whenever(settingsDataStore.countryName).thenReturn(flowOf(null)) // Missing Name
        whenever(regionApi.getRegion()).thenReturn(RegionResponse("US", "CA", "United States", "success"))

        val repository = RegionRepository(regionApi, settingsDataStore, newPipeService)

        // Act
        repository.initializeRegion()

        // Assert
        verify(regionApi).getRegion() // Should verify API call
        verify(settingsDataStore).setCountryName("United States")
    }
    
    @Test
    fun `initializeRegion when region is missing calls API and saves result`() = runTest {
        // Arrange
        whenever(settingsDataStore.regionCode).thenReturn(flowOf(""))
        whenever(settingsDataStore.countryCode).thenReturn(flowOf(""))
        whenever(settingsDataStore.countryName).thenReturn(flowOf("")) // Missing name
        whenever(regionApi.getRegion()).thenReturn(RegionResponse("IN", "Maharashtra", "India", "success"))
        
        val repository = RegionRepository(regionApi, settingsDataStore, newPipeService)
        
        // Act
        repository.initializeRegion()
        
        // Assert
        verify(regionApi).getRegion()
        verify(settingsDataStore).setCountryCode("IN")
        verify(settingsDataStore).setRegionCode("Maharashtra")
        verify(settingsDataStore).setCountryName("India")
        verify(newPipeService).updateRegion("IN")
    }

    @Test
    fun `initializeRegion when API fails falls back to locale`() = runTest {
        // Arrange
        whenever(settingsDataStore.regionCode).thenReturn(flowOf(""))
        whenever(settingsDataStore.countryCode).thenReturn(flowOf(""))
        whenever(settingsDataStore.countryName).thenReturn(flowOf("")) // Missing name
        whenever(regionApi.getRegion()).thenThrow(RuntimeException("Network Error"))
        
        val repository = RegionRepository(regionApi, settingsDataStore, newPipeService)
        
        // Act
        repository.initializeRegion()
        
        // Assert
        verify(regionApi).getRegion()
        verify(settingsDataStore).setCountryCode(any())
        verify(settingsDataStore).setRegionCode(any())
        verify(settingsDataStore).setCountryName(any())
        verify(newPipeService).updateRegion(any())
    }

    @Test
    fun `initializeRegion canonicalizes cached UK to GB`() = runTest {
        // Arrange
        whenever(settingsDataStore.regionCode).thenReturn(flowOf("UK"))
        whenever(settingsDataStore.countryCode).thenReturn(flowOf("UK"))
        whenever(settingsDataStore.countryName).thenReturn(flowOf("United Kingdom"))

        val repository = RegionRepository(regionApi, settingsDataStore, newPipeService)

        // Act
        repository.initializeRegion()

        // Assert
        verify(regionApi, never()).getRegion()
        verify(settingsDataStore).setCountryCode("GB")
        verify(settingsDataStore, atLeastOnce()).setRegionCode("GB")
        verify(newPipeService).updateRegion("GB")
    }

    @Test
    fun `initializeRegion canonicalizes UK payload from API`() = runTest {
        // Arrange
        whenever(settingsDataStore.regionCode).thenReturn(flowOf(""))
        whenever(settingsDataStore.countryCode).thenReturn(flowOf(""))
        whenever(settingsDataStore.countryName).thenReturn(flowOf(""))
        whenever(regionApi.getRegion()).thenReturn(RegionResponse("UK", "UK", "UK", "success"))

        val repository = RegionRepository(regionApi, settingsDataStore, newPipeService)

        // Act
        repository.initializeRegion()

        // Assert
        verify(settingsDataStore).setCountryCode("GB")
        verify(settingsDataStore).setRegionCode("GB")
        verify(settingsDataStore).setCountryName(any())
        verify(newPipeService).updateRegion("GB")
    }
}
