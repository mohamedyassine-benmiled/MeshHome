package com.mybm.meshhome.core

import org.junit.Assert.*
import org.junit.Test
import org.xbill.DNS.*
import org.xbill.DNS.Record

class DnsWireTest {
    private val devices = listOf(MeshDevice("one", "One", listOf("100.64.1.2"), listOf("fd00::2")), MeshDevice("two", "Two", listOf("100.64.1.3")))
    private val messages = DnsMessages(listOf(DnsRule(DomainPattern.parse("*.example.org"), "one"),
        DnsRule(DomainPattern.parse("backup.example.org"), "two")), devices)
    private fun query(name: String, type: Int = Type.A) = Message.newQuery(Record.newRecord(Name.fromString("$name."), type, DClass.IN)).toWire()

    @Test fun localAAndAaaaNeverCallUpstream() {
        val a = Message(messages.answer(query("photos.example.org")) { error("Must not forward") })
        assertEquals("100.64.1.2", (a.getSection(Section.ANSWER).single() as ARecord).address.hostAddress)
        val aaaa = Message(messages.answer(query("photos.example.org", Type.AAAA)) { error("Must not forward") })
        assertEquals(16, (aaaa.getSection(Section.ANSWER).single() as AAAARecord).address.address.size)
        assertFalse(a.header.getFlag(Flags.AD.toInt())) // Synthesized records must not claim DNSSEC validation.
    }

    @Test fun serviceSubdomainNamesAlsoMatchWildcard() {
        val response = Message(messages.answer(query("_service._tcp.example.org", Type.SRV)) { error("Must not leak to upstream") })
        assertEquals(Rcode.NOERROR, response.rcode)
        assertTrue(response.getSection(Section.ANSWER).isEmpty())
    }

    @Test fun udpLimitRespectsEdnsAndNeverExceedsTunnelBudget() {
        val ordinary = query("example.net")
        assertEquals(512, DnsMessages.udpLimit(ordinary))
        val edns = Message(ordinary).apply { addRecord(OPTRecord(4096, 0, 0), Section.ADDITIONAL) }
        assertEquals(1200, DnsMessages.udpLimit(edns.toWire()))
    }

    @Test fun ipv4OnlyTargetDoesNotLeakAaaaOrHttpsRecords() {
        for (type in listOf(Type.AAAA, Type.HTTPS, Type.CNAME, Type.TXT)) {
            val response = Message(messages.answer(query("backup.example.org", type)) { error("Must not forward") })
            assertEquals(Rcode.NOERROR, response.rcode)
            assertTrue(response.getSection(Section.ANSWER).isEmpty())
        }
    }

    @Test fun deletedDeviceReturnsServfail() {
        val dns = DnsMessages(listOf(DnsRule(DomainPattern.parse("example.org"), "deleted")), devices)
        assertEquals(Rcode.SERVFAIL, Message(dns.answer(query("example.org")) { error("Must not forward") }).rcode)
    }

    @Test fun upstreamReplyMustMatchTransactionAndQuestion() {
        val input = query("example.net")
        val valid = Message(input).apply { header.setFlag(Flags.QR.toInt()) }.toWire()
        assertArrayEquals(valid, messages.answer(input) { valid })
        val wrongId = Message(valid).apply { header.id = (header.id + 1) and 65535 }.toWire()
        assertThrows(IllegalArgumentException::class.java) { messages.answer(input) { wrongId } }
        val wrongQuestion = Message.newQuery(Record.newRecord(Name.fromString("evil.net."), Type.A, DClass.IN)).apply {
            header.id = Message(input).header.id; header.setFlag(Flags.QR.toInt())
        }.toWire()
        assertThrows(IllegalArgumentException::class.java) { messages.answer(input) { wrongQuestion } }
    }

    @Test fun malformedRequestsAreRejectedWithoutForwarding() {
        val invalid = listOf(byteArrayOf(), ByteArray(12), ByteArray(20) { 0xff.toByte() },
            Message(query("photos.example.org")).apply { header.setFlag(Flags.QR.toInt()) }.toWire())
        invalid.forEach { packet -> assertTrue(runCatching { messages.answer(packet) { error("No forwarding") } }.isFailure) }
    }

    @Test fun oversizedUdpResponseSetsTruncationAndKeepsQuestion() {
        val response = Message(query("example.net")).apply {
            header.setFlag(Flags.QR.toInt())
            repeat(80) { addRecord(TXTRecord(Name.fromString("example.net."), DClass.IN, 30, "x".repeat(100)), Section.ANSWER) }
        }
        val limited = Message(DnsMessages.truncateForUdp(response.toWire(), 512))
        assertTrue(limited.header.getFlag(Flags.TC.toInt()))
        assertEquals(response.question, limited.question)
        assertTrue(limited.toWire().size < 512)
    }

    @Test fun literalParserRejectsNamesAndInvalidAddresses() {
        listOf("example.org", "1.2.3", "1.2.3.256", "1..2.3", "fd00::1%wlan0", "[::1]", "bad:name").forEach { assertNull(it, IpLiteral.parse(it)) }
        assertEquals(4, IpLiteral.parse("100.64.1.2")!!.size)
        assertEquals(16, IpLiteral.parse("fd00::1")!!.size)
        assertEquals(12, (IpLiteral.resolver("fe80::1%12") as java.net.Inet6Address).scopeId)
        assertNull(IpLiteral.resolver("evil.example.org%12"))
        assertNull(IpLiteral.resolver("fe80::1%bad/interface"))
    }
}
