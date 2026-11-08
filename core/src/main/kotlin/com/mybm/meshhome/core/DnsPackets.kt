package com.mybm.meshhome.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Local IPv4 DNS endpoint only. Mesh IPv4/IPv6 packets are passed to libtelio unchanged. */
object DnsPackets {
    const val ADDRESS = "198.18.0.1"
    private val address = byteArrayOf(198.toByte(), 18, 0, 1)
    data class Packet(val source: ByteArray, val sourcePort: Int, val protocol: Int, val data: ByteArray)

    fun isDnsDestination(packet: ByteArray): Boolean = packet.size >= 20 &&
        (packet[0].toInt() ushr 4 and 15) == 4 && packet.copyOfRange(16, 20).contentEquals(address)

    fun decode(packet: ByteArray): Packet? = runCatching {
        require(isDnsDestination(packet))
        val offset = (packet[0].toInt() and 15) * 4
        val total = u16(packet, 2)
        require(offset >= 20 && total == packet.size && total >= offset + 8)
        require(u16(packet, 6) and 0x3fff == 0) // No fragmented DNS IP packets.
        val protocol = packet[9].toInt() and 255
        require(protocol == 17 || protocol == 6)
        require(u16(packet, offset + 2) == 53)
        val transport = packet.copyOfRange(offset, total)
        if (protocol == 17) require(u16(transport, 4) == transport.size)
        if (protocol == 6) require(transport.size >= 20)
        Packet(packet.copyOfRange(12, 16), u16(transport, 0), protocol, transport)
    }.getOrNull()

    fun udpReply(packet: Packet, payload: ByteArray): ByteArray {
        require(payload.size <= 65507)
        val udp = ByteArray(payload.size + 8)
        put16(udp, 0, 53); put16(udp, 2, packet.sourcePort); put16(udp, 4, udp.size)
        payload.copyInto(udp, 8)
        put16(udp, 6, transportChecksum(packet.source, 17, udp).let { if (it == 0) 65535 else it })
        return wrap(packet.source, 17, udp)
    }

    fun tcpReply(packet: Packet, seq: Long, ack: Long, flags: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val tcp = ByteArray(20 + data.size)
        put16(tcp, 0, 53); put16(tcp, 2, packet.sourcePort)
        put32(tcp, 4, seq); put32(tcp, 8, ack)
        tcp[12] = 0x50; tcp[13] = flags.toByte(); put16(tcp, 14, 32768)
        data.copyInto(tcp, 20)
        put16(tcp, 16, transportChecksum(packet.source, 6, tcp))
        return wrap(packet.source, 6, tcp)
    }

    private fun wrap(destination: ByteArray, protocol: Int, transport: ByteArray): ByteArray {
        val ip = ByteArray(20 + transport.size)
        ip[0] = 0x45; put16(ip, 2, ip.size); ip[8] = 64; ip[9] = protocol.toByte()
        address.copyInto(ip, 12); destination.copyInto(ip, 16)
        put16(ip, 10, checksum(ip.copyOfRange(0, 20)))
        transport.copyInto(ip, 20)
        return ip
    }

    private fun transportChecksum(destination: ByteArray, protocol: Int, transport: ByteArray): Int {
        val pseudo = ByteArray(12 + transport.size)
        address.copyInto(pseudo); destination.copyInto(pseudo, 4)
        pseudo[9] = protocol.toByte(); put16(pseudo, 10, transport.size)
        transport.copyInto(pseudo, 12)
        return checksum(pseudo)
    }

    fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        for (i in bytes.indices step 2) sum += ((bytes[i].toInt() and 255) shl 8) +
            if (i + 1 < bytes.size) bytes[i + 1].toInt() and 255 else 0
        while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.toInt().inv() and 65535
    }
    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
    fun u32(b: ByteArray, i: Int) = (0..3).fold(0L) { n, k -> (n shl 8) or (b[i + k].toLong() and 255) }
    fun put16(b: ByteArray, i: Int, n: Int) { b[i] = (n ushr 8).toByte(); b[i + 1] = n.toByte() }
    fun put32(b: ByteArray, i: Int, n: Long) { (0..3).forEach { b[i + it] = (n ushr (24 - it * 8)).toByte() } }
}

/**
 * Bounded local DNS-over-TCP endpoint (not a general TCP proxy).
 * In-order reassembly, duplicate ACKs, response retransmission, MSS-sized output,
 * persistent framing, idle expiry. Out-of-order segments are ACKed for retransmission.
 */
class DnsTcp(private val respond: (ByteArray) -> ByteArray) {
    private data class Pending(val seq: Long, val flags: Int, val bytes: ByteArray, var sentAt: Long, var tries: Int = 0)
    private data class Flow(
        val packet: DnsPackets.Packet, var receive: Long, var send: Long, var last: Long,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        val pending: MutableList<Pending> = mutableListOf(), var closing: Boolean = false,
    )
    private val flows = mutableMapOf<String, Flow>()
    private val random = SecureRandom()
    private fun key(p: DnsPackets.Packet) = p.source.joinToString(".") + ":${p.sourcePort}"
    private fun next(n: Long) = n and 0xffffffffL
    private fun afterOrEqual(a: Long, b: Long) = (a - b).toInt() >= 0

    @Synchronized fun accept(p: DnsPackets.Packet, now: Long): List<ByteArray> {
        val b = p.data
        if (b.size < 20) return emptyList()
        val offset = ((b[12].toInt() ushr 4) and 15) * 4
        if (offset < 20 || offset > b.size) return emptyList()
        val flags = b[13].toInt() and 255
        val seq = DnsPackets.u32(b, 4); val ack = DnsPackets.u32(b, 8)
        val id = key(p)
        if (flags and 4 != 0) { flows.remove(id); return emptyList() }
        if (flags and 2 != 0) {
            val old = flows[id]
            if (old != null && old.receive == next(seq + 1)) return old.pending.map { wire(old, it) }
            if (flows.size >= 64) return emptyList()
            val start = random.nextInt().toLong() and 0xffffffffL
            val flow = Flow(p, next(seq + 1), next(start + 1), now)
            flow.pending += Pending(start, 0x12, byteArrayOf(), now)
            flows[id] = flow
            return flow.pending.map { wire(flow, it) }
        }
        val f = flows[id] ?: return listOf(DnsPackets.tcpReply(p, ack, next(seq + b.size - offset), 0x14))
        f.last = now
        if (flags and 16 == 0 || !afterOrEqual(f.send, ack)) return emptyList()
        f.pending.removeAll { afterOrEqual(ack, next(it.seq + it.bytes.size + if (it.flags and 3 != 0) 1 else 0)) }
        if (f.closing && f.pending.isEmpty()) { flows.remove(id); return emptyList() }
        if (seq != f.receive) return listOf(DnsPackets.tcpReply(p, f.send, f.receive, 0x10))
        val length = b.size - offset
        val output = mutableListOf<ByteArray>()
        if (length > 0 && !f.closing) {
            if (f.buffer.size() + length > 65537 || f.pending.sumOf { it.bytes.size } > 65537) {
                flows.remove(id); return listOf(DnsPackets.tcpReply(p, f.send, f.receive, 0x14))
            }
            f.buffer.write(b, offset, length); f.receive = next(f.receive + length)
            var data = f.buffer.toByteArray()
            var messages = 0
            while (data.size >= 2 && messages++ < 16) {
                val size = DnsPackets.u16(data, 0)
                if (size < 12) { flows.remove(id); return listOf(DnsPackets.tcpReply(p, f.send, f.receive, 0x14)) }
                if (data.size < size + 2) break
                val response = respond(data.copyOfRange(2, size + 2))
                require(response.size <= 65535)
                val framed = ByteArray(response.size + 2)
                if (f.pending.sumOf { it.bytes.size } + framed.size > 65537) {
                    flows.remove(id); return listOf(DnsPackets.tcpReply(p, f.send, f.receive, 0x14))
                }
                DnsPackets.put16(framed, 0, response.size); response.copyInto(framed, 2)
                framed.toList().chunked(1200).forEach { chunk ->
                    val pending = Pending(f.send, 0x18, chunk.toByteArray(), now)
                    f.pending += pending; output += wire(f, pending); f.send = next(f.send + chunk.size)
                }
                data = data.copyOfRange(size + 2, data.size)
            }
            if (data.size >= 2 && data.size >= DnsPackets.u16(data, 0) + 2) {
                flows.remove(id); return listOf(DnsPackets.tcpReply(p, f.send, f.receive, 0x14))
            }
            f.buffer.reset(); f.buffer.write(data)
            if (output.isEmpty()) output += DnsPackets.tcpReply(p, f.send, f.receive, 0x10)
        }
        if (flags and 1 != 0 && !f.closing) {
            f.receive = next(f.receive + 1); f.closing = true
            val pending = Pending(f.send, 0x11, byteArrayOf(), now)
            f.pending += pending; output += wire(f, pending); f.send = next(f.send + 1)
        }
        return output
    }

    @Synchronized fun tick(now: Long): List<ByteArray> {
        flows.entries.removeAll { now - it.value.last >= 30_000 || it.value.pending.any { p -> p.tries >= 4 && now >= retransmitAt(p) } }
        return flows.values.flatMap { f -> f.pending.filter { now >= retransmitAt(it) }.map {
            it.sentAt = now; it.tries++; wire(f, it)
        } }
    }

    /** No ambient timer: arm only the earliest outstanding retransmission/flow-expiry deadline. */
    @Synchronized fun nextDeadline(): Long? = flows.values.minOfOrNull { flow ->
        minOf(flow.last + 30_000, flow.pending.minOfOrNull(::retransmitAt) ?: Long.MAX_VALUE)
    }
    private fun retransmitAt(p: Pending): Long = p.sentAt + (1000L shl p.tries.coerceAtMost(4))
    private fun wire(flow: Flow, pending: Pending) = DnsPackets.tcpReply(flow.packet, pending.seq, flow.receive, pending.flags, pending.bytes)
}
