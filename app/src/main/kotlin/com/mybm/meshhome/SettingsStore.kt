package com.mybm.meshhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.mybm.meshhome.core.*

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    internal fun backup() = SettingsBackup(mode, apps, homeSsids, upstreamDns, rules)

    /** Apply a fully validated import in one preferences transaction. */
    internal fun restore(backup: SettingsBackup) {
        val validated = SettingsBackup.parse(backup.exportText())
        val json = JSONObject(validated.exportText())
        prefs.edit().putString("mode", validated.mode.name)
            .putStringSet("apps", validated.apps).putStringSet("homeSsids", validated.homeSsids)
            .putString("upstreamDns", validated.upstreamDns).putString("rules", json.getJSONArray("rules").toString()).apply()
    }
    var upstreamDns: String
        get() = prefs.getString("upstreamDns", "")!!
        set(value) {
            require(value.isBlank() || (IpLiteral.parse(value) != null && value != DnsPackets.ADDRESS)) { "Enter a DNS server IP, not a hostname." }
            prefs.edit().putString("upstreamDns", value.trim()).apply()
        }
    var mode: NetworkMode
        get() = runCatching { NetworkMode.valueOf(prefs.getString("mode", null) ?: "WIFI_ONLY") }.getOrDefault(NetworkMode.WIFI_ONLY)
        set(value) { prefs.edit().putString("mode", value.name).apply() }
    var apps: Set<String>
        get() = prefs.getStringSet("apps", emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet("apps", value.toSet()).apply() }
    var homeSsids: Set<String>
        get() = prefs.getStringSet("homeSsids", emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet("homeSsids", value.toSet()).apply() }
    var rules: List<DnsRule>
        get() {
            val array = JSONArray(prefs.getString("rules", "[]"))
            return (0 until array.length()).map { index ->
                val rule = array.getJSONObject(index)
                DnsRule(DomainPattern.parse(rule.getString("pattern")), rule.getString("deviceId"), rule.getBoolean("enabled"))
            }
        }
        set(value) {
            DnsRules(value) // Validate before persisting.
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("pattern", it.pattern.value).put("deviceId", it.deviceId).put("enabled", it.enabled)) }
            prefs.edit().putString("rules", array.toString()).apply()
        }
}
