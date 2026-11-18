package org.wierichs.dogtracker.communication

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflinePositionMessageTest {
    private val profile = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
    private val localDevice = "a16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
    private val senderDevice = "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
    private val subject = "d76274b7-e21a-440e-ad0d-c51c6f6345e0"
    private val now = Instant.parse("2026-11-18T07:00:00Z").toEpochMilli()
    private val serverPair = rsaKeyPair()
    private val senderPair = rsaKeyPair()
    private val localPair = rsaKeyPair()
    private val capabilities = RelayCapabilities(
        RelayServerEncryptionKey("encryption", publicKey(serverPair)),
        "receipt",
        publicKey(serverPair)
    )

    private fun directory(profileId: String = profile, senderKind: String = "participant",
        senderSubjectId: String? = subject): VerifiedRelayDirectory {
        val body = JSONObject().put("document_type", "offline_directory").put("profile_id", profileId)
            .put("device_id", localDevice).put("issued_at", Instant.ofEpochMilli(now).toString())
            .put("expires_at", Instant.ofEpochMilli(now + 86_400_000).toString())
            .put("devices", JSONArray()
                .put(JSONObject().put("device_id", localDevice).put("device_kind", "participant")
                    .put("public_key", publicKey(localPair)))
                .put(JSONObject().put("device_id", senderDevice).put("device_kind", senderKind)
                    .put("public_key", publicKey(senderPair)).put("subject_id", senderSubjectId)))
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toString().toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(serverPair.private)
            update("location-relay-document-v1\nreceipt\n$encoded".toByteArray(Charsets.UTF_8))
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        val document = JSONObject().put("action", "relay_document").put("key_id", "receipt")
            .put("document", encoded).put("signature", signature)
        return VerifiedRelayDirectory.verifyOffline(document, capabilities, localDevice, profile, now)
    }

    private fun signedMessage(
        profileId: String = profile,
        sender: String = senderDevice,
        subjectType: String = "participant",
        recordedAt: Long = now
    ) = OfflinePositionMessage.create(
        profileId, sender, subjectType, subject, 7, recordedAt, 51.0, 7.0, 5.0,
        sign = { bytes -> Signature.getInstance("SHA256withRSA").run {
            initSign(senderPair.private)
            update(bytes)
            sign()
        } }
    )

    @Test
    fun signedPositionRoundTripsAndVerifiesForAnAuthorizedPeer() {
        val directory = directory()
        val message = signedMessage()
        val parsed = OfflinePositionMessage.parse(message.toByteArray(), now)

        assertNotNull(parsed)
        assertEquals(message, parsed)
        assertTrue(requireNotNull(parsed).verify(directory, now))
    }

    @Test
    fun signedPositionIsAcceptedOnlyFromItsOwnAuthenticatedPeer() {
        val directory = directory()
        val position = signedMessage()
        assertEquals(position, OfflinePositionMessage.fromPeer(position.toByteArray(), senderDevice, directory, now))
        assertNull(OfflinePositionMessage.fromPeer(position.toByteArray(), localDevice, directory, now))
        assertNull(OfflinePositionMessage.fromPeer(position.copy(latitude = 52.0).toByteArray(), senderDevice, directory, now))
    }

    @Test
    fun messageMustMatchTheSignedProfileAndRegisteredDeviceKind() {
        assertFalse(signedMessage(profileId = "d76274b7-e21a-440e-ad0d-c51c6f6345e0")
            .verify(directory(), now))
        assertFalse(signedMessage(subjectType = "dog").verify(directory(), now))
        assertFalse(signedMessage().verify(directory(senderKind = "dog"), now))
        assertTrue(signedMessage(subjectType = "dog").verify(directory(senderKind = "dog"), now))
        assertFalse(signedMessage().verify(directory(senderSubjectId = localDevice), now))
        assertFalse(signedMessage().verify(directory(senderSubjectId = null), now))
    }

    @Test
    fun signatureCoversPositionAndOldButNotFuturePositionsCanBeParsed() {
        val message = signedMessage()
        assertFalse(message.copy(latitude = 52.0).verify(directory(), now))
        assertFalse(message.copy(signature = "not-a-signature").verify(directory(), now))
        assertFalse(message.copy(sequence = 0).verify(directory(), now))

        val old = signedMessage(recordedAt = now - 10 * 60_000)
        assertNotNull(OfflinePositionMessage.parse(old.toByteArray(), now))
        assertNull(OfflinePositionMessage.parse(
            signedMessage(recordedAt = now + 30_001).toByteArray(), now
        ))
    }

    @Test
    fun parserRejectsOutOfRangeCoordinatesAndMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            OfflinePositionMessage.create(profile, senderDevice, "participant", subject, 1, now,
                91.0, 7.0, 5.0) { byteArrayOf(1) }
        }
        assertNull(OfflinePositionMessage.parse("not json".toByteArray(), now))
        assertNull(OfflinePositionMessage.parse(ByteArray(8193), now))
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun publicKey(pair: KeyPair): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
}
