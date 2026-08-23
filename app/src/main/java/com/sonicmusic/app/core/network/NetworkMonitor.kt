package com.sonicmusic.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide network connectivity monitor.
 * Exposes a StateFlow for synchronous checks and a Flow for reactive flows.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnlineState = MutableStateFlow(isConnectedNow())
    val isOnlineState: StateFlow<Boolean> = _isOnlineState.asStateFlow()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnlineState.value = true
            }

            override fun onLost(network: Network) {
                _isOnlineState.value = isConnectedNow()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val connected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _isOnlineState.value = connected
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            _isOnlineState.value = true
        }
    }

    val isOnline: Flow<Boolean> = isOnlineState

    fun isConnectedNow(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return true // Optimistic default to allow requests
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }
}
