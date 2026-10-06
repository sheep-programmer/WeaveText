package com.weavetext.ime.plugins

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from plugin config and personal-profile sync. Only encrypted credentials are persisted. */
class GitHubCredentials(ctx: Context, private val keyProvider: () -> SecretKey = { key() }) {
    private val prefs = ctx.getSharedPreferences("github_credentials", Context.MODE_PRIVATE)
    val epoch get() = revision.get()
    val login get() = prefs.getString("login", null)

    @Synchronized fun token(): String? {
        val encrypted = prefs.getString("token", null) ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(encrypted)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD("weavetext-github-v1".toByteArray())
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized fun save(login: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD("weavetext-github-v1".toByteArray())
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()))
        check(prefs.edit().putString("login", login).putString("token", encoded).commit()) { "无法保存 GitHub 授权" }
        revision.incrementAndGet()
    }

    @Synchronized fun clear() { check(prefs.edit().clear().commit()) { "无法清除 GitHub 授权" }; revision.incrementAndGet() }

    companion object {
        private val revision = AtomicLong()
        private const val ALIAS = "weavetext.github.credentials.v1"
        @Synchronized private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
