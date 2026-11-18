package org.wierichs.dogtracker.communication

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

internal object RelaySigningIdentity {
    private const val KEY_ALIAS = "dog_tracker_relay_signing_key"
    private const val KEY_SIZE_BITS = 3072

    fun publicKeyBase64Url(): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(keyPair().public.encoded)

    fun sign(message: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair().private)
        signature.update(message)
        return signature.sign()
    }

    private fun keyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        if (privateKey != null && publicKey != null) return KeyPair(publicKey, privateKey)

        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(KEY_SIZE_BITS)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
        )
        generator.generateKeyPair()

        val createdPrivateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("Android Keystore did not retain the relay private key")
        val createdPublicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: error("Android Keystore did not retain the relay public key")
        return KeyPair(createdPublicKey, createdPrivateKey)
    }
}