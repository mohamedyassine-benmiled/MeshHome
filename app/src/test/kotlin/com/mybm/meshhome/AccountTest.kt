package com.mybm.meshhome

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class AccountTest {
    @Test fun nativeMapMaintainsOnlyConfiguredTargetsButPreservesLocalAndRelayData() {
        val original = """{"identifier":"phone","hostname":"phone.nord","ip_addresses":["100.64.1.1"],"derp_servers":[{"name":"relay"}],"peers":[
            {"identifier":"a","hostname":"a.nord","ip_addresses":["100.64.1.2"]},
            {"identifier":"b","hostname":"b.nord","ip_addresses":["100.64.1.3"]}]}"""
        val map = MeshMap.parse(original)
        val filtered = JSONObject(map.forTargets(setOf("b")))
        assertEquals(1, filtered.getJSONArray("peers").length())
        assertEquals("b", filtered.getJSONArray("peers").getJSONObject(0).getString("identifier"))
        assertEquals("phone", filtered.getString("identifier"))
        assertEquals("relay", filtered.getJSONArray("derp_servers").getJSONObject(0).getString("name"))
        assertEquals(2, map.peers.size) // Device picker still sees all account peers.
        assertEquals(0, JSONObject(map.forTargets(emptySet())).getJSONArray("peers").length())
    }
    private class Fake(url: URL, private val code: Int, private val payload: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(payload.toByteArray())
        override fun getOutputStream() = sent
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun basicAuthenticationAndFixedHttpsEndpoint() {
        lateinit var connection: Fake
        val api = NordApi("test-not-real") { url -> Fake(url, 200, "{}").also { connection = it } }
        api.verify()
        assertEquals("https://api.nordvpn.com/v1/users/services/credentials", connection.url.toString())
        assertEquals("token:test-not-real", String(Base64.getDecoder().decode(connection.getRequestProperty("Authorization").removePrefix("Basic "))))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun errorsDoNotExposeBodiesOrTokens() {
        val api = NordApi("test-not-real") { url -> Fake(url, 401, "sensitive server response") }
        val error = assertThrows(NordApiException::class.java) { api.verify() }
        assertEquals(401, error.status)
        assertFalse(error.message!!.contains("test-not-real"))
        assertFalse(error.message!!.contains("sensitive"))
    }

    @Test fun redirectsAreNotFollowed() {
        val api = NordApi("test-not-real") { url -> Fake(url, 302, "") }
        assertEquals(302, assertThrows(NordApiException::class.java) { api.verify() }.status)
    }

    @Test fun registrationSendsOnlyLocalPublicIdentity() {
        lateinit var connection: Fake
        val api = NordApi("test-not-real") { url -> Fake(url, 200, "{\"identifier\":\"device-1\"}").also { connection = it } }
        assertEquals("device-1", api.register("public-key", "hardware-id"))
        val payload = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals("public-key", payload.getString("public_key"))
        assertEquals("hardware-id", payload.getString("hardware_identifier"))
        assertFalse(payload.has("private_key"))
        assertEquals("POST", connection.requestMethod)
    }

    @Test fun mapAndDeregistrationRejectPathInjection() {
        val api = NordApi("test") { error("Must not open a connection") }
        assertThrows(IllegalArgumentException::class.java) { api.map("../evil") }
        assertThrows(IllegalArgumentException::class.java) { api.delete("id?token=x") }
    }

    @Test fun meshMapUsesStableIdsAndSeparatesAddressFamilies() {
        val map = MeshMap.parse("""{"identifier":"phone","hostname":"phone.nord","ip_addresses":["100.64.1.1"],"peers":[
            {"identifier":"server","hostname":"server.nord","nickname":"Home","ip_addresses":["100.64.1.2","fd00::2"]}]}""")
        assertEquals("phone", map.local.id)
        assertEquals("server", map.peers.single().id)
        assertEquals("Home", map.peers.single().name)
        assertEquals(listOf("fd00::2"), map.peers.single().ipv6)
    }

    @Test fun meshMapRejectsHostnamesAsPeerAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshMap.parse("""{"identifier":"phone","hostname":"phone.nord","ip_addresses":["evil.example.org"]}""")
        }
    }

    @Test fun localX25519IdentityRoundTrips() {
        val key = X25519PrivateKeyParameters(SecureRandom())
        assertEquals(32, key.encoded.size)
        val restored = X25519PrivateKeyParameters(key.encoded, 0)
        assertArrayEquals(key.generatePublicKey().encoded, restored.generatePublicKey().encoded)
    }
}
