package com.mybm.meshhome.core

import org.junit.Assert.*
import org.junit.Test

class DnsPacketTest {
    @Test fun emptyDnsTcpEndpointNeedsNoTimer() {
        assertNull(DnsTcp { it }.nextDeadline())
    }

    @Test fun dnsTcpDeadlinesExistOnlyForLiveFlowsAndBackOffRetransmission() {
        val endpoint = DnsTcp { it }
        endpoint.accept(tcp(100, 0, 2), 0)
        assertEquals(1000L, endpoint.nextDeadline())
        endpoint.tick(1000)
        assertEquals(3000L, endpoint.nextDeadline())
        endpoint.tick(3000)
        assertEquals(7000L, endpoint.nextDeadline())
        endpoint.tick(31_000)
        assertNull(endpoint.nextDeadline())
    }
    private val source = byteArrayOf(100, 64, 1, 8)
    private fun tcp(seq: Long, ack: Long, flags: Int, payload: ByteArray = byteArrayOf()): DnsPackets.Packet {
        val bytes = ByteArray(20 + payload.size)
        DnsPackets.put16(bytes, 0, 45000); DnsPackets.put16(bytes, 2, 53)
        DnsPackets.put32(bytes, 4, seq); DnsPackets.put32(bytes, 8, ack)
        bytes[12] = 0x50; bytes[13] = flags.toByte(); DnsPackets.put16(bytes, 14, 65535)
        payload.copyInto(bytes, 20)
        return DnsPackets.Packet(source, 45000, 6, bytes)
    }

    @Test fun udpReplyHasValidIpAndTransportChecksums() {
        val p = DnsPackets.Packet(source, 45000, 17, ByteArray(8))
        val reply = DnsPackets.udpReply(p, ByteArray(31) { it.toByte() })
        assertEquals(0, DnsPackets.checksum(reply.copyOfRange(0, 20)))
        assertEquals(reply.size, DnsPackets.u16(reply, 2))
        val pseudo = ByteArray(12 + reply.size - 20)
        reply.copyInto(pseudo, 0, 12, 20); pseudo[9] = 17
        DnsPackets.put16(pseudo, 10, reply.size - 20); reply.copyInto(pseudo, 12, 20)
        assertEquals(0, DnsPackets.checksum(pseudo))
    }

    @Test fun dnsDestinationDecodeRejectsFragmentsAndBadLengths() {
        val request = ByteArray(40)
        request[0] = 0x45; request[9] = 17
        DnsPackets.put16(request, 2, request.size)
        byteArrayOf(198.toByte(), 18, 0, 1).copyInto(request, 16)
        DnsPackets.put16(request, 22, 53); DnsPackets.put16(request, 24, 20)
        assertNotNull(DnsPackets.decode(request))
        val fragment = request.copyOf(); DnsPackets.put16(fragment, 6, 0x2000)
        assertNull(DnsPackets.decode(fragment))
        val short = request.copyOf(); DnsPackets.put16(short, 2, 200)
        assertNull(DnsPackets.decode(short))
        assertNull(DnsPackets.decode(request.copyOf(19)))
    }

    @Test fun tcpHandshakeReassemblesFramesAndRetransmitsResponses() {
        var calls = 0
        val tcp = DnsTcp { calls++; it }
        val syn = tcp.accept(tcp(100, 0, 2), 0).single()
        val initial = DnsPackets.u32(syn, 24)
        tcp.accept(tcp(101, initial + 1, 16), 1)
        val query = ByteArray(14); DnsPackets.put16(query, 0, 12)
        val first = tcp.accept(tcp(101, initial + 1, 24, query.copyOfRange(0, 7)), 2)
        assertEquals(16, first.single()[33].toInt() and 255)
        assertEquals(0, calls)
        val replies = tcp.accept(tcp(108, initial + 1, 24, query.copyOfRange(7, 14)), 3)
        assertEquals(1, calls)
        assertArrayEquals(query, replies.single().copyOfRange(40, replies.single().size))
        assertEquals(1, tcp.tick(1004).size)
        // Retransmitted client bytes are acknowledged but not resolved twice.
        tcp.accept(tcp(108, initial + 1, 24, query.copyOfRange(7, 14)), 1005)
        assertEquals(1, calls)
        val end = (initial + 1 + query.size) and 0xffffffffL
        tcp.accept(tcp(115, end, 16), 1006)
        assertTrue(tcp.tick(2007).isEmpty())
    }

    @Test fun tcpExpiresIdleFlowsAndBoundsConnections() {
        val tcp = DnsTcp { it }
        val syn = tcp.accept(tcp(100, 0, 2), 0)
        assertEquals(1, syn.size)
        assertTrue(tcp.tick(31000).isEmpty())
        val reset = tcp.accept(tcp(101, 20, 16), 31001).single()
        assertEquals(0x14, reset[33].toInt() and 255)
    }

    @Test fun tcpOutOfOrderBytesAreNotAccepted() {
        var calls = 0
        val tcp = DnsTcp { calls++; it }
        val syn = tcp.accept(tcp(100, 0, 2), 0).single()
        val start = DnsPackets.u32(syn, 24)
        val response = tcp.accept(tcp(200, (start + 1) and 0xffffffffL, 24, ByteArray(20)), 1).single()
        assertEquals(101L, DnsPackets.u32(response, 28))
        assertEquals(0, calls)
    }
}
