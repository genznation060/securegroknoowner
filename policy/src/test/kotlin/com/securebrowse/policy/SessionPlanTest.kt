package com.securebrowse.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {
    @Test
    fun packageIdsAndMinutes() {
        assertEquals("org.mozilla.firefox", SessionRules.normalizePackage(" org.mozilla.firefox "))
        assertNull(SessionRules.normalizePackage("firefox"))
        assertNull(SessionRules.normalizePackage("com.android.settings/com.android.settings.Settings"))
        assertNull(SessionRules.normalizePackage("..bad"))
        assertNull(SessionRules.normalizePackage("com..bad"))
        assertTrue(SessionRules.packageRejection("not a package")!!.contains("not an Android app id"))
        assertNull(SessionRules.minutesRejection(25))
        assertTrue(SessionRules.minutesRejection(0)!!.contains("1"))
        assertTrue(SessionRules.minutesRejection(721)!!.contains("720"))
        assertTrue(SessionRules.minutesRejection(null)!!.contains("whole number"))
    }

    @Test
    fun lockTaskListAlwaysKeepsThisAppAndDropsJunk() {
        val packages = SessionRules.lockTaskPackages(
            "com.securebrowse.app",
            listOf("org.mozilla.firefox", "com.securebrowse.app", "not valid", "org.mozilla.firefox", "com.android.calculator2"),
        )
        assertEquals(
            listOf("com.securebrowse.app", "org.mozilla.firefox", "com.android.calculator2"),
            packages,
        )
        assertEquals(listOf("com.android.settings"), SessionRules.riskySelection(listOf("com.android.settings", "com.android.calculator2")))
    }

    @Test
    fun shadeFlagsMatchTheDocumentedBits() {
        assertEquals(SessionRules.FEATURE_NONE, SessionRules.featureFlags(blockStatusBar = true, blockNotifications = true))
        assertEquals(SessionRules.FEATURE_SYSTEM_INFO, SessionRules.featureFlags(blockStatusBar = false, blockNotifications = true))
        assertEquals(SessionRules.FEATURE_NOTIFICATIONS, SessionRules.featureFlags(blockStatusBar = true, blockNotifications = false))
        assertEquals(
            SessionRules.FEATURE_SYSTEM_INFO or SessionRules.FEATURE_NOTIFICATIONS,
            SessionRules.featureFlags(blockStatusBar = false, blockNotifications = false),
        )
    }

    @Test
    fun countdownExpiresAndFormats() {
        val session = FocusSession(
            endsAtEpochMs = 1_000_000,
            durationMinutes = 25,
            packages = listOf("org.mozilla.firefox"),
            blockStatusBar = true,
            blockNotifications = true,
        )
        assertEquals(90_000L, SessionRules.remainingMillis(session, 910_000))
        assertEquals("01:30", SessionRules.formatRemaining(90_000))
        assertEquals("1:00:00", SessionRules.formatRemaining(3_600_000))
        assertFalse(SessionRules.isExpired(session, 999_999))
        assertTrue(SessionRules.isExpired(session, 1_000_000))
        assertEquals(0L, SessionRules.remainingMillis(session, 1_000_000))
        assertEquals("00:00", SessionRules.formatRemaining(0))
    }

    @Test
    fun sessionRecordRoundTripAndCorruptClears() {
        val store = MemoryStringStore()
        val repo = SessionRepository(store)
        assertNull(repo.current())
        val session = FocusSession(
            endsAtEpochMs = 1_800_000_000_000L,
            durationMinutes = 15,
            packages = listOf("com.android.calculator2"),
            blockStatusBar = true,
            blockNotifications = false,
        )
        repo.save(session)
        assertEquals(session, SessionRepository(store).current())
        assertFalse(store.read()!!.contains("passphrase"))
        repo.clear()
        assertNull(repo.current())
        store.write("{")
        assertNull(repo.current())
    }

    @Test
    fun oldAllowlistWithoutSessionFieldsStillLoads() {
        val json = """
            {"version":1,"homepage":null,"javascriptEnabled":true,"blockThirdPartyResources":true,"allowDownloads":false,"allowThirdPartyCookies":false,"kioskAutoStart":true,"strictKioskRestrictions":false,"entries":[]}
        """.trim()
        val policy = PolicyCodec.decode(json)
        assertEquals(25, policy.sessionMinutes)
        assertTrue(policy.sessionPackages.isEmpty())
        assertTrue(policy.blockStatusBar)
        assertTrue(policy.blockNotifications)
        val again = PolicyCodec.decode(PolicyCodec.encode(policy))
        assertEquals(policy, again)
    }

    @Test
    fun repositoryEditsSessionAppsSeparatelyFromDomains() {
        val repo = PolicyRepository(MemoryStringStore())
        assertTrue(repo.updateSessionOptions(45, blockStatusBar = true, blockNotifications = true) is PolicyEdit.Saved)
        assertTrue(repo.addSessionPackage(" org.mozilla.firefox ") is PolicyEdit.Saved)
        assertTrue(repo.addSessionPackage("com.android.settings") is PolicyEdit.Saved)
        assertTrue(repo.addSessionPackage("chrome") is PolicyEdit.Rejected)
        assertTrue(repo.updateSessionOptions(0, true, true) is PolicyEdit.Rejected)
        assertEquals(45, repo.get().sessionMinutes)
        assertEquals(listOf("com.android.settings", "org.mozilla.firefox"), repo.get().sessionPackages)
        assertTrue(repo.removeSessionPackage("com.android.settings") is PolicyEdit.Saved)
        assertEquals(listOf("org.mozilla.firefox"), repo.get().sessionPackages)
        assertTrue(repo.get().entries.isEmpty())
        val imported = PolicyRepository(MemoryStringStore())
        assertTrue(imported.importDocument(repo.exportDocument()) is PolicyEdit.Saved)
        assertEquals(repo.get(), imported.get())
    }
}
