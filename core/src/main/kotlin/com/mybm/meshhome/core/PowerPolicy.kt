package com.mybm.meshhome.core

/** Non-wakeup scheduling policy; no timers should be armed while the tunnel is paused. */
object PowerPolicy {
    const val ACTIVE_MAP_REFRESH_MS = 5 * 60_000L
    const val IDLE_MAP_REFRESH_MS = 15 * 60_000L
    fun mapRefreshInterval(interactive: Boolean, powerSave: Boolean): Long =
        if (interactive && !powerSave) ACTIVE_MAP_REFRESH_MS else IDLE_MAP_REFRESH_MS
}

/** Bounded exponential backoff with jitter, avoiding synchronized reconnect storms. */
class RetryBackoff(
    private val initialMs: Long = 5_000,
    private val maximumMs: Long = 5 * 60_000,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    private var failures = 0
    init { require(initialMs > 0 && maximumMs >= initialMs) }
    fun reset() { failures = 0 }
    fun nextDelay(): Long {
        var delay = initialMs
        repeat(failures.coerceAtMost(30)) { delay = if (delay >= maximumMs / 2) maximumMs else delay * 2 }
        failures = (failures + 1).coerceAtMost(31)
        return (delay * (0.8 + random().coerceIn(0.0, 1.0) * 0.4)).toLong().coerceIn(1, maximumMs)
    }
}

/** Only routing/DNS-relevant changes rebuild a TUN. Relay/map metadata is updated in-place. */
@ConsistentCopyVisibility
data class SessionConfiguration private constructor(
    val network: String, val resolvers: List<String>, val upstream: String,
    val apps: List<String>, val localAddresses: List<String>, val rules: List<String>, val targets: List<String>,
) {
    companion object {
        fun create(network: String, resolvers: List<String>, upstream: String, apps: Set<String>,
                   localAddresses: List<String>, rules: List<DnsRule>, devices: List<MeshDevice>): SessionConfiguration {
            val enabled = rules.filter { it.enabled }
            return SessionConfiguration(network, resolvers.toList(), upstream, apps.sorted(), localAddresses.sorted(),
                enabled.map { "${it.pattern.value}=${it.deviceId}" }.sorted(),
                enabled.map { it.deviceId }.distinct().sorted().map { id ->
                    val device = devices.singleOrNull { it.id == id }
                    "$id=${device?.let { (it.ipv4 + it.ipv6).sorted() } ?: "missing"}"
                })
        }
    }
}
