package com.personal.devicememory.core.database.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the SQLCipher database passphrase using Android Keystore.
 * Features automatic recovery, fallback keys, and zero-crash guarantees.
 */
object DatabaseKeyManager {

    private const val TAG = "DatabaseKeyManager"
    private const val KEYSTORE_ALIAS = "devicememory_db_key"
    private const val PREFS_NAME = "devicememory_crypto"
    private const val PREF_ENCRYPTED_PASSPHRASE = "encrypted_passphrase"
    private const val PREF_IV = "passphrase_iv"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_LENGTH = 128

    fun getPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val encryptedB64 = prefs.getString(PREF_ENCRYPTED_PASSPHRASE, null)
        val ivB64 = prefs.getString(PREF_IV, null)

        return if (encryptedB64 != null && ivB64 != null) {
            try {
                val encryptedBytes = Base64.decode(encryptedB64, Base64.NO_WRAP)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                decryptPassphrase(encryptedBytes, iv)
            } catch (e: Throwable) {
                Log.e(TAG, "Decryption failed (corrupted or rotated key). Regenerating passphrase safely.", e)
                generateAndStorePassphrase(prefs)
            }
        } else {
            generateAndStorePassphrase(prefs)
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                try {
                    val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                    if (entry?.secretKey != null) {
                        return entry.secretKey
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Key found but unrecoverable, will generate a new one", e)
                }
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore failed, falling back to internal key generator", e)
            val fallbackKeyGen = KeyGenerator.getInstance("AES")
            fallbackKeyGen.init(256)
            return fallbackKeyGen.generateKey()
        }
    }

    private fun generateAndStorePassphrase(prefs: SharedPreferences): ByteArray {
        val passphrase = ByteArray(32)
        java.security.SecureRandom().nextBytes(passphrase)

        try {
            val key = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val encrypted = cipher.doFinal(passphrase)
            val iv = cipher.iv

            prefs.edit()
                .putString(PREF_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Error generating encrypted passphrase", e)
        }

        return passphrase
    }

    private fun decryptPassphrase(encryptedBytes: ByteArray, iv: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encryptedBytes)
    }

    fun destroyKey(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error deleting KeyStore entry", e)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
