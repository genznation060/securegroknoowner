package com.securebrowse.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.securebrowse.policy.StringStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM ciphertext in no-backup app storage. The key never leaves the
 * Android Keystore. This is not a substitute for device encryption policy,
 * and a person who can delete the app's files can delete the ciphertext.
 */
class KeystoreStringStore(
    context: Context,
    private val fileName: String,
) : StringStore {
    private val file = File(context.noBackupFilesDir, fileName)
    private val alias = "securebrowse.$fileName"

    override fun read(): String? {
        if (!file.exists() || file.length() == 0L) return null
        return decrypt(file.readBytes()).toString(Charsets.UTF_8)
    }

    override fun write(value: String) {
        val blob = encrypt(value.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, "$fileName.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            file.writeBytes(blob)
            tmp.delete()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == 12)
        return MAGIC + iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < MAGIC.size + 12 + 16 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalStateException("Secure storage header is not valid.")
        }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val body = blob.copyOfRange(MAGIC.size + 12, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val MAGIC = byteArrayOf(0x53, 0x42, 0x31, 0x01)
    }
}
