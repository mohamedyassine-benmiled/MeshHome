package com.mybm.meshhome.core

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val server = MeshDevice("server", "Home", listOf("100.64.1.2"))
    private val backup = MeshDevice("backup", "Backup", listOf("100.64.1.3"))
    private fun rule(pattern: String, id: String = "server", enabled: Boolean = true) =
        DnsRule(DomainPattern.parse(pattern), id, enabled)

    @Test fun exactMatchesOnlyItself() {
        val pattern = DomainPattern.parse("example.org.")
        assertTrue(pattern.matches("example.org"))
        assertFalse(pattern.matches("photos.example.org"))
    }

    @Test fun wildcardMatchesDescendantsNotApexOrLookalikes() {
        val pattern = DomainPattern.parse("*.example.org")
        assertTrue(pattern.matches("PHOTOS.example.org."))
        assertTrue(pattern.matches("photos.private.example.org"))
        assertFalse(pattern.matches("example.org"))
        assertFalse(pattern.matches("evilexample.org"))
        assertFalse(pattern.matches("photos.example.org.evil.org"))
    }

    @Test fun precedenceAndMultipleDevices() {
        val rules = DnsRules(listOf(rule("*.example.org"), rule("*.example.net", "backup"),
            rule("photos.example.org")))
        assertEquals(DnsDecision.Answer(server.ipv4), rules.resolve("photos.example.org", QueryType.A, listOf(server, backup)))
        assertEquals(DnsDecision.Answer(backup.ipv4), rules.resolve("video.example.net", QueryType.A, listOf(server, backup)))
    }

    @Test fun missingTargetsNeverFallBack() {
        val rules = DnsRules(listOf(rule("*.example.org")))
        assertEquals(DnsDecision.TargetUnavailable, rules.resolve("photos.example.org", QueryType.A, emptyList()))
        assertEquals(DnsDecision.Answer(emptyList<String>()), rules.resolve("photos.example.org", QueryType.AAAA, listOf(server)))
        assertEquals(DnsDecision.Answer(emptyList<String>()), rules.resolve("photos.example.org", QueryType.OTHER, listOf(server)))
        assertEquals(DnsDecision.Forward, rules.resolve("example.org", QueryType.A, listOf(server)))
    }

    @Test fun disabledRulesDoNotMatch() {
        assertEquals(DnsDecision.Forward, DnsRules(listOf(rule("example.org", enabled = false))).resolve("example.org", QueryType.A, listOf(server)))
    }

    @Test fun duplicateNormalizedRulesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DnsRules(listOf(rule("EXAMPLE.ORG."), rule("example.org"))) }
    }

    @Test fun malformedPatternsAreRejected() {
        listOf("", "*", "foo.*.org", "https://example.org", "foo..org", "example.org..", "-bad.org").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { DomainPattern.parse(it) }
        }
        assertEquals("xn--bcher-kva.example", DomainPattern.parse("bücher.example").value)
    }

    private fun policy(network: UnderlyingNetwork?, apps: Set<String> = setOf("app"), mode: NetworkMode = NetworkMode.WIFI_ONLY) =
        NetworkPolicy.evaluate(mode, setOf("Home"), network, apps, setOf("app"))

    @Test fun homeBypassRequiresActualWifiAndKnownSsid() {
        assertEquals(PolicyDecision.HomeBypass, policy(UnderlyingNetwork(Transport.WIFI, "Home")))
        assertEquals(PolicyDecision.Eligible, policy(UnderlyingNetwork(Transport.WIFI)))
        assertEquals(PolicyDecision.Eligible, policy(UnderlyingNetwork(Transport.CELLULAR, "Home"), mode = NetworkMode.ALL_NETWORKS))
    }

    @Test fun wifiOnlyNeverApprovesCellularOrUnknownTransport() {
        assertEquals(PolicyDecision.WifiRequired, policy(UnderlyingNetwork(Transport.CELLULAR)))
        assertEquals(PolicyDecision.WifiRequired, policy(UnderlyingNetwork(Transport.OTHER)))
        assertEquals(PolicyDecision.NoNetwork, policy(null))
    }

    @Test fun emptyOrUninstalledAllowlistNeverApprovesTunnel() {
        assertEquals(PolicyDecision.NoSelectedApps, policy(UnderlyingNetwork(Transport.WIFI), emptySet()))
        assertEquals(PolicyDecision.NoSelectedApps, policy(UnderlyingNetwork(Transport.WIFI), setOf("uninstalled")))
    }
}
