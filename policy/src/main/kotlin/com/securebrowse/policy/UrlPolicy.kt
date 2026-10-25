package com.securebrowse.policy

import java.net.URI
import java.net.URISyntaxException

/**
 * Decides whether a URL may be loaded.
 *
 * Checking the address the user typed is not enough. Callers must run every
 * main-frame load, redirect, popup, and subresource through [evaluate].
 * A user gesture never bypasses the allowlist. This policy never asks the OS
 * to open an external browser.
 */
object UrlPolicy {
    const val MAX_URL_LENGTH = 8_192

    enum class Purpose {
        MAIN_FRAME,
        SUBRESOURCE,
        DOWNLOAD,
        POPUP,
    }

    enum class BlockReason {
        ALLOWED,
        EMPTY,
        INVALID,
        UNSUPPORTED_SCHEME,
        CLEARTEXT_HTTP,
        CREDENTIALS_IN_URL,
        HOST_MISSING,
        NOT_ALLOWLISTED,
        SUBRESOURCE_BLOCKED,
        DOWNLOAD_DISABLED,
        TOO_LONG,
        SSL,
    }

    data class Decision(
        val allowed: Boolean,
        val reason: BlockReason,
        val host: String?,
        val purpose: Purpose,
        val isRedirect: Boolean,
        val hasUserGesture: Boolean,
    ) {
        /** Never hand a URL to another app. Not part of equality so it cannot be flipped. */
        val openExternally: Boolean
            get() = false
    }

    fun evaluate(
        raw: String?,
        policy: BrowserPolicy,
        purpose: Purpose,
        isRedirect: Boolean = false,
        hasUserGesture: Boolean = false,
    ): Decision {
        fun block(reason: BlockReason, host: String? = null) = Decision(
            allowed = false,
            reason = reason,
            host = host,
            purpose = purpose,
            isRedirect = isRedirect,
            hasUserGesture = hasUserGesture,
        )

        if (raw.isNullOrBlank()) return block(BlockReason.EMPTY)
        val trimmed = raw.trim()
        if (trimmed.length > MAX_URL_LENGTH) return block(BlockReason.TOO_LONG)
        if (trimmed.any { it == '\\' || it.isISOControl() }) return block(BlockReason.INVALID)

        val schemeHint = schemeOf(trimmed)
        if (schemeHint == "data") {
            return if (purpose == Purpose.SUBRESOURCE) {
                Decision(true, BlockReason.ALLOWED, null, purpose, isRedirect, hasUserGesture)
            } else {
                block(BlockReason.UNSUPPORTED_SCHEME)
            }
        }
        if (schemeHint != null && schemeHint != "https" && schemeHint != "http" && schemeHint != "blob" && schemeHint != "about") {
            return block(BlockReason.UNSUPPORTED_SCHEME)
        }

        if (trimmed.equals("about:blank", ignoreCase = true)) {
            return if (purpose == Purpose.MAIN_FRAME && !isRedirect) {
                Decision(true, BlockReason.ALLOWED, null, purpose, false, hasUserGesture)
            } else {
                block(BlockReason.UNSUPPORTED_SCHEME)
            }
        }

        if (trimmed.startsWith("blob:", ignoreCase = true)) {
            return evaluateBlob(trimmed, policy, purpose, isRedirect, hasUserGesture)
        }

        val uri = try {
            URI(toAsciiUrl(trimmed))
        } catch (_: URISyntaxException) {
            return block(BlockReason.INVALID)
        } catch (_: IllegalArgumentException) {
            return block(BlockReason.INVALID)
        }

        val scheme = uri.scheme?.lowercase() ?: return block(BlockReason.INVALID)
        if (uri.userInfo != null) {
            return block(BlockReason.CREDENTIALS_IN_URL, safeHost(uri))
        }

        when (scheme) {
            "http" -> return block(BlockReason.CLEARTEXT_HTTP, safeHost(uri))
            "https" -> Unit
            "data" -> {
                return if (purpose == Purpose.SUBRESOURCE) {
                    Decision(true, BlockReason.ALLOWED, null, purpose, isRedirect, hasUserGesture)
                } else {
                    block(BlockReason.UNSUPPORTED_SCHEME)
                }
            }
            else -> return block(BlockReason.UNSUPPORTED_SCHEME, safeHost(uri))
        }

        val host = safeHost(uri) ?: return block(BlockReason.HOST_MISSING)
        return decideHost(host, policy, purpose, isRedirect, hasUserGesture)
    }

    fun evaluateDownload(raw: String?, policy: BrowserPolicy): Decision {
        val decision = evaluate(raw, policy, Purpose.DOWNLOAD, isRedirect = false, hasUserGesture = true)
        if (!decision.allowed) return decision
        if (!policy.allowDownloads) {
            return decision.copy(allowed = false, reason = BlockReason.DOWNLOAD_DISABLED)
        }
        return decision
    }

    private fun evaluateBlob(
        raw: String,
        policy: BrowserPolicy,
        purpose: Purpose,
        isRedirect: Boolean,
        hasUserGesture: Boolean,
    ): Decision {
        fun block(reason: BlockReason, host: String? = null) = Decision(
            false, reason, host, purpose, isRedirect, hasUserGesture,
        )
        if (purpose != Purpose.SUBRESOURCE) return block(BlockReason.UNSUPPORTED_SCHEME)
        val rest = raw.substring(5)
        if (rest.isEmpty() || rest.equals("null", ignoreCase = true)) {
            return Decision(true, BlockReason.ALLOWED, null, purpose, isRedirect, hasUserGesture)
        }
        val origin = blobOrigin(rest) ?: return block(BlockReason.UNSUPPORTED_SCHEME)
        val inner = evaluate(origin, policy, Purpose.SUBRESOURCE, isRedirect, hasUserGesture)
        return if (inner.allowed) {
            Decision(true, BlockReason.ALLOWED, inner.host, purpose, isRedirect, hasUserGesture)
        } else {
            inner.copy(purpose = purpose)
        }
    }

    private fun schemeOf(raw: String): String? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val scheme = raw.substring(0, colon).lowercase()
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return scheme
    }

    /** Converts a Unicode hostname to punycode so lookalikes cannot skip parsing. */
    private fun toAsciiUrl(raw: String): String {
        if (raw.all { it.code < 128 }) return raw
        val marker = "://"
        val sep = raw.indexOf(marker)
        if (sep < 0) return raw
        val scheme = raw.substring(0, sep)
        val rest = raw.substring(sep + marker.length)
        val cut = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (cut < 0) rest else rest.substring(0, cut)
        val tail = if (cut < 0) "" else rest.substring(cut)
        val at = authority.lastIndexOf('@')
        val user = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val port = run {
            val colon = hostPort.lastIndexOf(':')
            if (colon > 0 && hostPort.substring(colon + 1).all { it.isDigit() }) hostPort.substring(colon) else ""
        }
        val host = if (port.isEmpty()) hostPort else hostPort.substring(0, hostPort.length - port.length)
        val ascii = try {
            java.net.IDN.toASCII(host, java.net.IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            return raw
        }
        return scheme + marker + user + ascii + port + tail
    }

    private fun blobOrigin(rest: String): String? {
        val scheme = when {
            rest.startsWith("https://") -> "https://"
            rest.startsWith("http://") -> "http://"
            else -> return null
        }
        val after = rest.substring(scheme.length)
        val hostPort = after.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPort.isEmpty()) return null
        return scheme + hostPort
    }

    private fun decideHost(
        host: String,
        policy: BrowserPolicy,
        purpose: Purpose,
        isRedirect: Boolean,
        hasUserGesture: Boolean,
    ): Decision {
        if (policy.isHostAllowed(host)) {
            return Decision(true, BlockReason.ALLOWED, host, purpose, isRedirect, hasUserGesture)
        }
        if (purpose == Purpose.SUBRESOURCE && !policy.blockThirdPartyResources) {
            return Decision(true, BlockReason.ALLOWED, host, purpose, isRedirect, hasUserGesture)
        }
        val reason = if (purpose == Purpose.SUBRESOURCE) {
            BlockReason.SUBRESOURCE_BLOCKED
        } else {
            BlockReason.NOT_ALLOWLISTED
        }
        return Decision(false, reason, host, purpose, isRedirect, hasUserGesture)
    }

    private fun safeHost(uri: URI): String? {
        val rawHost = try {
            uri.host
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return HostNames.normalize(rawHost)
    }
}

object BlockMessages {
    fun title(decision: UrlPolicy.Decision): String {
        return when (decision.reason) {
            UrlPolicy.BlockReason.ALLOWED -> "Allowed"
            UrlPolicy.BlockReason.CLEARTEXT_HTTP -> "Insecure connection blocked"
            UrlPolicy.BlockReason.SSL -> "Certificate error"
            UrlPolicy.BlockReason.CREDENTIALS_IN_URL -> "Address blocked"
            UrlPolicy.BlockReason.UNSUPPORTED_SCHEME -> "Unsupported link blocked"
            UrlPolicy.BlockReason.DOWNLOAD_DISABLED -> "Download blocked"
            UrlPolicy.BlockReason.SUBRESOURCE_BLOCKED -> "Embedded content blocked"
            UrlPolicy.BlockReason.NOT_ALLOWLISTED ->
                if (decision.isRedirect) "Redirect blocked" else "Website blocked"
            else -> "Website blocked"
        }
    }

    /**
     * User-facing explanation. The host is included. Query strings and
     * fragments are not, because they can carry tokens.
     */
    fun detail(decision: UrlPolicy.Decision): String {
        val where = decision.host?.let { " ($it)" }.orEmpty()
        val base = when (decision.reason) {
            UrlPolicy.BlockReason.ALLOWED -> "This address is on the allowlist."
            UrlPolicy.BlockReason.EMPTY -> "Enter a website address."
            UrlPolicy.BlockReason.INVALID -> "That address could not be read safely."
            UrlPolicy.BlockReason.TOO_LONG -> "That address is too long."
            UrlPolicy.BlockReason.UNSUPPORTED_SCHEME ->
                "Only https addresses are opened. Other link types stay inside SecureBrowse and are discarded."
            UrlPolicy.BlockReason.CLEARTEXT_HTTP ->
                "Plain http is disabled. SecureBrowse does not send traffic without TLS."
            UrlPolicy.BlockReason.CREDENTIALS_IN_URL ->
                "Addresses that embed a username or password are rejected."
            UrlPolicy.BlockReason.HOST_MISSING -> "That address has no website name."
            UrlPolicy.BlockReason.NOT_ALLOWLISTED ->
                if (decision.isRedirect) {
                    "The page tried to redirect to a website the administrator has not approved$where."
                } else if (decision.purpose == UrlPolicy.Purpose.POPUP) {
                    "A popup to an unapproved website was blocked$where. It was not sent to another browser."
                } else {
                    "The administrator has not approved this website$where."
                }
            UrlPolicy.BlockReason.SUBRESOURCE_BLOCKED ->
                "This page asked for content from an unapproved domain$where. Add that domain only if you trust it."
            UrlPolicy.BlockReason.DOWNLOAD_DISABLED ->
                "Downloads are off. An administrator can allow downloads from approved https sites."
            UrlPolicy.BlockReason.SSL ->
                "The certificate check failed$where. The connection was cancelled and was not bypassed."
        }
        return base
    }
}
