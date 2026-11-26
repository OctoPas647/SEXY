package com.octopas.sexy.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells the app when Tailscale is on. Tailscale on Android is a VPN, so this watches for any
 * VPN network coming up or going away. The app then looks for the PC by itself.
 */
class TailscaleMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val vpns = mutableSetOf<Network>()
    private val _on = MutableStateFlow(currentlyOn())
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private fun currentlyOn(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } == true
    }.getOrDefault(false)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(vpns) { vpns += network }
            _on.value = true
        }

        override fun onLost(network: Network) {
            val any = synchronized(vpns) { vpns -= network; vpns.isNotEmpty() }
            _on.value = any || currentlyOn()
        }
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm?.registerNetworkCallback(request, callback)
        }
    }
}
