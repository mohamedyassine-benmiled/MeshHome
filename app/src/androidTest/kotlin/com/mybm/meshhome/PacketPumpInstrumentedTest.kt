package com.mybm.meshhome

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.mybm.meshhome.core.*
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

/** Real Android FD tests, no account, VPN permission, settings writes or external traffic. */
@RunWith(AndroidJUnit4::class)
class PacketPumpInstrumentedTest {
    private class Fixture(rules: List<DnsRule> = emptyList(), peers: List<MeshDevice> = emptyList()) : AutoCloseable {
        val far = FileDescriptor()
        val failures = AtomicInteger()
        val upstreamCalls = AtomicInteger()
        val pump: PacketPump
        val native: ParcelFileDescriptor
        init {
            val near = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, near, far)
            val tun = ParcelFileDescriptor.dup(near); Os.close(near)
            pump = PacketPump(tun, rules, peers, { upstreamCalls.incrementAndGet(); error("No network calls allowed") },
                listOf("100.64.1.1"), listOf("100.64.1.2")) { failures.incrementAndGet() }
            native = ParcelFileDescriptor.adoptFd(pump.nativeFd())
            pump.start()
        }
        override fun close() { pump.close(); native.close(); Os.close(far) }
    }
    private fun read(fd: FileDescriptor): ByteArray {
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        assertTrue("Packet not received", Os.poll(arrayOf(poll), 2000) > 0)
        val bytes = ByteArray(65535)
        return bytes.copyOf(Os.read(fd, bytes, 0, bytes.size))
    }

    private fun udp(source: String, destination: String, sport: Int, dport: Int): ByteArray {
        val b = ByteArray(28)
        b[0] = 0x45; b[8] = 64; b[9] = 17
        DnsPackets.put16(b, 2, b.size)
        checkNotNull(IpLiteral.parse(source)).copyInto(b, 12)
        checkNotNull(IpLiteral.parse(destination)).copyInto(b, 16)
        DnsPackets.put16(b, 20, sport); DnsPackets.put16(b, 22, dport); DnsPackets.put16(b, 24, 8)
        DnsPackets.put16(b, 10, DnsPackets.checksum(b.copyOf(20)))
        return b
    }

    @Test fun idleReaderIsWokenByShutdownWithoutAnIdlePollingTimeout() {
        Fixture().use { fixture ->
            assertTrue(fixture.pump.isRunning)
            val before = SystemClock.elapsedRealtime()
            fixture.pump.close()
            assertFalse(fixture.pump.isRunning)
            assertTrue("Shutdown pipe did not release blocking poll", SystemClock.elapsedRealtime() - before < 1000)
            assertEquals(0, fixture.failures.get())
            assertEquals(0, fixture.upstreamCalls.get())
        }
    }

    @Test fun packetBoundariesArePreservedInBothDirections() {
        Fixture().use { fixture ->
            val outgoing = udp("100.64.1.1", "100.64.1.2", 45000, 443)
            Os.write(fixture.far, outgoing, 0, outgoing.size)
            assertArrayEquals(outgoing, read(fixture.native.fileDescriptor))
            val incoming = udp("100.64.1.2", "100.64.1.1", 443, 45000)
            Os.write(fixture.native.fileDescriptor, incoming, 0, incoming.size)
            assertArrayEquals(incoming, read(fixture.far))
            assertEquals(0, fixture.failures.get())
        }
    }

    @Test fun unsolicitedNativeTrafficCannotReachTun() {
        Fixture().use { fixture ->
            val incoming = udp("100.64.1.2", "100.64.1.1", 443, 45000)
            Os.write(fixture.native.fileDescriptor, incoming, 0, incoming.size)
            val poll = StructPollfd().apply { fd = fixture.far; events = OsConstants.POLLIN.toShort() }
            assertEquals(0, Os.poll(arrayOf(poll), 250))
            assertEquals(0, fixture.failures.get())
        }
    }

    @Test fun matchingDnsIsAnsweredLocallyWithoutWakingAnUpstreamResolver() {
        val rule = DnsRule(DomainPattern.parse("*.example.org"), "server")
        Fixture(listOf(rule), listOf(MeshDevice("server", "Server", listOf("100.64.1.2")))).use { fixture ->
            val labels = "photos.example.org".split('.')
            val dns = java.io.ByteArrayOutputStream().apply {
                write(byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                labels.forEach { write(it.length); write(it.toByteArray()) }
                write(byteArrayOf(0, 0, 1, 0, 1))
            }.toByteArray()
            val packet = ByteArray(28 + dns.size)
            packet[0] = 0x45; packet[8] = 64; packet[9] = 17
            DnsPackets.put16(packet, 2, packet.size)
            byteArrayOf(100, 64, 1, 1).copyInto(packet, 12)
            checkNotNull(IpLiteral.parse(DnsPackets.ADDRESS)).copyInto(packet, 16)
            DnsPackets.put16(packet, 20, 45000); DnsPackets.put16(packet, 22, 53); DnsPackets.put16(packet, 24, 8 + dns.size)
            DnsPackets.put16(packet, 10, DnsPackets.checksum(packet.copyOf(20)))
            dns.copyInto(packet, 28)
            Os.write(fixture.far, packet, 0, packet.size)
            val response = read(fixture.far)
            assertEquals(0x1234, DnsPackets.u16(response, 28))
            assertArrayEquals(byteArrayOf(100, 64, 1, 2), response.takeLast(4).toByteArray())
            assertEquals(0, fixture.upstreamCalls.get())
        }
    }
}
