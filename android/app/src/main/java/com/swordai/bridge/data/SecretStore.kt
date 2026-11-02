package com.swordai.bridge.data

import android.content.Context
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
 * Хранилище секретов: значения шифруются AES-256-GCM ключом из Android Keystore.
 * Сам ключ не покидает защищённое хранилище устройства.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, packed).commit()
    }

    @Synchronized
    fun get(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        return try {
            val (ivB64, dataB64) = packed.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Ключ мог быть утерян (например, после восстановления системы) — требуется повторное сопряжение.
            Log.w("SecretStore", "Cannot decrypt $name: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "swordai_bridge_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
