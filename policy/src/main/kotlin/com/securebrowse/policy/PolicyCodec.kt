package com.securebrowse.policy

object PolicyCodec {
    const val VERSION = 1
    const val MAX_BYTES = 256 * 1024
    const val MAX_ENTRIES = 500

    private val KEYS = setOf(
        "version",
        "homepage",
        "javascriptEnabled",
        "blockThirdPartyResources",
        "allowDownloads",
        "allowThirdPartyCookies",
        "kioskAutoStart",
        "strictKioskRestrictions",
        "sessionMinutes",
        "sessionPackages",
        "blockStatusBar",
        "blockNotifications",
        "entries",
    )

    fun encode(policy: BrowserPolicy): String {
        val root = linkedMapOf<String, Any?>(
            "version" to VERSION,
            "homepage" to policy.homepage,
            "javascriptEnabled" to policy.javascriptEnabled,
            "blockThirdPartyResources" to policy.blockThirdPartyResources,
            "allowDownloads" to policy.allowDownloads,
            "allowThirdPartyCookies" to policy.allowThirdPartyCookies,
            "kioskAutoStart" to policy.kioskAutoStart,
            "strictKioskRestrictions" to policy.strictKioskRestrictions,
            "sessionMinutes" to policy.sessionMinutes,
            "sessionPackages" to policy.sessionPackages,
            "blockStatusBar" to policy.blockStatusBar,
            "blockNotifications" to policy.blockNotifications,
            "entries" to policy.entries.map { entry ->
                linkedMapOf<String, Any?>(
                    "host" to entry.host,
                    "allowSubdomains" to entry.allowSubdomains,
                )
            },
        )
        return JsonValues.stringify(root)
    }

    fun decode(json: String): BrowserPolicy {
        if (json.length > MAX_BYTES) {
            throw PolicyFormatException("Allowlist file is too large.")
        }
        val root = JsonValues.parse(json) as? Map<*, *>
            ?: throw PolicyFormatException("Allowlist file must be an object.")
        val keys = root.keys.map { it as? String ?: throw PolicyFormatException("Invalid field name.") }
        val unknown = keys.filter { it !in KEYS }
        if (unknown.isNotEmpty()) {
            throw PolicyFormatException("Unknown field “${unknown.first()}”.")
        }
        val version = root["version"] as? Int ?: throw PolicyFormatException("Missing allowlist version.")
        if (version != VERSION) throw PolicyFormatException("Unsupported allowlist version $version.")

        val rawEntries = root["entries"] as? List<*> ?: throw PolicyFormatException("Missing domain list.")
        if (rawEntries.size > MAX_ENTRIES) throw PolicyFormatException("Too many domains.")
        val entries = rawEntries.map { item ->
            val obj = item as? Map<*, *> ?: throw PolicyFormatException("Each domain must be an object.")
            val hostRaw = obj["host"] as? String ?: throw PolicyFormatException("A domain is missing its host.")
            val subs = obj["allowSubdomains"] as? Boolean
                ?: throw PolicyFormatException("A domain is missing subdomain permission.")
            val host = HostNames.normalize(hostRaw)
                ?: throw PolicyFormatException("“$hostRaw” is not a valid hostname.")
            PublicSuffixGuard.rejectionReason(host)?.let { throw PolicyFormatException(it) }
            if (obj.keys.any { it != "host" && it != "allowSubdomains" }) {
                throw PolicyFormatException("A domain entry has an unknown field.")
            }
            DomainEntry(host, subs)
        }
        if (entries.map { it.host }.distinct().size != entries.size) {
            throw PolicyFormatException("Duplicate domains.")
        }

        val policy = BrowserPolicy(
            entries = entries,
            homepage = null,
            javascriptEnabled = bool(root, "javascriptEnabled", true),
            blockThirdPartyResources = bool(root, "blockThirdPartyResources", true),
            allowDownloads = bool(root, "allowDownloads", false),
            allowThirdPartyCookies = bool(root, "allowThirdPartyCookies", false),
            kioskAutoStart = bool(root, "kioskAutoStart", true),
            strictKioskRestrictions = bool(root, "strictKioskRestrictions", false),
            sessionMinutes = sessionMinutes(root),
            sessionPackages = sessionPackages(root),
            blockStatusBar = bool(root, "blockStatusBar", true),
            blockNotifications = bool(root, "blockNotifications", true),
        )
        val homepage = when (val value = root["homepage"]) {
            null -> null
            is String -> value.trim().ifEmpty { null }
            else -> throw PolicyFormatException("Homepage must be text or null.")
        }
        if (homepage == null) return policy
        val decision = UrlPolicy.evaluate(homepage, policy, UrlPolicy.Purpose.MAIN_FRAME)
        if (!decision.allowed) {
            throw PolicyFormatException("Homepage is not an approved https site.")
        }
        return policy.copy(homepage = homepage)
    }

    private fun bool(root: Map<*, *>, key: String, default: Boolean): Boolean {
        if (!root.containsKey(key)) return default
        return root[key] as? Boolean ?: throw PolicyFormatException("“$key” must be true or false.")
    }

    private fun sessionMinutes(root: Map<*, *>): Int {
        if (!root.containsKey("sessionMinutes")) return SessionRules.DEFAULT_MINUTES
        val value = root["sessionMinutes"] as? Int
            ?: throw PolicyFormatException("“sessionMinutes” must be a whole number.")
        SessionRules.minutesRejection(value)?.let { throw PolicyFormatException(it) }
        return value
    }

    private fun sessionPackages(root: Map<*, *>): List<String> {
        if (!root.containsKey("sessionPackages")) return emptyList()
        val raw = root["sessionPackages"] as? List<*>
            ?: throw PolicyFormatException("Session apps must be a list.")
        if (raw.size > SessionRules.MAX_PACKAGES) throw PolicyFormatException("Too many session apps.")
        return raw.map { item ->
            val text = item as? String ?: throw PolicyFormatException("A session app id must be text.")
            SessionRules.normalizePackage(text)
                ?: throw PolicyFormatException("“$text” is not an Android app id.")
        }.distinct()
    }
}
