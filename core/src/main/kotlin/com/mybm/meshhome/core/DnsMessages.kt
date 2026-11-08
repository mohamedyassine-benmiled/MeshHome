package com.mybm.meshhome.core

import org.xbill.DNS.*
import java.net.InetAddress

/** Pure DNS wire codec; never performs DNS lookups. Malformed queries never go upstream. */
class DnsMessages(rules: List<DnsRule>, private val devices: List<MeshDevice>) {
    private val rules = DnsRules(rules)

    fun answer(query: ByteArray, forward: (ByteArray) -> ByteArray): ByteArray {
        require(query.size in 12..65535) { "Invalid DNS message size" }
        val request = Message(query)
        require(!request.header.getFlag(Flags.QR.toInt()) && request.header.opcode == Opcode.QUERY)
        require(request.header.getCount(Section.QUESTION) == 1)
        val question = request.question
        require(question.getDClass() == DClass.IN)
        val type = when (question.type) { Type.A -> QueryType.A; Type.AAAA -> QueryType.AAAA; else -> QueryType.OTHER }
        val decision = rules.resolve(question.name.toString(), type, devices)
        if (decision == DnsDecision.Forward) {
            val wire = forward(query)
            val response = Message(wire)
            require(response.header.id == request.header.id && response.header.getFlag(Flags.QR.toInt()))
            require(response.question == question) { "Mismatched DNS response" }
            return wire
        }
        val response = Message(request.header.id)
        response.header.setFlag(Flags.QR.toInt())
        response.header.setFlag(Flags.RA.toInt())
        if (request.header.getFlag(Flags.RD.toInt())) response.header.setFlag(Flags.RD.toInt())
        response.addRecord(question, Section.QUESTION)
        if (decision == DnsDecision.TargetUnavailable) response.header.rcode = Rcode.SERVFAIL
        if (decision is DnsDecision.Answer) {
            decision.addresses.forEach { address ->
                val raw = IpLiteral.parse(address) ?: error("Invalid peer address")
                val inet = InetAddress.getByAddress(raw)
                val record = when (question.type) {
                    Type.A -> ARecord(question.name, DClass.IN, decision.ttlSeconds.toLong(), inet)
                    Type.AAAA -> AAAARecord(question.name, DClass.IN, decision.ttlSeconds.toLong(), inet)
                    else -> error("Unexpected address record")
                }
                response.addRecord(record, Section.ANSWER)
            }
        }
        return response.toWire()
    }

    companion object {
        fun udpLimit(query: ByteArray): Int = runCatching { (Message(query).getOPT()?.payloadSize ?: 512).coerceIn(512, 1200) }.getOrDefault(512)

        fun truncateForUdp(response: ByteArray, maximum: Int = 1200): ByteArray {
            if (response.size <= maximum) return response
            val message = Message(response)
            message.removeAllRecords(Section.ANSWER)
            message.removeAllRecords(Section.AUTHORITY)
            message.removeAllRecords(Section.ADDITIONAL)
            message.header.setFlag(Flags.TC.toInt())
            return message.toWire()
        }

        fun failure(query: ByteArray): ByteArray? = runCatching {
            val request = Message(query)
            val response = Message(request.header.id)
            response.header.setFlag(Flags.QR.toInt())
            response.header.setFlag(Flags.RA.toInt())
            response.header.rcode = Rcode.SERVFAIL
            request.question?.let { response.addRecord(it, Section.QUESTION) }
            response.toWire()
        }.getOrNull()
    }
}

/** Literal-only address parser. Never feeds an arbitrary hostname to getByName. */
object IpLiteral {
    /** Network-provided IPv6 DNS may carry a link/interface scope (e.g. fe80::1%12). */
    fun resolver(value: String): InetAddress? = runCatching {
        val pieces = value.split('%', limit = 2)
        val bytes = parse(pieces[0]) ?: return@runCatching null
        if (pieces.size == 1) InetAddress.getByAddress(bytes) else {
            require(bytes.size == 16 && pieces[1].matches(Regex("[A-Za-z0-9_.-]{1,32}")))
            val scope = pieces[1].toIntOrNull()
            if (scope != null) java.net.Inet6Address.getByAddress(null, bytes, scope)
            else java.net.Inet6Address.getByAddress(null, bytes, checkNotNull(java.net.NetworkInterface.getByName(pieces[1])))
        }
    }.getOrNull()

    fun parse(value: String): ByteArray? = runCatching {
        if (':' in value) {
            require(value.all { it in "0123456789abcdefABCDEF:." } && '%' !in value)
            java.net.InetAddress.getByName(value).address.also { require(it.size == 16) }
        } else {
            val parts = value.split('.')
            require(parts.size == 4)
            parts.map {
                require(it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit))
                it.toInt().also { n -> require(n in 0..255) }.toByte()
            }.toByteArray()
        }
    }.getOrNull()
}
