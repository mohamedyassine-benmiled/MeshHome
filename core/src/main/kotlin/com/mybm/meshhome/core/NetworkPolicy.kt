package com.mybm.meshhome.core

enum class NetworkMode { WIFI_ONLY, ALL_NETWORKS }
enum class Transport { WIFI, CELLULAR, OTHER }

/** Snapshot of the actual selected, non-VPN underlying network. */
data class UnderlyingNetwork(val transport: Transport, val ssid: String? = null)

sealed interface PolicyDecision {
    data object Eligible : PolicyDecision
    data object HomeBypass : PolicyDecision
    data object NoNetwork : PolicyDecision
    data object WifiRequired : PolicyDecision
    data object NoSelectedApps : PolicyDecision
}

object NetworkPolicy {
    fun evaluate(
        mode: NetworkMode,
        homeSsids: Set<String>,
        network: UnderlyingNetwork?,
        selectedApps: Set<String>,
        installedApps: Set<String>,
    ): PolicyDecision {
        if (network == null) return PolicyDecision.NoNetwork
        if (network.transport == Transport.WIFI && !network.ssid.isNullOrBlank() && network.ssid in homeSsids) {
            return PolicyDecision.HomeBypass
        }
        if (mode == NetworkMode.WIFI_ONLY && network.transport != Transport.WIFI) return PolicyDecision.WifiRequired
        if (selectedApps.intersect(installedApps).isEmpty()) return PolicyDecision.NoSelectedApps
        return PolicyDecision.Eligible
    }
}
