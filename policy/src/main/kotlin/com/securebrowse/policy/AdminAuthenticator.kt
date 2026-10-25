package com.securebrowse.policy

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local administrator verifier.
 *
 * The secret is never stored. A random salt and a PBKDF2-HMAC-SHA256 verifier
 * are stored instead. This is not device-level security: anyone who can clear
 * the app's private storage, or who controls an unmanaged device, can discard
 * the verifier. Dedicated-device policies are what keep ordinary users out of
 * Settings. There is no default secret in this code.
 */
interface SecretKdf {
    fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray
}

object Pbkdf2Kdf : SecretKdf {
    override fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec: KeySpec = PBEKeySpec(secret, salt, iterations, 256)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec as PBEKeySpec).encoded
        } finally {
            (spec as PBEKeySpec).clearPassword()
        }
    }
}

data class CredentialRecord(
    val saltB64: String,
    val hashB64: String,
    val iterations: Int,
    val failures: Int,
    val lockUntilEpochMs: Long,
)

sealed class AuthResult {
    data object Unconfigured : AuthResult()
    data object Accepted : AuthResult()
    data class Rejected(val failures: Int) : AuthResult()
    data class Locked(val retryAtEpochMs: Long) : AuthResult()
    data class InvalidSecret(val message: String) : AuthResult()
}

class AdminAuthenticator(
    private val store: StringStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kdf: SecretKdf = Pbkdf2Kdf,
    private val random: SecureRandom = SecureRandom(),
) {
    fun isConfigured(): Boolean = read() != null

    fun status(now: Long = clock()): AuthResult {
        val record = read() ?: return AuthResult.Unconfigured
        if (now < record.lockUntilEpochMs) return AuthResult.Locked(record.lockUntilEpochMs)
        return AuthResult.Rejected(record.failures)
    }

    fun setup(secret: CharArray): AuthResult {
        try {
            if (read() != null) return AuthResult.InvalidSecret("An administrator secret is already set.")
            validateSecret(secret)?.let { return AuthResult.InvalidSecret(it) }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val hash = kdf.derive(secret, salt, ITERATIONS)
            write(
                CredentialRecord(
                    saltB64 = Base64.getEncoder().encodeToString(salt),
                    hashB64 = Base64.getEncoder().encodeToString(hash),
                    iterations = ITERATIONS,
                    failures = 0,
                    lockUntilEpochMs = 0L,
                ),
            )
            return AuthResult.Accepted
        } finally {
            secret.fill('\u0000')
        }
    }

    fun verify(secret: CharArray, now: Long = clock()): AuthResult {
        try {
            val record = read() ?: return AuthResult.Unconfigured
            if (now < record.lockUntilEpochMs) return AuthResult.Locked(record.lockUntilEpochMs)
            val salt = Base64.getDecoder().decode(record.saltB64)
            val expected = Base64.getDecoder().decode(record.hashB64)
            val actual = kdf.derive(secret, salt, record.iterations)
            if (!MessageDigest.isEqual(actual, expected)) {
                val failures = record.failures + 1
                val lockFor = lockoutMs(failures)
                val updated = record.copy(
                    failures = failures,
                    lockUntilEpochMs = if (lockFor == 0L) 0L else now + lockFor,
                )
                write(updated)
                return if (lockFor == 0L) AuthResult.Rejected(failures) else AuthResult.Locked(updated.lockUntilEpochMs)
            }
            write(record.copy(failures = 0, lockUntilEpochMs = 0L))
            return AuthResult.Accepted
        } finally {
            secret.fill('\u0000')
        }
    }

    fun change(current: CharArray, next: CharArray): AuthResult {
        val verified = verify(current.copyOf())
        if (verified !is AuthResult.Accepted) {
            current.fill('\u0000')
            next.fill('\u0000')
            return verified
        }
        val problem = validateSecret(next)
        if (problem != null) {
            current.fill('\u0000')
            next.fill('\u0000')
            return AuthResult.InvalidSecret(problem)
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = kdf.derive(next, salt, ITERATIONS)
        current.fill('\u0000')
        next.fill('\u0000')
        write(
            CredentialRecord(
                saltB64 = Base64.getEncoder().encodeToString(salt),
                hashB64 = Base64.getEncoder().encodeToString(hash),
                iterations = ITERATIONS,
                failures = 0,
                lockUntilEpochMs = 0L,
            ),
        )
        return AuthResult.Accepted
    }

    private fun validateSecret(secret: CharArray): String? {
        if (secret.size < MIN_LENGTH || secret.size > MAX_LENGTH) {
            return "Use 10 to 128 characters. A short PIN is not accepted."
        }
        val text = String(secret)
        if (text.any { it.isISOControl() }) return "Control characters are not allowed."
        if (text.all { it == text[0] }) return "Do not use a repeated character."
        if (text in DENY) return "That secret is too common."
        if (text.all { it.isDigit() } && isSequentialDigits(text)) {
            return "Do not use a straight sequence of digits."
        }
        return null
    }

    private fun read(): CredentialRecord? {
        val raw = store.read()?.takeIf { it.isNotBlank() } ?: return null
        return CredentialCodec.decode(raw)
    }

    private fun write(record: CredentialRecord) {
        store.write(CredentialCodec.encode(record))
    }

    companion object {
        const val ITERATIONS = 210_000
        const val MIN_LENGTH = 10
        const val MAX_LENGTH = 128
        private const val SALT_BYTES = 16

        private val DENY = setOf(
            "password123",
            "passphrase1",
            "securebrowse",
            "letmeinplease",
            "administrator",
            "1234567890",
            "0123456789",
            "0987654321",
            "qwertyuiop",
            "changeme123",
            "adminadmin",
        )

        fun lockoutMs(failures: Int): Long = when {
            failures >= 8 -> 30L * 60L * 1000L
            failures == 7 -> 10L * 60L * 1000L
            failures == 6 -> 2L * 60L * 1000L
            failures == 5 -> 30L * 1000L
            else -> 0L
        }

        private fun isSequentialDigits(text: String): Boolean {
            val ascending = text.zipWithNext().all { (a, b) -> b == a + 1 }
            val descending = text.zipWithNext().all { (a, b) -> b == a - 1 }
            return ascending || descending
        }
    }
}

internal object CredentialCodec {
    fun encode(record: CredentialRecord): String {
        return JsonValues.stringify(
            linkedMapOf(
                "version" to 1,
                "kdf" to "pbkdf2-hmac-sha256",
                "iterations" to record.iterations,
                "salt" to record.saltB64,
                "verifier" to record.hashB64,
                "failures" to record.failures,
                "lockUntil" to record.lockUntilEpochMs,
            ),
        )
    }

    fun decode(json: String): CredentialRecord {
        val root = JsonValues.parse(json) as? Map<*, *>
            ?: throw PolicyFormatException("Credential record is unreadable.")
        val version = root["version"] as? Int ?: throw PolicyFormatException("Credential record is unreadable.")
        if (version != 1) throw PolicyFormatException("Credential record is unreadable.")
        if (root["kdf"] != "pbkdf2-hmac-sha256") throw PolicyFormatException("Credential record is unreadable.")
        return CredentialRecord(
            saltB64 = root["salt"] as? String ?: throw PolicyFormatException("Credential record is unreadable."),
            hashB64 = root["verifier"] as? String ?: throw PolicyFormatException("Credential record is unreadable."),
            iterations = root["iterations"] as? Int ?: throw PolicyFormatException("Credential record is unreadable."),
            failures = root["failures"] as? Int ?: 0,
            lockUntilEpochMs = longOf(root["lockUntil"]),
        )
    }

    private fun longOf(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw PolicyFormatException("Credential record is unreadable.")
    }
}
