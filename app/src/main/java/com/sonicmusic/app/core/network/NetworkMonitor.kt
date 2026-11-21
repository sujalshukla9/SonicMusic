package com.sonicmusic.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide network connectivity monitor.
 * Tracks whether the default network can reach the internet.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnlineState = MutableStateFlow(isConnectedNow())
    val isOnlineState: StateFlow<Boolean> = _isOnlineState.asStateFlow()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var currentDefault: Network? = connectivityManager.activeNetwork

            override fun onAvailable(network: Network) {
                currentDefault = network
            }

            override fun onLost(network: Network) {
                if (currentDefault == network) {
                    currentDefault = null
                    _isOnlineState.value = false
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (currentDefault == network) _isOnlineState.value = networkCapabilities.isInternetValidated()
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Synchronous checks remain available if callback registration fails.
        }
    }

    fun isConnectedNow(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)?.isInternetValidated() == true
    }

    private fun NetworkCapabilities.isInternetValidated(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
