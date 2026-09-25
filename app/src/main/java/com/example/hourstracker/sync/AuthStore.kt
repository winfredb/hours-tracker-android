package com.example.hourstracker.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Securely stores the worker's PocketBase auth token (and who they are) so it
 * survives restarts but is never written in plaintext. The encryption key is
 * generated inside the Android Keystore (hardware-backed where available) and
 * never leaves it; only the AES-GCM ciphertext is persisted in a normal
 * SharedPreferences file.
 *
 * Uses AES/GCM (authenticated, random IV per encryption) — the standard
 * secure primitive for at-rest app secrets.
 */
class AuthStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Ciphertext (base64) of the auth token, plus its IV. */
    private var ciphertext: String?
        get() = prefs.getString(KEY_CIPHERTEXT, null)
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                e.remove(KEY_CIPHERTEXT).remove(KEY_IV).remove(KEY_EMAIL).remove(KEY_NAME).remove(KEY_USER_ID)
            } else {
                e.putString(KEY_CIPHERTEXT, value)
            }
            e.apply()
        }

    private var iv: String?
        get() = prefs.getString(KEY_IV, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_IV, value).apply()
            else prefs.edit().remove(KEY_IV).apply()
        }

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_EMAIL, value).apply()
            else prefs.edit().remove(KEY_EMAIL).apply()
        }

    var name: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_NAME, value).apply()
            else prefs.edit().remove(KEY_NAME).apply()
        }

    /** The PocketBase user record id — set on time_entries as the `user` field. */
    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) {
            if (value != null) prefs.edit().putString(KEY_USER_ID, value).apply()
            else prefs.edit().remove(KEY_USER_ID).apply()
        }

    /** True when a token is stored (even if it later turns out expired). */
    val hasToken: Boolean get() = ciphertext != null

    /** The decrypted token, or null when not stored / cannot decrypt. */
    val token: String?
        get() {
            val ct = ciphertext ?: return null
            val ivB64 = iv ?: return null
            return try {
                decrypt(Base64.decode(ct, Base64.NO_WRAP), Base64.decode(ivB64, Base64.NO_WRAP))
            } catch (e: Exception) {
                null // corrupt or key rotated away; treat as logged out
            }
        }

    /** Store a fresh plaintext token. */
    fun saveToken(token: String) {
        val (ct, ivBytes) = encrypt(token)
        ciphertext = Base64.encodeToString(ct, Base64.NO_WRAP)
        iv = Base64.encodeToString(ivBytes, Base64.NO_WRAP)
    }

    /** Wipe the token + identity (logout). */
    fun clear() {
        ciphertext = null
        iv = null
        email = null
        name = null
        userId = null
    }

    // --- crypto -------------------------------------------------------------

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ct to cipher.iv
    }

    private fun decrypt(ct: ByteArray, ivBytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, ivBytes))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS_NAME = "hr_sync_auth"
        const val KEY_ALIAS = "hr_worker_auth"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_CIPHERTEXT = "token_ciphertext"
        const val KEY_IV = "token_iv"
        const val KEY_EMAIL = "token_email"
        const val KEY_NAME = "token_name"
        const val KEY_USER_ID = "token_user_id"
    }
}