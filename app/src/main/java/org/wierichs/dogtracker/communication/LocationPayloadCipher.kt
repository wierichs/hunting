package org.wierichs.dogtracker.communication

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val LOCATION_PAYLOAD_KEY_ALIAS = "dog_tracker_location_payload_key"

internal class LocationPayloadCryptoException(cause: Throwable) :
    Exception("GPS-Position konnte nicht sicher verschluesselt oder gelesen werden.", cause)

internal class UnreadableLocationPayloadException(
    val messageId: String,
    cause: Throwable? = null
) : Exception("Eine gespeicherte GPS-Position kann nicht entschluesselt werden.", cause)

internal object LocationPayloadCipher {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private val formatPrefix = byteArrayOf(0x44, 0x54, 0x4c, 0x50, 0x01)

    fun encrypt(messageId: String, plaintext: ByteArray): ByteArray =
        encrypt(messageId, plaintext, encryptionKey(LOCATION_PAYLOAD_KEY_ALIAS))

    fun encrypt(messageId: String, plaintext: ByteArray, key: SecretKey): ByteArray = try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        if (iv.size != IV_SIZE_BYTES) {
            throw GeneralSecurityException("Ungueltige IV-Laenge.")
        }
        cipher.updateAAD(messageId.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        ByteBuffer.allocate(formatPrefix.size + iv.size + ciphertext.size)
            .put(formatPrefix)
            .put(iv)
            .put(ciphertext)
            .array()
    } catch (error: GeneralSecurityException) {
        throw LocationPayloadCryptoException(error)
    }

    fun decrypt(messageId: String, encrypted: ByteArray): ByteArray =
        decrypt(messageId, encrypted, LOCATION_PAYLOAD_KEY_ALIAS)

    fun decrypt(messageId: String, encrypted: ByteArray, keyAlias: String): ByteArray {
        if (!isEncrypted(encrypted) || encrypted.size < formatPrefix.size + IV_SIZE_BYTES + TAG_SIZE_BITS / 8) {
            throw UnreadableLocationPayloadException(messageId)
        }

        val key = try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            keyStore.getKey(keyAlias, null) as? SecretKey
        } catch (error: UnrecoverableKeyException) {
            throw UnreadableLocationPayloadException(messageId, error)
        } catch (error: GeneralSecurityException) {
            throw LocationPayloadCryptoException(error)
        } catch (error: IOException) {
            throw LocationPayloadCryptoException(error)
        } ?: throw UnreadableLocationPayloadException(messageId)

        val ivStart = formatPrefix.size
        val iv = encrypted.copyOfRange(ivStart, ivStart + IV_SIZE_BYTES)
        val ciphertext = encrypted.copyOfRange(ivStart + IV_SIZE_BYTES, encrypted.size)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.updateAAD(messageId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw UnreadableLocationPayloadException(messageId, error)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw UnreadableLocationPayloadException(messageId, error)
        } catch (error: InvalidKeyException) {
            throw UnreadableLocationPayloadException(messageId, error)
        } catch (error: GeneralSecurityException) {
            throw LocationPayloadCryptoException(error)
        }
    }

    fun isEncrypted(payload: ByteArray): Boolean =
        payload.size >= formatPrefix.size &&
            formatPrefix.indices.all { payload[it] == formatPrefix[it] }

    fun encryptionKey(): SecretKey = encryptionKey(LOCATION_PAYLOAD_KEY_ALIAS)

    @Synchronized
    fun encryptionKey(keyAlias: String): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existingKey = try {
                keyStore.getKey(keyAlias, null) as? SecretKey
            } catch (_: UnrecoverableKeyException) {
                keyStore.deleteEntry(keyAlias)
                null
            }
            if (existingKey != null) {
                try {
                    Cipher.getInstance(CIPHER_TRANSFORMATION).init(Cipher.ENCRYPT_MODE, existingKey)
                    return existingKey
                } catch (_: KeyPermanentlyInvalidatedException) {
                    keyStore.deleteEntry(keyAlias)
                }
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        } catch (error: GeneralSecurityException) {
            throw LocationPayloadCryptoException(error)
        } catch (error: IOException) {
            throw LocationPayloadCryptoException(error)
        }
    }
}
