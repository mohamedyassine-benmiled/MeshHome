package com.mybm.meshhome.core

import java.net.IDN
import java.util.Locale

/** DNS patterns, never URLs. Wildcards include descendants but exclude the apex. */
@ConsistentCopyVisibility
data class DomainPattern private constructor(val domain: String, val wildcard: Boolean) {
    val value: String get() = if (wildcard) "*.$domain" else domain

    fun matches(query: String): Boolean {
        val name = runCatching { normalize(query, queryName = true) }.getOrNull() ?: return false
        return if (wildcard) name.endsWith(".$domain") else name == domain
    }

    companion object {
        fun parse(input: String): DomainPattern {
            val text = input.trim()
            val wildcard = text.startsWith("*.")
            return DomainPattern(normalize(if (wildcard) text.drop(2) else text), wildcard)
        }

        private fun normalize(input: String, queryName: Boolean = false): String {
            val name = input.removeSuffix(".")
            require(name.isNotEmpty() && '*' !in name) { "Enter an exact domain or a leading *. wildcard." }
            require('\\' !in name) { "Escaped DNS names are not hostname patterns." }
            val ascii = IDN.toASCII(name, if (queryName) 0 else IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(ascii.length <= 253 && ascii.split('.').all { it.isNotEmpty() && it.length <= 63 }) {
                "Invalid DNS name."
            }
            return ascii
        }
    }
}

data class DnsRule(val pattern: DomainPattern, val deviceId: String, val enabled: Boolean = true)

data class MeshDevice(
    val id: String,
    val name: String,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
)

enum class QueryType { A, AAAA, OTHER }

sealed interface DnsDecision {
    data object Forward : DnsDecision
    data object TargetUnavailable : DnsDecision
    /** Empty records means NOERROR/NODATA, not upstream fallback. */
    data class Answer(val addresses: List<String>, val ttlSeconds: Int = 30) : DnsDecision
}

class DnsRules(private val rules: List<DnsRule>) {
    init {
        require(rules.map { it.pattern.value }.distinct().size == rules.size) { "Duplicate domain rule." }
        require(rules.all { it.deviceId.isNotBlank() }) { "Select a target device for each rule." }
    }

    fun resolve(query: String, type: QueryType, devices: List<MeshDevice>): DnsDecision {
        val rule = rules.filter { it.enabled && it.pattern.matches(query) }
            .sortedWith(compareBy<DnsRule> { it.pattern.wildcard }.thenByDescending { it.pattern.domain.length })
            .firstOrNull() ?: return DnsDecision.Forward
        val target = devices.singleOrNull { it.id == rule.deviceId } ?: return DnsDecision.TargetUnavailable
        if (target.ipv4.isEmpty() && target.ipv6.isEmpty()) return DnsDecision.TargetUnavailable
        return DnsDecision.Answer(when (type) {
            QueryType.A -> target.ipv4
            QueryType.AAAA -> target.ipv6
            QueryType.OTHER -> emptyList()
        })
    }
}
