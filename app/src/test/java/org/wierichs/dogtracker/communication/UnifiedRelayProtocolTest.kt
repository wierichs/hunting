package org.wierichs.dogtracker.communication

import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedRelayProtocolTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
    private val capabilities = RelayCapabilities(RelayServerEncryptionKey("encryption", publicKey), "receipt", publicKey)
    private val profile = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
    private val device = "a16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
    private val now = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli()

    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(pair.private); update(bytes); sign()
    }

    private fun document(body: JSONObject): JSONObject {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray())
        return JSONObject().put("action", "relay_document").put("key_id", "receipt").put("document", encoded)
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(sign("location-relay-document-v1\nreceipt\n$encoded".toByteArray())))
    }

    private fun directory(): JSONObject = document(JSONObject().put("document_type", "directory").put("profile_id", profile)
        .put("device_id", device).put("issued_at", Instant.ofEpochMilli(now).toString())
        .put("expires_at", Instant.ofEpochMilli(now + 120_000).toString())
        .put("devices", JSONArray().put(JSONObject().put("device_id", device).put("device_kind", "participant").put("public_key", publicKey))))

    private fun envelope(): RelayEnvelope = RelayEnvelopeEncoder.create(device,
        participantLocationPayload(profile, 1, 51.0, 7.0, 5f, now, device), capabilities.encryption, ::sign,
        nowEpochMillis = now, lifetimeMillis = 90_000)

    @Test fun directoryAuthenticatesOnlyListedUnexpiredOrigins() {
        val directory = VerifiedRelayDirectory.verify(directory(), capabilities, profile, device, now)
        val envelope = envelope()
        assertTrue(directory.accepts(envelope, now))
        assertFalse(directory.accepts(envelope.copy(originDeviceId = "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e"), now))
        assertFalse(directory.accepts(envelope.copy(ciphertext = envelope.ciphertext.reversed()), now))
        assertFalse(directory.current(now + 120_000))
    }

    @Test fun forgedOrWrongGroupDirectoryFailsClosed() {
        val json = directory()
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedRelayDirectory.verify(json, capabilities, "b76274b7-e21a-440e-ad0d-c51c6f6345e0", device, now)
        }
        json.put("document", Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { VerifiedRelayDirectory.verify(json, capabilities, profile, device, now) }
    }

    @Test fun receiptsBindOriginMessageAndExactEncryptedContent() {
        val envelope = envelope()
        val json = document(JSONObject().put("document_type", "receipt").put("profile_id", profile)
            .put("origin_device_id", device).put("message_id", envelope.messageId).put("envelope_digest", relayEnvelopeDigest(envelope))
            .put("received_at", Instant.ofEpochMilli(now).toString()).put("expires_at", Instant.ofEpochMilli(now + 86_400_000).toString()))
        val receipt = VerifiedRelayReceipt.verify(json, capabilities, profile, now)
        assertTrue(receipt.matches(envelope))
        assertFalse(receipt.matches(envelope.copy(nonce = envelope.nonce.reversed())))
        assertThrows(IllegalArgumentException::class.java) { VerifiedRelayReceipt.verify(json, capabilities, profile, now + 86_400_000) }
    }

    @Test fun participantPayloadHasTypedGroupBoundSequencedCoordinates() {
        val json = JSONObject(String(participantLocationPayload(profile, 12, 51.2, 7.1, 4f, now, device)))
        assertEquals("participant.location", json.getString("message_type"))
        assertEquals("12", json.getString("sequence"))
        assertEquals(profile, json.getString("profile_id"))
        assertEquals(device, json.getString("sharing_epoch"))
        assertEquals("2026-11-02T10:00:00.000Z", json.getString("recorded_at"))
        assertThrows(IllegalArgumentException::class.java) { participantLocationPayload(profile, 0, 51.0, 7.0, 5f, now, device) }
        assertThrows(IllegalArgumentException::class.java) { participantLocationPayload(profile, 1, Double.NaN, 7.0, 5f, now, device) }
        assertFalse(json.has("access_token"))
        assertFalse(json.has("device_token"))
    }

    @Test fun localDeduplificationIsScopedToTheOriginDevice() {
        val message = "b6f0ca6c-cc23-44df-b231-51a7a29ad002"
        assertNotEquals(relayStorageId(device, message), relayStorageId("b16cb2bc-6dca-40bb-8e10-fc33b4d9272e", message))
    }

    @Test fun nearbyPermissionsMatchAndroidVersionBoundaries() {
        assertTrue(RelayGatewayService.requiredRelayPermissions(32).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(RelayGatewayService.requiredRelayPermissions(32).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(33).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(RelayGatewayService.requiredRelayPermissions(33).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(37).contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }
}