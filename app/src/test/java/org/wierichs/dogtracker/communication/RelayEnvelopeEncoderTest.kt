package org.wierichs.dogtracker.communication

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEnvelopeEncoderTest {
    @Test
    fun envelopeCanBeVerifiedAndDecryptedByServerCompatibleAlgorithms() {
        val signingPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val encryptionPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val signing = Signature.getInstance("SHA256withRSA")
        val signer: (ByteArray) -> ByteArray = { input ->
            signing.initSign(signingPair.private)
            signing.update(input)
            signing.sign()
        }
        val serverPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(encryptionPair.public.encoded)
        val serverKey = RelayServerEncryptionKey("relay-1", serverPublicKey)
        val payload = """{"latitude":51.12,"longitude":6.98,"accuracy":8.0,"recorded_at":"2026-10-28T15:00:00.000Z"}"""
            .toByteArray(Charsets.UTF_8)
        val now = Instant.parse("2026-10-28T15:00:05.000Z").toEpochMilli()

        val envelope = RelayEnvelopeEncoder.create(
            originDeviceId = "a16cb2bc-6dca-40bb-8e10-fc33b4d9272e",
            locationPayload = payload,
            serverKey = serverKey,
            sign = signer,
            nowEpochMillis = now
        )

        val encryptedAesKey = Base64.getUrlDecoder().decode(envelope.encryptedKey)
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsaCipher.init(
            Cipher.DECRYPT_MODE,
            encryptionPair.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        val aesKey = rsaCipher.doFinal(encryptedAesKey)
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(
            Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, Base64.getUrlDecoder().decode(envelope.nonce))
        )
        val decryptedPayload = aesCipher.doFinal(Base64.getUrlDecoder().decode(envelope.ciphertext))

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(signingPair.public)
        verifier.update(
            RelayEnvelopeEncoder.signingInput(
                envelope.originDeviceId,
                envelope.messageId,
                envelope.expiresAt,
                envelope.keyId,
                envelope.encryptedKey,
                envelope.nonce,
                envelope.ciphertext
            )
        )

        assertArrayEquals(payload, decryptedPayload)
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(envelope.signature)))
        assertTrue(envelope.expiresAt.endsWith(".000Z"))
    }
}