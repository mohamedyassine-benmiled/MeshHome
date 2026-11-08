package com.mybm.meshhome

import android.net.Network
import android.net.VpnService
import com.mybm.meshhome.core.DnsMessages
import com.mybm.meshhome.core.DnsPackets
import com.mybm.meshhome.core.IpLiteral
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*

/** Uses the selected physical network's DNS, never a remote home resolver by default. */
class DnsUpstream(private val vpn: VpnService, private val network: Network, private val servers: List<String>) {
    fun query(message: ByteArray): ByteArray {
        val ip = servers.mapNotNull(IpLiteral::resolver).firstOrNull { !it.address.contentEquals(IpLiteral.parse(DnsPackets.ADDRESS)) }
            ?: error("This network has no usable DNS resolver. Configure an upstream DNS IP in settings.")
        DatagramSocket(null).use { socket ->
            check(vpn.protect(socket)) { "DNS socket protection failed." }
            network.bindSocket(socket)
            socket.soTimeout = 4000
            socket.connect(ip, 53)
            socket.send(DatagramPacket(message, message.size))
            val response = DatagramPacket(ByteArray(65535), 65535)
            socket.receive(response)
            val wire = response.data.copyOf(response.length)
            if (wire.size >= 12 && wire[2].toInt() and 2 != 0) return tcp(ip, message)
            return wire
        }
    }

    private fun tcp(ip: InetAddress, query: ByteArray): ByteArray = Socket().use { socket ->
        check(vpn.protect(socket))
        network.bindSocket(socket)
        socket.connect(InetSocketAddress(ip, 53), 4000); socket.soTimeout = 4000
        DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
        DataInputStream(socket.getInputStream()).let { input ->
            val size = input.readUnsignedShort()
            require(size >= 12)
            ByteArray(size).also { input.readFully(it) }
        }
    }
}
