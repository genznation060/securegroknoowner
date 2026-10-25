package com.securebrowse.policy

import java.net.IDN

/**
 * Hostname normalization and label-boundary matching.
 *
 * `notgoogle.com` does not match `google.com`. `evil.com` does not match a
 * rule for `google.com` just because the characters appear somewhere in the
 * name. Subdomains match only when the administrator enabled them, and only
 * on a dot boundary (`www.google.com`, never `fakegoogle.com`).
 */
object HostNames {
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val IPV4 = Regex(
        "^(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)" +
            "(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
    )

    fun isIpv4(host: String): Boolean = IPV4.matches(host)

    /**
     * Returns a lowercase ASCII hostname, or null when the input is not a
     * single host (no scheme, path, port, or userinfo).
     */
    fun normalize(raw: String): String? {
        var host = raw.trim().lowercase()
        if (host.isEmpty() || host.length > 253) return null
        if (host.any { it.isISOControl() || it == ' ' || it == '\\' || it == '/' || it == '%' || it == '@' || it == ':' || it == '?' || it == '#' || it == '*' }) {
            return null
        }
        if (host.endsWith(".")) {
            host = host.dropLast(1)
        }
        if (host.isEmpty() || host.endsWith(".") || host.startsWith(".") || host.contains("..")) {
            return null
        }
        val ascii = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val normalized = ascii.lowercase()
        if (normalized.isEmpty() || normalized.length > 253 || normalized.contains("..")) return null
        if (isIpv4(normalized)) return normalized
        val labels = normalized.split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        return normalized
    }

    fun matches(host: String, ruleHost: String, allowSubdomains: Boolean): Boolean {
        if (host == ruleHost) return true
        if (!allowSubdomains || isIpv4(ruleHost) || isIpv4(host)) return false
        return host.endsWith(".$ruleHost")
    }
}
