package com.sonicmusic.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

@Singleton
class NetworkStatusMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isConnectedNow())
            }

            override fun onLost(network: Network) {
                trySend(isConnectedNow())
            }

            override fun onUnavailable() {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(networkCapabilities.hasValidatedInternet())
            }
        }

        trySend(isConnectedNow())

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: SecurityException) {
            trySend(false)
        } catch (_: Exception) {
            trySend(false)
        }

        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        .conflate()

    fun isConnectedNow(): Boolean {
        return try {
            connectivityManager.activeNetwork
                ?.let(connectivityManager::getNetworkCapabilities)
                ?.hasValidatedInternet()
                ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun NetworkCapabilities.hasValidatedInternet(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
