package com.mybm.meshhome

import android.net.Network
import java.util.Base64
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class NordApiException(val status: Int) : Exception(when (status) {
    401, 403 -> "Nord rejected the token or this operation. Check token expiry and Meshnet account access."
    404 -> "Nord could not find this Meshnet device. Remove it locally and register again if it was deleted."
    409 -> "Nord reports a registration conflict. Check the existing devices in Nord Account."
    429 -> "Nord rate limit reached. Wait before retrying."
    else -> "Nord API request failed (HTTP $status)."
})

/** Based on pinned upstream clis/tcli/src/nord.rs, not the outdated README bearer-token example. */
class NordApi internal constructor(private val token: String, private val open: (URL) -> HttpsURLConnection) {
    constructor(token: String, network: Network) : this(token, { network.openConnection(it) as HttpsURLConnection })
    fun verify() { request("GET", "/users/services/credentials") }
    fun register(publicKey: String, hardwareId: String): String = JSONObject(request("POST", "/meshnet/machines",
        JSONObject().put("public_key", publicKey).put("hardware_identifier", hardwareId)
            .put("os", "android").put("os_version", "Android ${android.os.Build.VERSION.RELEASE}; MeshHome 0.3")))
        .getString("identifier").also { validateId(it) }
    fun map(id: String): String { validateId(id); return request("GET", "/meshnet/machines/$id/map") }
    fun delete(id: String) { validateId(id); request("DELETE", "/meshnet/machines/$id") }

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' })
        val connection = open(URL("https://api.nordvpn.com/v1$path"))
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false // Never send credentials to a redirect target.
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("token:$token".toByteArray(Charsets.UTF_8)))
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "MeshHome/0.3 (Android; independent Meshnet client)")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw NordApiException(status)
            if (status == 204) return ""
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 16 * 1024 * 1024) { "Nord response exceeded size limit." }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
    private fun validateId(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid device identifier." } }
}
