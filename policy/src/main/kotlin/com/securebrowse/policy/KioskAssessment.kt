package com.securebrowse.policy

/**
 * Distinguishes a normal app, Android screen pinning, and a real dedicated
 * device. WebView cannot pin the device. Lock-task allowlisting requires
 * device-owner (or an affiliated profile owner) and is never acquired silently.
 */
enum class ManagementPosture {
    UNMANAGED,
    DEVICE_OWNER,
}

enum class LockPresentation {
    /** App is a normal task. Home, Chrome, and Settings remain available. */
    NOT_LOCKED,
    /** User-confirmed screen pinning. Back plus Recents leaves it. */
    SCREEN_PINNING,
    /** Device-owner lock task. Ordinary navigation away is blocked by Android. */
    DEDICATED_KIOSK,
}

data class KioskSnapshot(
    val posture: ManagementPosture,
    val lockTaskPermitted: Boolean,
    val lockTaskMode: LockPresentation,
)

data class KioskDescription(
    val presentation: LockPresentation,
    val canPreventLeaving: Boolean,
    val summary: String,
)

object KioskAssessment {
    /**
     * Framework values. The app module must pass the same integers through to
     * DevicePolicyManager during provisioning.
     */
    const val PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1
    const val ACTION_GET_PROVISIONING_MODE = "android.app.action.GET_PROVISIONING_MODE"
    const val ACTION_ADMIN_POLICY_COMPLIANCE = "android.app.action.ADMIN_POLICY_COMPLIANCE"
    const val EXTRA_PROVISIONING_MODE = "android.app.extra.PROVISIONING_MODE"

    fun describe(snapshot: KioskSnapshot): KioskDescription {
        val presentation = when {
            snapshot.posture == ManagementPosture.DEVICE_OWNER &&
                snapshot.lockTaskPermitted &&
                snapshot.lockTaskMode == LockPresentation.DEDICATED_KIOSK ->
                LockPresentation.DEDICATED_KIOSK
            snapshot.lockTaskMode == LockPresentation.SCREEN_PINNING ->
                LockPresentation.SCREEN_PINNING
            snapshot.lockTaskMode == LockPresentation.DEDICATED_KIOSK &&
                snapshot.posture != ManagementPosture.DEVICE_OWNER ->
                LockPresentation.SCREEN_PINNING
            else -> LockPresentation.NOT_LOCKED
        }
        val summary = when (presentation) {
            LockPresentation.DEDICATED_KIOSK ->
                "Dedicated kiosk. Android is keeping SecureBrowse in front because this app is the device owner and lock-task allowlist. This is not a claim about other browsers on an unmanaged phone."
            LockPresentation.SCREEN_PINNING ->
                "Screen pinning only. The person holding the device can leave with Back and Recents. This is not kiosk lockdown."
            LockPresentation.NOT_LOCKED ->
                "Normal browser mode. The allowlist applies only inside SecureBrowse. Chrome and every other app are unaffected until a device owner provisions lock task."
        }
        return KioskDescription(
            presentation = presentation,
            canPreventLeaving = presentation == LockPresentation.DEDICATED_KIOSK,
            summary = summary,
        )
    }

    /** Leaving lock task is an administrator action. There is no silent exit. */
    fun exitAllowed(adminAuthenticated: Boolean): Boolean = adminAuthenticated
}

object CompanionSuggestions {
    private val BY_HOST = mapOf(
        "youtube.com" to listOf(
            "youtu.be",
            "googlevideo.com",
            "ytimg.com",
            "ggpht.com",
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com",
        ),
        "google.com" to listOf(
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com",
            "ggpht.com",
        ),
        "wikipedia.org" to listOf(
            "wikimedia.org",
            "wmflabs.org",
        ),
    )

    /**
     * Suggestions are not added automatically. The administrator has to approve
     * each extra domain. googleusercontent.com in particular hosts untrusted
     * user content; approve it only when the site cannot function without it.
     */
    fun forHost(host: String): List<String> {
        val normalized = HostNames.normalize(host) ?: return emptyList()
        val direct = BY_HOST[normalized].orEmpty()
        val parent = BY_HOST.entries.firstOrNull { (rule, _) ->
            HostNames.matches(normalized, rule, allowSubdomains = true)
        }?.value.orEmpty()
        return (direct + parent).distinct()
    }
}
