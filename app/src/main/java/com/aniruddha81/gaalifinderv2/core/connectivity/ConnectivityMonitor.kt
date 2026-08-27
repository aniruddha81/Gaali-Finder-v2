package com.aniruddha81.gaalifinderv2.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Reports whether the device currently has a network capable of reaching the internet. */
interface ConnectivityMonitor {
    /** Emits the current state immediately, then again on every change. */
    val isOnline: Flow<Boolean>

    /** One-shot snapshot, for guarding a request that is about to be made. */
    fun isCurrentlyOnline(): Boolean
}

@Singleton
class AndroidConnectivityMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ConnectivityMonitor {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService()

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            // Without the service we cannot observe anything; assume online so the user is
            // never blocked from trying, and let the request itself report the real failure.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(capabilities.hasInternet())
            }
        }

        trySend(isCurrentlyOnline())

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // registerNetworkCallback can throw if the process is being torn down or the caller is
        // rate-limited (too many concurrent callbacks); falling back to a static answer keeps
        // the flow alive rather than crashing whoever is collecting it.
        val registered = runCatching { manager.registerNetworkCallback(request, callback) }
            .isSuccess

        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    override fun isCurrentlyOnline(): Boolean = runCatching {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        manager.getNetworkCapabilities(network)?.hasInternet() == true
    }.getOrDefault(true)

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Shared eagerly enough that a screen re-subscribing does not re-register a system callback. */
internal val ConnectivitySharingStrategy: SharingStarted = SharingStarted.WhileSubscribed(5_000)
