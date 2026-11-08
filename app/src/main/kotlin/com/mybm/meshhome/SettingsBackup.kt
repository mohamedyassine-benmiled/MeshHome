package com.mybm.meshhome

import com.mybm.meshhome.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlist: credentials and device private keys never enter exports. */
internal data class SettingsBackup(
    val mode: NetworkMode,
    val apps: Set<String>,
    val homeSsids: Set<String>,
    val upstreamDns: String,
    val rules: List<DnsRule>,
    val nordToken: String? = null
) {
    fun exportText(): String = JSONObject().put("format", "meshhome-settings").put("version", 1)
        .put("mode", mode.name).put("apps", JSONArray(apps.sorted()))
        .put("homeSsids", JSONArray(homeSsids.sorted())).put("upstreamDns", upstreamDns)
        .put("rules", JSONArray().apply {
            rules.forEach { put(JSONObject().put("pattern", it.pattern.value).put("deviceId", it.deviceId).put("enabled", it.enabled)) }
        }).toString(2)

    companion object {
        fun parse(text: String): SettingsBackup {
            val json = JSONObject(text)
            require(json.getString("format") == "meshhome-settings" && json.getInt("version") == 1) { "Unsupported settings file." }
            fun strings(key: String): Set<String> {
                val array = json.getJSONArray(key)
                return (0 until array.length()).map {
                    require(array.get(it) is String) { "Invalid $key entry." }
                    array.getString(it).also { value -> require(value.isNotBlank()) { "Empty $key entry." } }
                }.toSet()
            }
            val dns = json.getString("upstreamDns").trim()
            require(dns.isBlank() || (IpLiteral.parse(dns) != null && dns != DnsPackets.ADDRESS)) { "Invalid upstream DNS address." }
            val array = json.getJSONArray("rules")
            val rules = (0 until array.length()).map {
                val rule = array.getJSONObject(it)
                require(rule.getString("deviceId").isNotBlank()) { "Missing target device." }
                DnsRule(DomainPattern.parse(rule.getString("pattern")), rule.getString("deviceId"), rule.getBoolean("enabled"))
            }
            DnsRules(rules)
            val token = if (json.has("nordToken")) {
                require(json.get("nordToken") is String) { "nordToken must be text." }
                json.getString("nordToken").trim().takeIf { it.isNotEmpty() }
            } else null
            return SettingsBackup(NetworkMode.valueOf(json.getString("mode")), strings("apps"), strings("homeSsids"), dns, rules, token)
        }
    }
}
