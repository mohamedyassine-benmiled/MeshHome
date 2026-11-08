package com.mybm.meshhome.core

/** Client-only tuple/handshake filter, not a general router or TCP stack.
 * Runs on the single packet-reader thread. Expiry is lazy: no idle timers.
 * Native decrypted packets MUST pass here before entering Android's TUN.
 */
class ClientFirewall(localAddresses: List<String>, targetAddresses: List<String>, private val capacity: Int = 4096) {
    private val locals = localAddresses.map { checkNotNull(IpLiteral.parse(it)).toList() }.toSet()
    private val targets = targetAddresses.map { checkNotNull(IpLiteral.parse(it)).toList() }.toSet()
    private data class Key(val local: List<Byte>, val remote: List<Byte>, val localPort: Int, val remotePort: Int, val protocol: Int)
    private data class Flow(var expires: Long, val synSequence: Long, var established: Boolean = false, var closing: Boolean = false)
    private data class Packet(val source: List<Byte>, val destination: List<Byte>, val sourcePort: Int,
                              val destinationPort: Int, val protocol: Int, val flags: Int, val sequence: Long, val ack: Long)
    private val flows = HashMap<Key, Flow>()
    init { require(capacity > 0) }

    fun outgoing(bytes: ByteArray, now: Long): Boolean {
        val p = parse(bytes) ?: return false
        if (p.source !in locals || p.destination !in targets) return false
        val key = Key(p.source, p.destination, p.sourcePort, p.destinationPort, p.protocol)
        val flow = live(key, now)
        if (flow == null) {
            // TCP pinholes can only be opened by an initial SYN (no ACK/RST/FIN).
            if (p.protocol == 6 && p.flags and 0x17 != 0x02) return false
            if (flows.size >= capacity) flows.entries.removeAll { it.value.expires <= now }
            if (flows.size >= capacity) return false
            flows[key] = Flow(now + if (p.protocol == 17) 60_000 else 120_000, p.sequence)
        } else {
            if (p.protocol == 6 && !flow.established && p.flags and 2 != 0 && p.sequence != flow.synSequence) return false
            if (p.protocol == 6 && p.flags and 4 != 0) { flows.remove(key); return true }
            if (p.protocol == 6 && p.flags and 1 != 0) flow.closing = true
            flow.expires = now + lifetime(p, flow)
        }
        return true
    }

    fun incoming(bytes: ByteArray, now: Long): Boolean {
        val p = parse(bytes) ?: return false
        if (p.destination !in locals || p.source !in targets) return false
        val key = Key(p.destination, p.source, p.destinationPort, p.sourcePort, p.protocol)
        val flow = live(key, now) ?: return false
        if (p.protocol == 6) {
            if (!flow.established) {
                // SYN-ACK or connection-refused RST-ACK must acknowledge our SYN.
                if (p.ack != ((flow.synSequence + 1) and 0xffffffffL) ||
                    p.flags and 0x17 !in setOf(0x12, 0x14)) return false
                flow.established = true
            } else {
                if (p.flags and 0x10 == 0) return false
                if (p.flags and 2 != 0 && (p.flags and 0x17 != 0x12 || p.ack != ((flow.synSequence + 1) and 0xffffffffL))) return false
            }
            if (p.flags and 4 != 0) { flows.remove(key); return true }
            if (p.flags and 1 != 0) flow.closing = true
        }
        flow.expires = now + lifetime(p, flow)
        return true
    }

    private fun lifetime(p: Packet, flow: Flow): Long = when {
        p.protocol == 17 -> 60_000
        flow.closing -> 30_000
        flow.established -> 300_000
        else -> 120_000
    }
    private fun live(key: Key, now: Long): Flow? = flows[key]?.let {
        if (now >= it.expires) { flows.remove(key); null } else it
    }

    private fun parse(b: ByteArray): Packet? {
        if (b.isEmpty()) return null
        val offset: Int
        val protocol: Int
        val source: List<Byte>
        val destination: List<Byte>
        when (b[0].toInt() ushr 4 and 15) {
            4 -> {
                if (b.size < 20) return null
                offset = (b[0].toInt() and 15) * 4
                if (offset < 20 || offset > b.size || DnsPackets.u16(b, 2) != b.size ||
                    DnsPackets.u16(b, 6) and 0xbfff != 0 || DnsPackets.checksum(b.copyOf(offset)) != 0) return null
                protocol = b[9].toInt() and 255
                source = b.slice(12..15); destination = b.slice(16..19)
            }
            6 -> {
                if (b.size < 40 || DnsPackets.u16(b, 4) + 40 != b.size) return null
                offset = 40; protocol = b[6].toInt() and 255
                source = b.slice(8..23); destination = b.slice(24..39)
                // No extension headers, fragments or jumbograms are accepted.
            }
            else -> return null
        }
        var flags = 0; var sequence = 0L; var ack = 0L
        when (protocol) {
            17 -> if (b.size < offset + 8 || DnsPackets.u16(b, offset + 4) != b.size - offset) return null
            6 -> {
                if (b.size < offset + 20) return null
                val header = (b[offset + 12].toInt() ushr 4 and 15) * 4
                if (header < 20 || offset + header > b.size) return null
                flags = b[offset + 13].toInt() and 255
                sequence = u32(b, offset + 4); ack = u32(b, offset + 8)
            }
            else -> return null
        }
        val sport = DnsPackets.u16(b, offset); val dport = DnsPackets.u16(b, offset + 2)
        if (sport == 0 || dport == 0) return null
        return Packet(source, destination, sport, dport, protocol, flags, sequence, ack)
    }
    private fun u32(b: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (b[offset + i].toLong() and 255) }
}
