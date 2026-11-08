package com.mybm.meshhome

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.mybm.meshhome.core.*

data class CurrentNetwork(val network: Network, val snapshot: UnderlyingNetwork, val dns: List<String>, val strictPrivateDns: Boolean,
                          val captivePortal: Boolean = false, val bindingKey: String = network.toString())

object Networks {
    @SuppressLint("MissingPermission")
    fun current(context: Context): CurrentNetwork? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val candidates = manager.allNetworks.mapNotNull { n -> manager.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val network = candidates.firstOrNull { it.first == manager.activeNetwork }?.first
            ?: candidates.maxByOrNull { priority(it.second) }?.first ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        // Android may report the VPN to its owner even when the owner's UID is excluded.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return describe(context, network, caps, manager.getLinkProperties(network), readSsid = false)
    }

    private fun priority(caps: NetworkCapabilities): Int =
        (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0) +
            (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 10 else 0)

    @SuppressLint("MissingPermission")
    private fun describe(context: Context, network: Network, caps: NetworkCapabilities, links: LinkProperties?, readSsid: Boolean): CurrentNetwork? {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
        var ssid: String? = null
        if (transport == Transport.WIFI && readSsid) {
            ssid = runCatching {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    !context.getSystemService(LocationManager::class.java).isLocationEnabled) return@runCatching null
                // Read fresh permission-filtered data only when home bypass needs
                // it; don't reuse a location-sensitive capability cached before a
                // permission/location-mode change. We only use the default Wi-Fi.
                @Suppress("DEPRECATION")
                val info = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
                info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            }.getOrNull()
        }
        val dns = links?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        return CurrentNetwork(network, UnderlyingNetwork(transport, ssid), dns, !links?.privateDnsServerName.isNullOrBlank(),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL), bindingIdentity(network, links))
    }

    private fun bindingIdentity(network: Network, links: LinkProperties?): String = listOf(network.toString(), links?.interfaceName,
        links?.linkAddresses?.map { it.toString() }?.sorted(), links?.routes?.map { it.toString() }?.sorted(), links?.mtu).joinToString("|")

    fun accountNetwork(context: Context, settings: SettingsStore): Network {
        val current = checkNotNull(current(context)) { "No direct network available. Disconnect other VPN apps first." }
        check(settings.mode == NetworkMode.ALL_NETWORKS || current.snapshot.transport == Transport.WIFI) { "Wi-Fi-only mode: connect to Wi-Fi first." }
        return current.network
    }

    /** Track physical networks, never the VPN presented as its owner's default network.
     * Properties come from callbacks; incomplete onAvailable entries are not usable yet.
     */
    class Monitor(private val context: Context, private val changed: (invalidateSession: Boolean) -> Unit) {
        private val events = java.util.concurrent.ConcurrentLinkedDeque<String>()
        fun diagnostics(): String = events.joinToString("; ")
        private fun notifyChange(invalidate: Boolean, reason: String) {
            events.addLast("${android.os.SystemClock.elapsedRealtime()}: $reason halt=$invalidate")
            while (events.size > 12) events.pollFirst()
            changed(invalidate)
        }
        private val manager = context.getSystemService(ConnectivityManager::class.java)
        private val lock = Any()
        private data class State(var caps: NetworkCapabilities? = null, var links: LinkProperties? = null, var blocked: Boolean = false)
        private val physical = mutableMapOf<Network, State>()
        private var selectedBinding: String? = null
        private fun selected(): Pair<Network, State>? {
            val candidates = physical.entries.filter { (_, s) -> !s.blocked && s.caps != null && s.links != null }
            val entry = candidates.firstOrNull { it.key == manager.activeNetwork }
                ?: candidates.maxByOrNull { priority(checkNotNull(it.value.caps)) }
            return entry?.let { it.key to it.value }
        }
        private fun updated(reason: String) {
            val invalidate = synchronized(lock) {
                val next = selected()?.let { (n, s) -> bindingIdentity(n, s.links) }
                val different = selectedBinding != null && selectedBinding != next
                selectedBinding = next
                different
            }
            notifyChange(invalidate, reason)
        }
        private inner class Callback : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                synchronized(lock) { physical.putIfAbsent(value, State()) }
                updated("available $value")
            }
            override fun onLost(value: Network) {
                synchronized(lock) { physical.remove(value) }
                updated("lost $value")
            }
            override fun onCapabilitiesChanged(value: Network, properties: NetworkCapabilities) {
                synchronized(lock) { physical[value]?.caps = NetworkCapabilities(properties) }
                updated("capabilities $value")
            }
            override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) {
                synchronized(lock) { physical[value]?.links = properties }
                updated("links $value interface=${properties.interfaceName}")
            }
            override fun onBlockedStatusChanged(value: Network, blocked: Boolean) {
                synchronized(lock) { physical[value]?.blocked = blocked }
                updated("blocked $value=$blocked")
            }
        }
        private val callback = Callback()
        fun start() {
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        }
        fun stop() { manager.unregisterNetworkCallback(callback) }
        fun snapshot(readSsid: Boolean): CurrentNetwork? {
            val state = synchronized(lock) { selected()?.let { (n, s) -> Triple(n, s.caps, s.links) } } ?: return null
            return describe(context, state.first, state.second ?: return null, state.third ?: return null, readSsid)
        }
    }
}
