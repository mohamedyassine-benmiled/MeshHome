package com.mybm.meshhome.core

import org.junit.Assert.*
import org.junit.Test

class ClientFirewallTest {
    private val local = "100.64.1.1"
    private val remote = "100.64.1.2"
    private fun firewall(capacity: Int = 4096) = ClientFirewall(listOf(local, "fd00::1"), listOf(remote, "fd00::2"), capacity)

    companion object {
        fun packet(source: String, destination: String, sport: Int = 45000, dport: Int = 443,
                   protocol: Int = 17, flags: Int = 2, seq: Long = 100, ack: Long = 0): ByteArray {
            val src = checkNotNull(IpLiteral.parse(source)); val dst = checkNotNull(IpLiteral.parse(destination))
            val offset = if (src.size == 4) 20 else 40
            val b = ByteArray(offset + if (protocol == 6) 20 else 8)
            if (offset == 20) {
                b[0] = 0x45; b[8] = 64; b[9] = protocol.toByte()
                DnsPackets.put16(b, 2, b.size); src.copyInto(b, 12); dst.copyInto(b, 16)
                DnsPackets.put16(b, 10, DnsPackets.checksum(b.copyOf(20)))
            } else {
                b[0] = 0x60; b[6] = protocol.toByte(); b[7] = 64
                DnsPackets.put16(b, 4, b.size - 40); src.copyInto(b, 8); dst.copyInto(b, 24)
            }
            DnsPackets.put16(b, offset, sport); DnsPackets.put16(b, offset + 2, dport)
            if (protocol == 17) DnsPackets.put16(b, offset + 4, 8) else if (protocol == 6) {
                b[offset + 12] = 0x50; b[offset + 13] = flags.toByte()
                for (i in 0..3) { b[offset + 4 + i] = (seq ushr (24 - i * 8)).toByte(); b[offset + 8 + i] = (ack ushr (24 - i * 8)).toByte() }
            }
            return b
        }
    }
    private fun outgoing(protocol: Int = 17, flags: Int = 2) = packet(local, remote, protocol = protocol, flags = flags)
    private fun reply(protocol: Int = 17, flags: Int = 0x12, ack: Long = 101) = packet(remote, local, 443, 45000, protocol, flags, 500, ack)

    @Test fun unsolicitedAndWrongTupleRepliesAreBlocked() {
        val f = firewall()
        assertFalse(f.incoming(reply(), 0)); assertTrue(f.outgoing(outgoing(), 0)); assertTrue(f.incoming(reply(), 1))
        assertFalse(f.incoming(packet(remote, local, 444, 45000), 2))
        assertFalse(f.incoming(packet("100.64.1.3", local, 443, 45000), 2))
        assertFalse(f.incoming(packet(remote, "100.64.1.3", 443, 45000), 2))
    }
    @Test fun unknownDestinationsAndSpoofedLocalSourcesCannotOpenFlows() {
        val f = firewall()
        assertFalse(f.outgoing(packet(local, "100.64.1.3"), 0))
        assertFalse(f.outgoing(packet("100.64.1.3", remote), 0))
        assertFalse(f.incoming(reply(), 1))
    }
    @Test fun udpExpiresWithoutIdleTimerAndNewOutboundCanReopen() {
        val f = firewall(); assertTrue(f.outgoing(outgoing(), 0))
        assertFalse(f.incoming(reply(), 60_000))
        assertTrue(f.outgoing(outgoing(), 60_001)); assertTrue(f.incoming(reply(), 60_002))
    }
    @Test fun tcpRequiresSynAndCorrectSynAckBeforeData() {
        val f = firewall()
        assertFalse(f.outgoing(outgoing(6, 0x10), 0))
        assertTrue(f.outgoing(outgoing(6), 0))
        assertFalse(f.incoming(reply(6, 2), 1))
        assertFalse(f.incoming(reply(6, 0x10), 1))
        assertFalse(f.incoming(reply(6, ack = 102), 1))
        assertTrue(f.incoming(reply(6), 1))
        assertTrue(f.incoming(reply(6, 0x18, 110), 2))
        assertFalse(f.incoming(reply(6, 2), 3))
        assertFalse(f.incoming(reply(6, 0x12, 200), 3))
    }
    @Test fun tcpResetClosesPinholeAndFinUsesShortExpiry() {
        val f = firewall(); f.outgoing(outgoing(6), 0); f.incoming(reply(6), 1)
        assertTrue(f.incoming(reply(6, 0x14), 2)); assertFalse(f.incoming(reply(6, 0x10), 3))
        f.outgoing(outgoing(6), 4); f.incoming(reply(6), 5)
        assertTrue(f.incoming(reply(6, 0x11), 6)); assertFalse(f.incoming(reply(6, 0x10), 30_006))
    }
    @Test fun unansweredSynExpiresAndCapacityFailsClosedThenReclaimsExpiredEntries() {
        val f = firewall(1); assertTrue(f.outgoing(outgoing(6), 0))
        assertFalse(f.outgoing(packet(local, remote, 45001), 1))
        assertFalse(f.incoming(reply(6), 120_000))
        assertTrue(f.outgoing(packet(local, remote, 45001), 120_001))
        assertFalse(f.incoming(reply(), 120_002))
    }
    @Test fun ipv6UdpAndTcpAreFilteredInBothDirections() {
        for (protocol in listOf(6, 17)) {
            val f = firewall()
            val out = packet("fd00::1", "fd00::2", protocol = protocol)
            val back = packet("fd00::2", "fd00::1", 443, 45000, protocol, 0x12, ack = 101)
            assertFalse(f.incoming(back, 0)); assertTrue(f.outgoing(out, 0)); assertTrue(f.incoming(back, 1))
        }
    }
    @Test fun fragmentsExtensionsCorruptLengthsHeadersAndUnsupportedProtocolsAreDropped() {
        val f = firewall()
        val fragment = outgoing().also { DnsPackets.put16(it, 6, 0x2000); DnsPackets.put16(it, 10, 0); DnsPackets.put16(it, 10, DnsPackets.checksum(it.copyOf(20))) }
        val extension = packet("fd00::1", "fd00::2").also { it[6] = 44 }
        val cases = listOf(byteArrayOf(), byteArrayOf(0x45), outgoing().copyOf(24), fragment, extension,
            outgoing().also { it[0] = 0x44 }, outgoing().also { it[10] = 1 },
            outgoing().also { it[25] = 7 }, packet(local, remote, protocol = 1), outgoing(6).also { it[32] = 0x10 })
        cases.forEach { assertFalse(f.outgoing(it, 0)); assertFalse(f.incoming(it, 0)) }
    }
    @Test fun arbitraryPacketInputNeverThrows() {
        val random = kotlin.random.Random(17); val f = firewall()
        repeat(5000) { val b = random.nextBytes(random.nextInt(256)); f.outgoing(b, 0); f.incoming(b, 0) }
    }
}
