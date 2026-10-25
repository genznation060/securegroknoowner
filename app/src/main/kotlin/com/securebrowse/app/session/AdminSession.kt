package com.securebrowse.app.session

/**
 * In-memory unlock. Process death clears it. This is not stored, and it is
 * not equivalent to a device-lock screen.
 */
object AdminSession {
    private const val WINDOW_MS = 5 * 60 * 1000L

    @Volatile
    private var expiresAtEpochMs: Long = 0L

    fun grant(now: Long = System.currentTimeMillis()) {
        expiresAtEpochMs = now + WINDOW_MS
    }

    fun clear() {
        expiresAtEpochMs = 0L
    }

    fun isActive(now: Long = System.currentTimeMillis()): Boolean = now < expiresAtEpochMs
}
