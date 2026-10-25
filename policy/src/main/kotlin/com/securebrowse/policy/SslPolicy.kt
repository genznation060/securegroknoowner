package com.securebrowse.policy

/**
 * SSL failures are always cancelled. There is no proceed path, no user
 * bypass, and no custom trust of invalid certificates.
 */
object SslPolicy {
    const val CANCEL = "cancel"

    fun action(@Suppress("UNUSED_PARAMETER") primaryError: Int): String = CANCEL

    fun mayProceed(@Suppress("UNUSED_PARAMETER") primaryError: Int): Boolean = false
}
