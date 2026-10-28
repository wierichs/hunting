package org.wierichs.dogtracker.communication

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelaySigningIdentityTest {
    @Test
    fun androidKeystoreKeyExportsAndSignsWithExpectedAlgorithm() {
        val payload = "relay identity smoke test".toByteArray(Charsets.UTF_8)
        val encodedPublicKey = Base64.getUrlDecoder().decode(RelaySigningIdentity.publicKeyBase64Url())
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encodedPublicKey))
        val signatureBytes = RelaySigningIdentity.sign(payload)
        val verifier = Signature.getInstance("SHA256withRSA")

        verifier.initVerify(publicKey)
        verifier.update(payload)

        assertTrue(verifier.verify(signatureBytes))
    }

    @Test
    fun parserAcceptsFreshRelayEnvelopeAndRejectsExpiredEnvelope() {
        val signingPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val encryptionPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val signer: (ByteArray) -> ByteArray = { input ->
            Signature.getInstance("SHA256withRSA").run {
                initSign(signingPair.private)
                update(input)
                sign()
            }
        }
        val now = Instant.parse("2026-10-28T15:00:05.000Z").toEpochMilli()
        val envelope = RelayEnvelopeEncoder.create(
            originDeviceId = "a16cb2bc-6dca-40bb-8e10-fc33b4d9272e",
            locationPayload = "{}".toByteArray(Charsets.UTF_8),
            serverKey = RelayServerEncryptionKey(
                "relay-1",
                Base64.getUrlEncoder().withoutPadding().encodeToString(encryptionPair.public.encoded)
            ),
            sign = signer,
            nowEpochMillis = now
        )

        assertEquals(envelope.messageId, RelayEnvelope.parse(envelope.toByteArray(), now)?.messageId)
        assertNull(RelayEnvelope.parse(envelope.toByteArray(), envelope.expiresAtEpochMillis + 1))
    }
}