package com.mybm.meshhome

import android.content.Context
import android.net.Network
import android.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import com.mybm.meshhome.core.IpLiteral
import com.mybm.meshhome.core.MeshDevice
import java.security.SecureRandom
import java.util.UUID

data class MeshMap(val json: String, val local: MeshDevice, val peers: List<MeshDevice>) {
    /** Avoid WireGuard/relay keepalives for account devices that have no enabled DNS rule. */
    fun forTargets(ids: Set<String>): String {
        val root = JSONObject(json)
        val source = root.optJSONArray("peers")
        val selected = org.json.JSONArray()
        for (index in 0 until (source?.length() ?: 0)) {
            val peer = source!!.getJSONObject(index)
            if (peer.getString("identifier") in ids) selected.put(peer)
        }
        root.put("peers", selected)
        return root.toString()
    }

    companion object {
        fun parse(json: String): MeshMap {
            val root = JSONObject(json)
            fun device(item: JSONObject): MeshDevice {
                val addresses = item.optJSONArray("ip_addresses")
                val ips = (0 until (addresses?.length() ?: 0)).map { addresses!!.getString(it) }
                require(ips.all { IpLiteral.parse(it) != null }) { "Nord returned an invalid IP address." }
                return MeshDevice(item.getString("identifier"), item.optString("nickname").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.getString("hostname"), ips.filter { ':' !in it }, ips.filter { ':' in it })
            }
            val peers = root.optJSONArray("peers")
            return MeshMap(json, device(root), (0 until (peers?.length() ?: 0)).map { device(peers!!.getJSONObject(it)) })
        }
    }
}

/** One lock shared by UI and VPN service: never race registration, key replacement or logout. */
class AccountRepository(context: Context) {
    private val vault = TokenVault(context.applicationContext)
    val hasToken get() = vault.hasToken
    // Never wait for an in-flight HTTP operation on the UI thread. The vault has
    // its own short cross-instance lock for atomic ciphertext/IV reads.
    fun cachedMap(): MeshMap? = vault.readSecret("map")?.let { MeshMap.parse(it) }
    fun privateKey(): String = synchronized(lock) { identity().getString("private_key") }
    fun registeredId(): String? = synchronized(lock) { vault.readSecret("identity")?.let { JSONObject(it).optString("id").ifBlank { null } } }

    fun login(token: String, network: Network): MeshMap = synchronized(lock) {
        val api = NordApi(token.trim(), network)
        api.verify()
        if (vault.hasToken && vault.read() != token.trim()) {
            // Permit token renewal only after proving the new token can access
            // this very same registration/key. Never silently migrate an identity
            // to a different account or overwrite the old token on a failed check.
            val id = checkNotNull(registeredId()) { "Log out before replacing a token with an incomplete registration." }
            val existing = MeshMap.parse(api.map(id))
            validateLocalIdentity(existing, identity(), id)
        }
        // Save the validated token before registration. Retrying can reuse persisted identity.
        vault.save(token)
        refreshLocked(api)
    }

    fun refresh(network: Network): MeshMap = synchronized(lock) {
        val token = checkNotNull(vault.read()) { "Sign in with a Nord Account token first." }
        refreshLocked(NordApi(token, network))
    }

    private fun refreshLocked(api: NordApi): MeshMap {
        val identity = identity()
        var id = identity.optString("id")
        if (id.isBlank()) {
            val key = X25519PrivateKeyParameters(Base64.decode(identity.getString("private_key"), Base64.NO_WRAP), 0)
            id = api.register(Base64.encodeToString(key.generatePublicKey().encoded, Base64.NO_WRAP), identity.getString("hardware_id"))
            identity.put("id", id)
            vault.saveSecret("identity", identity.toString()) // Persist registration before map retrieval.
        }
        val map = MeshMap.parse(api.map(id))
        validateLocalIdentity(map, identity, id)
        if (vault.readSecret("map") != map.json) vault.saveSecret("map", map.json)
        return map
    }

    private fun validateLocalIdentity(map: MeshMap, identity: JSONObject, id: String) {
        check(map.local.id == id) { "Nord returned a different device identity." }
        val key = X25519PrivateKeyParameters(Base64.decode(identity.getString("private_key"), Base64.NO_WRAP), 0)
        check(JSONObject(map.json).getString("public_key") == Base64.encodeToString(key.generatePublicKey().encoded, Base64.NO_WRAP)) {
            "Nord returned a different public key for this device. Do not reuse another device's registration."
        }
    }

    private fun identity(): JSONObject {
        vault.readSecret("identity")?.let { return JSONObject(it) }
        val key = X25519PrivateKeyParameters(SecureRandom())
        return JSONObject().put("private_key", Base64.encodeToString(key.encoded, Base64.NO_WRAP))
            .put("hardware_id", "meshhome-${UUID.randomUUID()}").also { vault.saveSecret("identity", it.toString()) }
    }

    fun logout(deregister: Boolean, network: Network?) = synchronized(lock) {
        if (deregister) {
            val id = registeredId()
            if (id != null) {
                val api = NordApi(checkNotNull(vault.read()), checkNotNull(network) { "A permitted network is required to deregister." })
                try { api.delete(id) } catch (e: NordApiException) { if (e.status != 404) throw e }
            }
        }
        vault.clear()
    }
    companion object { private val lock = Any() }
}
