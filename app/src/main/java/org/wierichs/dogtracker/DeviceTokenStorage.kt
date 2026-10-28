package org.wierichs.dogtracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object DeviceTokenStorage {
    private const val KEY_ALIAS = "dog_tracker_device_token_key"
    private const val FILE_NAME = "device_token"
    private const val KEY_IV = "iv"
    private const val KEY_CIPHERTEXT = "ciphertext"
    private const val GCM_TAG_SIZE_BITS = 128

    fun read(context: Context): String? = runCatching {
        val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_SIZE_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun write(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}