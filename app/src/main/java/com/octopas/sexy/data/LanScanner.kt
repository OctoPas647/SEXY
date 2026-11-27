package com.octopas.sexy.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds AI servers on the Wi-Fi the phone is on, without Tailscale: knocks on [PORTS] of every
 * address in the phone's /24 (e.g. 192.168.1.1-254) and returns the ones that accept a connection.
 * Only open ports are returned; [ServerDiscovery.find] then checks which of them is really an AI server.
 */
object LanScanner {
    /** Your laptop's llama.cpp server first, then LM Studio, Ollama and llama.cpp's default. */
    val PORTS = listOf(9931, 1234, 11434, 8080)

    private const val CONNECT_TIMEOUT_MS = 350
    private const val PARALLEL = 96

    /** The phone's own IPv4 address on Wi-Fi / Ethernet, e.g. "192.168.1.23" (null when not on Wi-Fi). */
    fun phoneLanIp(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        cm.allNetworks.firstNotNullOfOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
            cm.getLinkProperties(n)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }
    }.getOrNull()

    /** Every other address in the same /24 as [ownIp] (".1" to ".254"), nearest-to-router first. */
    fun neighbours(ownIp: String): List<String> {
        val parts = ownIp.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != ownIp }
    }

    /** "http://ip:port" for every neighbour with one of [ports] open. Takes ~2-4 s on a normal home Wi-Fi. */
    suspend fun scan(context: Context, ports: List<Int> = PORTS): List<String> {
        val own = phoneLanIp(context) ?: return emptyList()
        return scanHosts(neighbours(own), ports)
    }

    suspend fun scanHosts(hosts: List<String>, ports: List<Int> = PORTS): List<String> = withContext(Dispatchers.IO) {
        val gate = Semaphore(PARALLEL)
        // Port order first, so a hit on 9931 is listed before a hit on 1234 on another machine.
        ports.flatMap { p -> hosts.map { h -> h to p } }
            .map { (h, p) -> async { gate.withPermit { if (isOpen(h, p)) "http://$h:$p" else null } } }
            .awaitAll()
            .filterNotNull()
    }

    private fun isOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS); true }
    }.getOrDefault(false)
}
