package com.mybm.meshhome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local storage only: saving a token does not mean Nord has authenticated it. */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE)
    val hasToken: Boolean get() = prefs.contains("ciphertext")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setKeySize(256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(token: String) {
        require(token.isNotBlank()) { "Enter a token." }
        saveSecret("token", token.trim())
    }

    fun saveSecret(name: String, value: String): Unit = synchronized(vaultLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val prefix = if (name == "token") "" else "$name."
        check(prefs.edit().putString("${prefix}ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${prefix}iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Could not save credentials." }
    }

    fun read(): String? = readSecret("token")

    fun readSecret(name: String): String? = synchronized(vaultLock) {
        val prefix = if (name == "token") "" else "$name."
        val encrypted = prefs.getString("${prefix}ciphertext", null) ?: return@synchronized null
        val iv = checkNotNull(prefs.getString("${prefix}iv", null))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun clear(): Unit = synchronized(vaultLock) {
        check(prefs.edit().clear().commit()) { "Could not clear credentials." }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
        Unit
    }

    companion object {
        private const val ALIAS = "meshhome.nord.token.v1"
        private val vaultLock = Any()
    }
}
