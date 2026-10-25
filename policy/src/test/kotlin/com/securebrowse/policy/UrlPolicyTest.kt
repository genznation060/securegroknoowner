package com.securebrowse.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val sites = BrowserPolicy(
        entries = listOf(
            DomainEntry("google.com", allowSubdomains = true),
            DomainEntry("wikipedia.org", allowSubdomains = true),
            DomainEntry("youtube.com", allowSubdomains = false),
        ),
    )

    @Test
    fun approvedExactDomains() {
        assertAllowed("https://google.com")
        assertAllowed("https://google.com/")
        assertAllowed("https://google.com:443/search?q=cats")
        assertAllowed("https://GOOGLE.com")
        assertAllowed("https://google.com.")
        assertAllowed("https://wikipedia.org/wiki/Android")
        assertAllowed("https://youtube.com/watch?v=abc")
    }

    @Test
    fun approvedSubdomainsRespectTheFlag() {
        assertAllowed("https://www.google.com")
        assertAllowed("https://accounts.google.com/ServiceLogin")
        assertAllowed("https://a.b.google.com/x")
        assertAllowed("https://en.wikipedia.org/wiki/WebView")
        assertBlocked("https://www.youtube.com", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://m.youtube.com/feed", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
    }

    @Test
    fun unapprovedAndLookalikeDomains() {
        assertBlocked("https://example.com", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://notgoogle.com", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://evilgoogle.com", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://google.com.evil.com", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://google.com.evil.com/path", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://evil.com/google.com", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://youtu.be/abc", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertBlocked("https://google.com.attacker.test", UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        val cyrillicOo = "https://g\u043eogle.com/"
        assertBlocked(cyrillicOo, UrlPolicy.BlockReason.NOT_ALLOWLISTED)
        assertFalse(UrlPolicy.evaluate(cyrillicOo, sites, UrlPolicy.Purpose.MAIN_FRAME).host == "google.com")
    }

    @Test
    fun redirectsPopupsAndGesturesDoNotBypass() {
        val redirect = UrlPolicy.evaluate(
            "https://evil.example/phish",
            sites,
            UrlPolicy.Purpose.MAIN_FRAME,
            isRedirect = true,
            hasUserGesture = false,
        )
        assertFalse(redirect.allowed)
        assertTrue(redirect.isRedirect)
        assertEquals(UrlPolicy.BlockReason.NOT_ALLOWLISTED, redirect.reason)
        assertTrue(BlockMessages.title(redirect).contains("Redirect"))

        val jsNav = UrlPolicy.evaluate(
            "javascript:location='https://evil.example'",
            sites,
            UrlPolicy.Purpose.MAIN_FRAME,
            hasUserGesture = false,
        )
        assertEquals(UrlPolicy.BlockReason.UNSUPPORTED_SCHEME, jsNav.reason)

        val allowedWithoutGesture = UrlPolicy.evaluate(
            "https://www.google.com/app",
            sites,
            UrlPolicy.Purpose.MAIN_FRAME,
            hasUserGesture = false,
        )
        assertTrue(allowedWithoutGesture.allowed)

        val popup = UrlPolicy.evaluate(
            "https://evil.example/popup",
            sites,
            UrlPolicy.Purpose.POPUP,
            hasUserGesture = true,
        )
        assertFalse(popup.allowed)
        assertFalse(popup.openExternally)
        assertTrue(BlockMessages.detail(popup).contains("not sent to another browser"))
        assertTrue(BlockMessages.detail(popup).contains("popup"))

        val allowedPopup = UrlPolicy.evaluate(
            "https://en.wikipedia.org/wiki/Kiosk",
            sites,
            UrlPolicy.Purpose.POPUP,
        )
        assertTrue(allowedPopup.allowed)
        assertFalse(allowedPopup.openExternally)
    }

    @Test
    fun schemesAndCleartext() {
        assertBlocked("http://google.com", UrlPolicy.BlockReason.CLEARTEXT_HTTP)
        assertBlocked("http://wikipedia.org/wiki/A", UrlPolicy.BlockReason.CLEARTEXT_HTTP)
        assertBlocked("javascript:alert(1)", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("file:///sdcard/evil.html", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("content://com.evil/1", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("intent://scan/#Intent;scheme=https;end", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("data:text/html,<h1>no</h1>", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("mailto:admin@example.com", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("market://details?id=com.evil", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("blob:https://google.com/uuid", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("about:srcdoc", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertBlocked("ftp://google.com/file", UrlPolicy.BlockReason.UNSUPPORTED_SCHEME)
        assertTrue(UrlPolicy.evaluate("about:blank", sites, UrlPolicy.Purpose.MAIN_FRAME).allowed)
        assertFalse(UrlPolicy.evaluate("about:blank", sites, UrlPolicy.Purpose.MAIN_FRAME, isRedirect = true).allowed)
    }

    @Test
    fun credentialsAndGarbage() {
        assertBlocked("https://user:pass@google.com/", UrlPolicy.BlockReason.CREDENTIALS_IN_URL)
        assertBlocked("https://google.com@evil.example/", UrlPolicy.BlockReason.CREDENTIALS_IN_URL)
        assertBlocked("", UrlPolicy.BlockReason.EMPTY)
        assertBlocked("   ", UrlPolicy.BlockReason.EMPTY)
        assertBlocked("https://", UrlPolicy.BlockReason.INVALID)
        assertBlocked("not a url", UrlPolicy.BlockReason.INVALID)
        assertBlocked("https://google.com/a\nb", UrlPolicy.BlockReason.INVALID)
        assertBlocked("a".repeat(UrlPolicy.MAX_URL_LENGTH + 1), UrlPolicy.BlockReason.TOO_LONG)
        val detail = BlockMessages.detail(
            UrlPolicy.evaluate("https://evil.example/reset?token=secret", sites, UrlPolicy.Purpose.MAIN_FRAME),
        )
        assertFalse(detail.contains("token"))
        assertFalse(detail.contains("secret"))
        assertTrue(detail.contains("evil.example"))
    }

    @Test
    fun subresourcesDownloadsAndThirdParties() {
        val script = UrlPolicy.evaluate(
            "https://gstatic.com/script.js",
            sites,
            UrlPolicy.Purpose.SUBRESOURCE,
        )
        assertEquals(UrlPolicy.BlockReason.SUBRESOURCE_BLOCKED, script.reason)

        val ownScript = UrlPolicy.evaluate(
            "https://ajax.googleapis.com/ajax/libs/x",
            BrowserPolicy(entries = listOf(DomainEntry("google.com", true))),
            UrlPolicy.Purpose.SUBRESOURCE,
        )
        assertFalse(ownScript.allowed)
        assertFalse(ownScript.host == "google.com")

        assertTrue(
            UrlPolicy.evaluate(
                "https://www.gstatic.com/x",
                sites.copy(
                    entries = sites.entries + DomainEntry("gstatic.com", true),
                ),
                UrlPolicy.Purpose.SUBRESOURCE,
            ).allowed,
        )

        val relaxed = sites.copy(blockThirdPartyResources = false)
        assertTrue(UrlPolicy.evaluate("https://cdn.example/app.js", relaxed, UrlPolicy.Purpose.SUBRESOURCE).allowed)
        assertFalse(UrlPolicy.evaluate("https://cdn.example/", relaxed, UrlPolicy.Purpose.MAIN_FRAME).allowed)
        assertFalse(UrlPolicy.evaluate("http://cdn.example/app.js", relaxed, UrlPolicy.Purpose.SUBRESOURCE).allowed)

        assertTrue(
            UrlPolicy.evaluate("data:image/png;base64,aaaa", sites, UrlPolicy.Purpose.SUBRESOURCE).allowed,
        )
        assertTrue(
            UrlPolicy.evaluate("blob:https://www.google.com/uuid", sites, UrlPolicy.Purpose.SUBRESOURCE).allowed,
        )
        assertFalse(
            UrlPolicy.evaluate("blob:https://evil.example/uuid", sites, UrlPolicy.Purpose.SUBRESOURCE).allowed,
        )
        assertFalse(
            UrlPolicy.evaluate("blob:https://evil.example", sites, UrlPolicy.Purpose.SUBRESOURCE).allowed,
        )

        val downloadOff = UrlPolicy.evaluateDownload("https://google.com/file.zip", sites)
        assertEquals(UrlPolicy.BlockReason.DOWNLOAD_DISABLED, downloadOff.reason)
        val downloadOn = UrlPolicy.evaluateDownload(
            "https://google.com/file.zip",
            sites.copy(allowDownloads = true),
        )
        assertTrue(downloadOn.allowed)
        assertFalse(downloadOn.openExternally)
        assertFalse(UrlPolicy.evaluateDownload("https://evil.example/a.bin", sites.copy(allowDownloads = true)).allowed)
        assertEquals(
            UrlPolicy.BlockReason.CLEARTEXT_HTTP,
            UrlPolicy.evaluateDownload("http://google.com/a.bin", sites.copy(allowDownloads = true)).reason,
        )
    }

    @Test
    fun sslNeverProceeds() {
        listOf(0, 1, 2, 3, 4, 5).forEach { code ->
            assertFalse(SslPolicy.mayProceed(code))
            assertEquals(SslPolicy.CANCEL, SslPolicy.action(code))
        }
    }

    private fun assertAllowed(url: String) {
        val decision = UrlPolicy.evaluate(url, sites, UrlPolicy.Purpose.MAIN_FRAME, hasUserGesture = true)
        assertTrue(url, decision.allowed)
        assertFalse(decision.openExternally)
    }

    private fun assertBlocked(url: String, reason: UrlPolicy.BlockReason) {
        val decision = UrlPolicy.evaluate(url, sites, UrlPolicy.Purpose.MAIN_FRAME, hasUserGesture = true)
        assertFalse(url, decision.allowed)
        assertEquals(url, reason, decision.reason)
        assertFalse(decision.openExternally)
    }
}

class PolicyRepositoryTest {
    @Test
    fun survivesRestartAndEditsHomepage() {
        val memory = MemoryStringStore()
        val first = PolicyRepository(memory)
        val added = first.addHost("Wikipedia.org", allowSubdomains = true)
        assertTrue(added is PolicyEdit.Saved)
        assertEquals("wikipedia.org", (added as PolicyEdit.Saved).policy.entries.single().host)
        assertTrue(first.setHomepage("https://en.wikipedia.org/") is PolicyEdit.Saved)
        assertTrue(first.addHost("notgoogle.com", true) is PolicyEdit.Saved)
        assertTrue(first.addHost("com", true) is PolicyEdit.Rejected)
        assertTrue(first.addHost("*.wikipedia.org", true) is PolicyEdit.Rejected)
        assertTrue(first.setHomepage("http://en.wikipedia.org/") is PolicyEdit.Rejected)
        assertTrue(first.setHomepage("https://example.com") is PolicyEdit.Rejected)

        val restarted = PolicyRepository(memory)
        assertEquals(listOf("notgoogle.com", "wikipedia.org"), restarted.get().entries.map { it.host })
        assertEquals("https://en.wikipedia.org/", restarted.get().homepage)
        val removed = restarted.removeHost("wikipedia.org")
        assertTrue(removed is PolicyEdit.Saved)
        assertNull((removed as PolicyEdit.Saved).policy.homepage)
        assertEquals(listOf("notgoogle.com"), restarted.get().entries.map { it.host })

        val exported = restarted.exportDocument()
        assertFalse(exported.contains("verifier"))
        assertFalse(exported.contains("password"))
        val imported = PolicyRepository(MemoryStringStore())
        assertTrue(imported.importDocument(exported) is PolicyEdit.Saved)
        assertEquals(restarted.get(), imported.get())
        assertTrue(imported.importDocument("{") is PolicyEdit.Rejected)
    }

    @Test
    fun suggestionsAreNotImplicit() {
        val repo = PolicyRepository(MemoryStringStore())
        repo.addHost("youtube.com", allowSubdomains = true)
        assertTrue(CompanionSuggestions.forHost("youtube.com").contains("googlevideo.com"))
        assertFalse(repo.get().isHostAllowed("googlevideo.com"))
        assertFalse(repo.get().isHostAllowed("youtu.be"))
    }
}

class AdminAuthenticatorTest {
    @Test
    fun setupVerifyLockoutAndRestart() {
        val memory = MemoryStringStore()
        var now = 1_700_000_000_000L
        val auth = AdminAuthenticator(memory, clock = { now })
        assertTrue(auth.setup("short".toCharArray()) is AuthResult.InvalidSecret)
        assertTrue(auth.setup("1234567890".toCharArray()) is AuthResult.InvalidSecret)
        assertTrue(auth.setup("password123".toCharArray()) is AuthResult.InvalidSecret)
        assertEquals(AuthResult.Accepted, auth.setup("correct horse battery".toCharArray()))
        assertFalse(memory.read()!!.contains("correct horse"))
        assertTrue(memory.read()!!.contains("pbkdf2-hmac-sha256"))

        val restarted = AdminAuthenticator(memory, clock = { now })
        assertEquals(AuthResult.Accepted, restarted.verify("correct horse battery".toCharArray()))
        repeat(4) {
            assertTrue(restarted.verify("wrong secret!".toCharArray()) is AuthResult.Rejected)
        }
        val locked = restarted.verify("wrong secret!".toCharArray())
        assertTrue(locked is AuthResult.Locked)
        assertTrue(restarted.verify("correct horse battery".toCharArray()) is AuthResult.Locked)
        now = (locked as AuthResult.Locked).retryAtEpochMs
        assertEquals(AuthResult.Accepted, restarted.verify("correct horse battery".toCharArray()))

        val again = AdminAuthenticator(MemoryStringStore(memory.read()), clock = { now })
        assertEquals(AuthResult.Accepted, again.change("correct horse battery".toCharArray(), "a different secret".toCharArray()))
        assertTrue(again.verify("correct horse battery".toCharArray()) is AuthResult.Rejected)
        assertEquals(AuthResult.Accepted, again.verify("a different secret".toCharArray()))
    }
}

class KioskAssessmentTest {
    @Test
    fun posturesAndExit() {
        val normal = KioskAssessment.describe(
            KioskSnapshot(ManagementPosture.UNMANAGED, lockTaskPermitted = false, LockPresentation.NOT_LOCKED),
        )
        assertEquals(LockPresentation.NOT_LOCKED, normal.presentation)
        assertFalse(normal.canPreventLeaving)
        assertTrue(normal.summary.contains("Chrome"))

        val pinned = KioskAssessment.describe(
            KioskSnapshot(ManagementPosture.UNMANAGED, lockTaskPermitted = false, LockPresentation.SCREEN_PINNING),
        )
        assertEquals(LockPresentation.SCREEN_PINNING, pinned.presentation)
        assertFalse(pinned.canPreventLeaving)

        val kiosk = KioskAssessment.describe(
            KioskSnapshot(ManagementPosture.DEVICE_OWNER, lockTaskPermitted = true, LockPresentation.DEDICATED_KIOSK),
        )
        assertEquals(LockPresentation.DEDICATED_KIOSK, kiosk.presentation)
        assertTrue(kiosk.canPreventLeaving)

        val pretended = KioskAssessment.describe(
            KioskSnapshot(ManagementPosture.UNMANAGED, lockTaskPermitted = false, LockPresentation.DEDICATED_KIOSK),
        )
        assertEquals(LockPresentation.SCREEN_PINNING, pretended.presentation)
        assertFalse(pretended.canPreventLeaving)

        assertFalse(KioskAssessment.exitAllowed(adminAuthenticated = false))
        assertTrue(KioskAssessment.exitAllowed(adminAuthenticated = true))
        assertEquals(1, KioskAssessment.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        assertEquals("android.app.action.GET_PROVISIONING_MODE", KioskAssessment.ACTION_GET_PROVISIONING_MODE)
        assertEquals("android.app.action.ADMIN_POLICY_COMPLIANCE", KioskAssessment.ACTION_ADMIN_POLICY_COMPLIANCE)
    }
}
