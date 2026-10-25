package com.securebrowse.policy

/**
 * A focus session is a timed lock-task allowlist. SecureBrowse stays on the
 * list so the person can come back to this screen. Other packages are added
 * only when an administrator selected them. Status-bar and notification flags
 * are decisions; the app module is what talks to DevicePolicyManager.
 *
 * Feature flag values match DevicePolicyManager from API 28 and are checked
 * against the framework in the instrumented test.
 */
data class FocusSession(
    val endsAtEpochMs: Long,
    val durationMinutes: Int,
    val packages: List<String>,
    val blockStatusBar: Boolean,
    val blockNotifications: Boolean,
)

object SessionRules {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 12 * 60
    const val DEFAULT_MINUTES = 25
    const val MAX_PACKAGES = 30

    const val FEATURE_NONE = 0
    const val FEATURE_SYSTEM_INFO = 1
    const val FEATURE_NOTIFICATIONS = 2

    private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Packages that defeat a session if the person can open them. They are
     * not rejected: the administrator is warned and can still allow them.
     */
    val RISKY_PACKAGES = setOf(
        "com.android.settings",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.google.android.apps.chrome",
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    fun normalizePackage(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length !in 3..200) return null
        if (trimmed.any { it.isWhitespace() || it == '/' || it == ':' }) return null
        if (!PACKAGE_NAME.matches(trimmed)) return null
        return trimmed
    }

    fun packageRejection(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Enter an app id such as org.mozilla.firefox."
        if (normalizePackage(trimmed) == null) {
            return "“$trimmed” is not an Android app id."
        }
        return null
    }

    fun minutesRejection(minutes: Int?): String? {
        if (minutes == null) return "Enter a whole number of minutes."
        if (minutes !in MIN_MINUTES..MAX_MINUTES) {
            return "Session length must be from $MIN_MINUTES to $MAX_MINUTES minutes."
        }
        return null
    }

    /**
     * Lock-task features granted during the session. Home, recents, global
     * actions, and the keyguard stay off. System info is the status-bar clock.
     * Notifications is the shade content lock task is allowed to show.
     */
    fun featureFlags(blockStatusBar: Boolean, blockNotifications: Boolean): Int {
        var flags = FEATURE_NONE
        if (!blockStatusBar) flags = flags or FEATURE_SYSTEM_INFO
        if (!blockNotifications) flags = flags or FEATURE_NOTIFICATIONS
        return flags
    }

    /** This app is always first. Invalid and duplicate ids are dropped. */
    fun lockTaskPackages(self: String, selected: List<String>): List<String> {
        val host = normalizePackage(self) ?: self.trim()
        val extras = selected.mapNotNull { normalizePackage(it) }.filter { it != host }
        return (listOf(host) + extras).distinct()
    }

    fun riskySelection(packages: List<String>): List<String> {
        return packages.mapNotNull { normalizePackage(it) }.filter { it in RISKY_PACKAGES }.distinct()
    }

    fun remainingMillis(session: FocusSession, nowEpochMs: Long): Long {
        return (session.endsAtEpochMs - nowEpochMs).coerceAtLeast(0L)
    }

    fun isExpired(session: FocusSession, nowEpochMs: Long): Boolean {
        return nowEpochMs >= session.endsAtEpochMs
    }

    fun formatRemaining(millis: Long): String {
        val sec = millis.coerceAtLeast(0L) / 1000L
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }
}

object SessionCodec {
    private val KEYS = setOf(
        "endsAtEpochMs",
        "durationMinutes",
        "packages",
        "blockStatusBar",
        "blockNotifications",
    )

    fun encode(session: FocusSession): String {
        val root = linkedMapOf<String, Any?>(
            "endsAtEpochMs" to session.endsAtEpochMs,
            "durationMinutes" to session.durationMinutes,
            "packages" to session.packages,
            "blockStatusBar" to session.blockStatusBar,
            "blockNotifications" to session.blockNotifications,
        )
        return JsonValues.stringify(root)
    }

    fun decode(json: String): FocusSession {
        if (json.length > PolicyCodec.MAX_BYTES) throw PolicyFormatException("Session record is too large.")
        val root = JsonValues.parse(json) as? Map<*, *>
            ?: throw PolicyFormatException("Session record must be an object.")
        val keys = root.keys.map { it as? String ?: throw PolicyFormatException("Invalid field name.") }
        val unknown = keys.filter { it !in KEYS }
        if (unknown.isNotEmpty()) throw PolicyFormatException("Unknown session field “${unknown.first()}”.")
        val ends = when (val value = root["endsAtEpochMs"]) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw PolicyFormatException("Session end time is missing.")
        }
        if (ends <= 0L) throw PolicyFormatException("Session end time is not valid.")
        val minutes = root["durationMinutes"] as? Int
            ?: throw PolicyFormatException("Session length is missing.")
        SessionRules.minutesRejection(minutes)?.let { throw PolicyFormatException(it) }
        val rawPackages = root["packages"] as? List<*>
            ?: throw PolicyFormatException("Session app list is missing.")
        if (rawPackages.size > SessionRules.MAX_PACKAGES) {
            throw PolicyFormatException("Too many session apps.")
        }
        val packages = rawPackages.map { item ->
            val text = item as? String ?: throw PolicyFormatException("A session app id must be text.")
            SessionRules.normalizePackage(text) ?: throw PolicyFormatException("“$text” is not an app id.")
        }.distinct()
        return FocusSession(
            endsAtEpochMs = ends,
            durationMinutes = minutes,
            packages = packages,
            blockStatusBar = bool(root, "blockStatusBar"),
            blockNotifications = bool(root, "blockNotifications"),
        )
    }

    private fun bool(root: Map<*, *>, key: String): Boolean {
        return root[key] as? Boolean ?: throw PolicyFormatException("“$key” must be true or false.")
    }
}

class SessionRepository(private val store: StringStore) {
    fun current(): FocusSession? {
        val raw = store.read()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            SessionCodec.decode(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun save(session: FocusSession) {
        SessionCodec.decode(SessionCodec.encode(session))
        store.write(SessionCodec.encode(session))
    }

    fun clear() {
        store.write("")
    }
}
