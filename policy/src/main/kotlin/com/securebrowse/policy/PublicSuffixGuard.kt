package com.securebrowse.policy

/**
 * Rejects allowlist rules that are public suffixes or shared hosting zones.
 *
 * This is a guardrail, not a full Public Suffix List. Label-boundary matching
 * already stops `notgoogle.com`. The list stops an administrator from
 * accidentally approving every `*.co.uk` or `*.github.io` site.
 */
object PublicSuffixGuard {
    private val EXACT = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "app", "dev", "io", "ai", "co",
        "uk", "us", "ca", "au", "de", "fr", "jp", "br", "in", "ru", "cn", "nl", "se",
        "no", "fi", "dk", "es", "it", "pl", "ch", "at", "be", "nz", "za", "mx", "kr",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "net.uk", "ltd.uk", "plc.uk", "me.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "asn.au", "id.au",
        "co.nz", "org.nz", "net.nz", "govt.nz", "ac.nz",
        "co.za", "org.za", "net.za", "gov.za", "ac.za",
        "com.br", "net.br", "org.br", "gov.br",
        "com.mx", "org.mx", "gob.mx",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "ed.jp",
        "co.in", "net.in", "org.in", "gen.in", "firm.in", "ind.in",
        "com.sg", "com.hk", "com.tw", "com.tr", "com.ar", "com.co", "com.pe",
        "com.cn", "com.ru", "co.kr", "ne.kr",
        "github.io", "githubusercontent.com", "herokuapp.com", "appspot.com",
        "azurewebsites.net", "cloudapp.net", "blob.core.windows.net",
        "firebaseapp.com", "web.app", "netlify.app", "vercel.app", "pages.dev",
        "workers.dev", "blogspot.com", "wordpress.com", "wixsite.com",
        "square.site", "notion.site", "github.dev", "glitch.me",
        "s3.amazonaws.com", "amazonaws.com", "cloudfront.net",
        "ngrok.io", "ngrok.app", "trycloudflare.com", "loca.lt",
    )

    fun rejectionReason(normalizedHost: String): String? {
        if (!normalizedHost.contains('.') && !HostNames.isIpv4(normalizedHost)) {
            return "Enter a full domain such as wikipedia.org. Single-label names are rejected."
        }
        if (normalizedHost in EXACT) {
            return "“$normalizedHost” is a public or shared suffix. Approve a specific organization domain, not the suffix itself."
        }
        return null
    }
}
