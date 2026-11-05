package ru.partitrainer.data

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
 * API-ключ хранится только на устройстве и только в зашифрованном виде:
 * AES-256-GCM, ключ шифрования лежит в Android Keystore и не покидает его.
 * Значение никогда не попадает в логи.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
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

    fun hasKey(): Boolean = prefs.contains(PREF)

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + enc
        prefs.edit().putString(PREF, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(): String? {
        val s = prefs.getString(PREF, null) ?: return null
        return try {
            val all = Base64.decode(s, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            // Ключ Keystore утрачен (например, после восстановления) — считаем, что ключа нет.
            prefs.edit().remove(PREF).apply()
            null
        }
    }

    fun delete() { prefs.edit().remove(PREF).apply() }

    private companion object {
        const val ALIAS = "parti_api_key_v1"
        const val PREF = "anthropic_key_enc"
    }
}
