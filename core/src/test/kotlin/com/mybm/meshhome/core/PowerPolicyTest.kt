package com.mybm.meshhome.core

import org.junit.Assert.*
import org.junit.Test

class PowerPolicyTest {
    @Test fun refreshIsFiveMinutesInForegroundAndFifteenWhenIdleOrSavingPower() {
        assertEquals(300_000, PowerPolicy.mapRefreshInterval(true, false))
        assertEquals(900_000, PowerPolicy.mapRefreshInterval(false, false))
        assertEquals(900_000, PowerPolicy.mapRefreshInterval(true, true))
        assertEquals(900_000, PowerPolicy.mapRefreshInterval(false, true))
    }
    @Test fun reconnectBackoffIsExponentialBoundedAndResettable() {
        val backoff = RetryBackoff(random = { 0.5 })
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L), (0..6).map { backoff.nextDelay() })
        repeat(100) { assertEquals(300_000L, backoff.nextDelay()) }
        backoff.reset(); assertEquals(5_000L, backoff.nextDelay())
    }
    @Test fun jitterIsBoundedAndDoesNotOverflowAtMaximumFailures() {
        val lower = RetryBackoff(random = { 0.0 })
        val upper = RetryBackoff(random = { 1.0 })
        assertEquals(4_000L, lower.nextDelay()); assertEquals(6_000L, upper.nextDelay())
        repeat(100) { assertTrue(lower.nextDelay() in 1..300_000); assertTrue(upper.nextDelay() in 1..300_000) }
    }
    @Test fun apiRefreshUsesIndependentLongerBackoff() {
        val backoff = RetryBackoff(30_000, 900_000, { 0.5 })
        assertEquals(30_000L, backoff.nextDelay()); assertEquals(60_000L, backoff.nextDelay())
        repeat(100) { assertTrue(backoff.nextDelay() <= 900_000) }
    }

    private val rules = listOf(DnsRule(DomainPattern.parse("*.example.org"), "a"), DnsRule(DomainPattern.parse("other.org"), "b"))
    private val devices = listOf(MeshDevice("a", "A", listOf("100.64.1.2")), MeshDevice("b", "B", listOf("100.64.1.3")))
    private fun config(network: String = "wifi", rules: List<DnsRule> = this.rules, devices: List<MeshDevice> = this.devices,
                       apps: Set<String> = setOf("app", "browser"), dns: List<String> = listOf("1.1.1.1", "8.8.8.8")) =
        SessionConfiguration.create(network, dns, "", apps, listOf("100.64.1.1"), rules, devices)

    @Test fun irrelevantPeerNamesAndListOrderDoNotRecreateTunnel() {
        assertEquals(config(), config(rules = rules.reversed(), devices = devices.reversed().map { it.copy(name = "Renamed") }, apps = linkedSetOf("browser", "app")))
    }
    @Test fun changingNetworkResolverAppsOrTargetAddressRecreatesTunnel() {
        assertNotEquals(config(), config(network = "cellular"))
        assertNotEquals(config(), config(dns = listOf("8.8.8.8", "1.1.1.1")))
        assertNotEquals(config(), config(apps = setOf("browser")))
        assertNotEquals(config(), config(devices = devices.map { it.copy(ipv4 = listOf("100.64.2.2")) }))
    }
    @Test fun swappedTargetAddressesMustNotLeaveDnsStaleEvenIfRouteSetIsUnchanged() {
        assertNotEquals(config(), config(devices = listOf(devices[0].copy(ipv4 = devices[1].ipv4), devices[1].copy(ipv4 = devices[0].ipv4))))
    }
    @Test fun disabledAndUnrelatedDevicesDoNotRecreateTunnel() {
        assertEquals(config(), config(rules = rules + DnsRule(DomainPattern.parse("disabled.org"), "unknown", false)))
        assertEquals(config(), config(devices = devices + MeshDevice("unrelated", "Other", listOf("100.64.3.2"))))
    }
    @Test fun missingTargetsAndEnablingRulesDoChangeDnsConfiguration() {
        assertNotEquals(config(), config(devices = listOf(devices[0])))
        assertNotEquals(config(), config(rules = listOf(rules[0], rules[1].copy(enabled = false))))
    }
}
