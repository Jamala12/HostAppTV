package org.hostapptv.player.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.hostapptv.player.data.xtream.ProviderConfig
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remembered provider access, encrypted with an AES-GCM key held in Android Keystore.
 * The plaintext never touches disk, logs, navigation state or backups (backup is disabled).
 * When "remember" is off the account lives only in memory for this process.
 */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    @Volatile private var sessionOnly: ProviderConfig? = null

    fun load(): ProviderConfig? {
        sessionOnly?.let { return it }
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching { decode(String(decrypt(blob), Charsets.UTF_8)) }.getOrNull()
    }

    fun save(config: ProviderConfig, remember: Boolean) {
        if (remember) {
            sessionOnly = null
            prefs.edit().putString(KEY_BLOB, encrypt(encode(config).toByteArray(Charsets.UTF_8))).apply()
        } else {
            sessionOnly = config
            prefs.edit().remove(KEY_BLOB).apply()
        }
    }

    fun clear() {
        sessionOnly = null
        prefs.edit().remove(KEY_BLOB).apply()
    }

    fun discoveryToken(): String? = prefs.getString("discovery_blob", null)?.let { blob ->
        runCatching { String(decrypt(blob), Charsets.UTF_8) }.getOrNull()
    }

    fun saveDiscoveryToken(token: String) {
        require(Regex("[A-Za-z0-9_.-]{20,4096}").matches(token)) { "Enter the TMDB API Read Access Token, without Bearer." }
        prefs.edit().putString("discovery_blob", encrypt(token.toByteArray(Charsets.UTF_8))).apply()
    }

    fun clearDiscoveryToken() { prefs.edit().remove("discovery_blob").apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): ByteArray {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return cipher.doFinal(all, 12, all.size - 12)
    }

    companion object {
        private const val ALIAS = "provider-account-v1"
        private const val KEY_BLOB = "blob"

        internal fun encode(c: ProviderConfig): String =
            listOf(c.baseUrl, c.username, c.password, if (c.allowCleartext) "1" else "0")
                .joinToString("\n") { it.replace("\\", "\\\\").replace("\n", "\\n") }

        internal fun decode(s: String): ProviderConfig {
            val parts = s.split("\n").map { it.replace("\\n", "\n").replace("\\\\", "\\") }
            return ProviderConfig(parts[0], parts[1], parts[2], parts.getOrNull(3) == "1")
        }

        /** Non-secret namespace for all local data belonging to one provider account. */
        fun accountId(c: ProviderConfig): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${c.baseUrl.lowercase().trimEnd('/')}|${c.username}".toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
