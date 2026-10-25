package com.securebrowse.app.data

import android.content.Context
import com.securebrowse.policy.AdminAuthenticator
import com.securebrowse.policy.BrowserPolicy
import com.securebrowse.policy.PolicyRepository
import com.securebrowse.policy.SessionRepository
import java.util.concurrent.atomic.AtomicReference

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val policies = PolicyRepository(KeystoreStringStore(appContext, "policy.bin"))
    val sessions = SessionRepository(PrefStringStore(appContext, "focus-session"))
    val admin = AdminAuthenticator(KeystoreStringStore(appContext, "admin.bin"))
    private val policyRef = AtomicReference(BrowserPolicy())

    @Volatile
    var policyDamaged: String? = null

    @Volatile
    var browsingEpoch: Int = 0

    fun bumpBrowsingEpoch() {
        browsingEpoch += 1
    }

    init {
        reload()
    }

    fun reload() {
        policyDamaged = null
        try {
            policyRef.set(policies.get())
        } catch (ex: Exception) {
            policyDamaged = ex.message ?: "unreadable"
            policyRef.set(BrowserPolicy())
        }
    }

    fun currentPolicy(): BrowserPolicy = policyRef.get()
}
