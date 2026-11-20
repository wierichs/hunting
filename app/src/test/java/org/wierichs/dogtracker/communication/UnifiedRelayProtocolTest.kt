package org.wierichs.dogtracker.communication

import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.wierichs.dogtracker.communication.RelayPeerAuthentication.RelayPeerSession

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

    @Test fun huntEndRequiresServerSignatureAndExactSessionAndCannotBeRenewed() {
        val nowInstant = Instant.ofEpochMilli(now)
        val signed = document(JSONObject().put("document_type", "hunt_end").put("profile_id", profile)
            .put("session_id", device).put("ended_at", nowInstant.toString())
            .put("expires_at", nowInstant.plusSeconds(86_400).toString()))
        val bytes = signed.toString().toByteArray()
        val verified = VerifiedHuntingEnd.verify(bytes, capabilities, profile, device, nowInstant)
        assertArrayEquals(bytes, verified.toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedHuntingEnd.verify(bytes, capabilities, profile, profile, nowInstant)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedHuntingEnd.verify(bytes, capabilities, device, device, nowInstant)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedHuntingEnd.verify(bytes, capabilities, profile, device, nowInstant.plusSeconds(86_400))
        }
        val signature = signed.getString("signature")
        val corrupted = (if (signature[0] == 'A') "B" else "A") + signature.drop(1)
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedHuntingEnd.verify(signed.put("signature", corrupted).toString().toByteArray(),
                capabilities, profile, device, nowInstant)
        }
    }

    @Test fun broadcastPermissionRequiresExplicitSignedParticipantGrant() {
        val dog = "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
        val guest = "c16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
        fun entry(id: String, kind: String) = JSONObject().put("device_id", id)
            .put("device_kind", kind).put("public_key", publicKey).put("subject_id", id)
        val devices = JSONArray().put(entry(device, "participant").put("can_broadcast", true))
            .put(entry(dog, "dog").put("can_broadcast", true))
            .put(entry(guest, "participant"))
        val signed = document(JSONObject().put("document_type", "offline_directory").put("profile_id", profile)
            .put("device_id", device).put("session_id", guest).put("issued_at", Instant.ofEpochMilli(now).toString())
            .put("expires_at", Instant.ofEpochMilli(now + 120_000).toString()).put("devices", devices))
        val directory = VerifiedRelayDirectory.verifyOffline(signed, capabilities, device, profile, now)
        assertEquals(guest, directory.huntingSessionId)
        assertTrue(directory.canBroadcast(device))
        assertFalse(directory.canBroadcast(dog))
        assertFalse(directory.canBroadcast(guest))
        assertEquals(setOf(device, guest), directory.participantSubjects())
    }

    @Test fun broadcastIsScopedToItsRevierAndDuplicatesAreNotShownAgain() {
        val mailbox = NearbyBroadcasts()
        val message = NearbyBroadcast(device, BroadcastTemplate.ASSEMBLY, device, now)
        val payload = JSONObject(String(broadcastPayload(profile, message), Charsets.UTF_8))
        assertNull(parseNearbyBroadcast(payload, device, device, now))
        assertNull(parseNearbyBroadcast(payload, profile, device, now + BROADCAST_MAX_AGE_MS + 1))
        val parsed = requireNotNull(parseNearbyBroadcast(payload, profile, device, now))
        assertTrue(mailbox.receive(device, parsed, now))
        assertFalse(mailbox.receive(device, parsed, now))
        assertEquals(listOf(message), mailbox.messages.value)
    }

    @Test fun broadcastReceiptCountsOnlyForInitiallyTargetedDevices() {
        val mailbox = NearbyBroadcasts()
        val message = NearbyBroadcast(device, BroadcastTemplate.ASSEMBLY, "owner", now,
            recipients = setOf("guest", "disconnected"), connected = setOf("guest"), outgoing = true)
        mailbox.sent(message, mapOf("target" to "guest"))
        mailbox.confirm(message.id, "new-device", "guest")
        mailbox.confirm(message.id, "target", "disconnected")
        mailbox.confirm("other-message", "target", "guest")
        assertTrue(mailbox.messages.value.single().confirmed.isEmpty())
        mailbox.confirm(message.id, "target", "guest")
        mailbox.confirm(message.id, "target", "guest")
        assertEquals(setOf("guest"), mailbox.messages.value.single().confirmed)
    }

    @Test fun onlyTheDogRegistrantCanSignalThePairedDevice() {
        val dogDevice = "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
        val dog = "c16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
        val admin = "d16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
        fun entry(id: String, kind: String, subject: String) = JSONObject().put("device_id", id)
            .put("device_kind", kind).put("public_key", publicKey).put("subject_id", subject)
        val dogEntry = entry(dogDevice, "dog", dog).put("dog_registrant_id", device)
        val devices = JSONArray().put(entry(device, "participant", device))
            .put(entry(admin, "participant", admin).put("can_broadcast", true)).put(dogEntry)
        fun directory(): VerifiedRelayDirectory {
            val signed = document(JSONObject().put("document_type", "offline_directory").put("profile_id", profile)
                .put("device_id", dogDevice).put("issued_at", Instant.ofEpochMilli(now).toString())
                .put("expires_at", Instant.ofEpochMilli(now + 120_000).toString()).put("devices", devices))
            return VerifiedRelayDirectory.verifyOffline(signed, capabilities, dogDevice, profile, now)
        }
        assertTrue(directory().canSignalDog(device, dogDevice, dog))
        assertFalse(directory().canSignalDog(admin, dogDevice, dog))
        assertFalse(directory().canSignalDog(device, dogDevice, profile))
        dogEntry.remove("dog_registrant_id")
        assertFalse(directory().canSignalDog(device, dogDevice, dog))
    }

    @Test fun dogSignalsRejectExpiredPacketsAndUnknownActions() {
        val packet = DogSignalPacket.create(device, DogSignal.VIBRATE, now)
        val payload = JSONObject(String(packet.encode(profile), Charsets.UTF_8))
        assertEquals(packet, DogSignalPacket.parse(payload, now + 10_000))
        assertNull(DogSignalPacket.parse(payload, now + 10_001))
        assertNull(DogSignalPacket.parse(payload.put("signal", "unknown"), now))
    }

    @Test fun offlineDirectoryAllowsOnlySignedPeersOnTheSameNearbyChannel() {
        val issued = System.currentTimeMillis()
        val peer = "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e"
        val peerPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val peerPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(peerPair.public.encoded)
        val signPeer: (ByteArray) -> ByteArray = { input ->
            Signature.getInstance("SHA256withRSA").run { initSign(peerPair.private); update(input); sign() }
        }
        val devices = JSONArray()
            .put(JSONObject().put("device_id", device).put("device_kind", "dog").put("public_key", publicKey))
            .put(JSONObject().put("device_id", peer).put("device_kind", "participant").put("public_key", peerPublicKey))
        val body = JSONObject().put("document_type", "offline_directory").put("profile_id", profile)
            .put("device_id", device).put("issued_at", Instant.ofEpochMilli(issued).toString())
            .put("expires_at", Instant.ofEpochMilli(issued + 86_400_000).toString()).put("devices", devices)
        val signed = document(body)
        val directory = VerifiedRelayDirectory.verifyOffline(signed, capabilities, device, profile, issued)
        val token = ByteArray(32) { it.toByte() }
        val hello = RelayPeerAuthentication.create("hello", peer, "", token, signPeer)
        val ready = RelayPeerAuthentication.create("ready", peer, device, token, signPeer)

        assertEquals(peer, RelayPeerAuthentication.verify(hello, "hello", device, token, directory))
        assertEquals(peer, RelayPeerAuthentication.verify(ready, "ready", device, token, directory))
        assertNull(RelayPeerAuthentication.verify(hello, "hello", device, ByteArray(32), directory))
        assertNull(RelayPeerAuthentication.verify(ready, "ready", "c16cb2bc-6dca-40bb-8e10-fc33b4d9272e", token, directory))
        assertNull(RelayPeerAuthentication.verify(hello, "ready", device, token, directory))
        val wrongKey = RelayPeerAuthentication.create("hello", peer, "", token, ::sign)
        assertNull(RelayPeerAuthentication.verify(wrongKey, "hello", device, token, directory))
        val unknown = RelayPeerAuthentication.create("hello", "c16cb2bc-6dca-40bb-8e10-fc33b4d9272e", "", token, ::sign)
        assertNull(RelayPeerAuthentication.verify(unknown, "hello", device, token, directory))
        val modified = JSONObject(String(hello)).put("sender", device).toString().toByteArray()
        assertNull(RelayPeerAuthentication.verify(modified, "hello", device, token, directory))
        val session = RelayPeerSession(device, token)
        assertFalse(session.authenticated(directory))
        assertTrue(session.bufferUntilAuthenticated("pending".toByteArray()))
        assertFalse(requireNotNull(session.receive("ready", ready, directory)).becameAuthenticated)
        assertFalse(session.authenticated(directory))
        val accepted = requireNotNull(session.receive("hello", hello, directory))
        assertTrue(accepted.reply)
        assertTrue(accepted.becameAuthenticated)
        assertTrue(session.authenticated(directory))
        assertEquals("pending", String(session.drain().single()))
        assertFalse(session.bufferUntilAuthenticated("late".toByteArray()))
        assertNull(session.receive("hello", unknown, directory))
        assertFalse(requireNotNull(session.receive("ready", ready, directory)).becameAuthenticated)
        val otherProfile = "d76274b7-e21a-440e-ad0d-c51c6f6345e0"
        val otherGroup = document(JSONObject(body.toString()).put("profile_id", otherProfile))
        val otherDirectory = VerifiedRelayDirectory.verifyOffline(otherGroup, capabilities, device, otherProfile, issued)
        assertFalse(session.authenticated(otherDirectory))
        assertNull(session.receive("hello", hello, otherDirectory))
        val revoked = document(JSONObject(body.toString()).put("devices", JSONArray().put(devices.getJSONObject(0))))
        val revokedDirectory = VerifiedRelayDirectory.verifyOffline(revoked, capabilities, device, profile, issued)
        assertFalse(session.authenticated(revokedDirectory))
        assertNull(session.receive("hello", hello, revokedDirectory))
        val otherDevice = document(JSONObject(body.toString()).put("device_id", peer))
        val otherDeviceDirectory = VerifiedRelayDirectory.verifyOffline(otherDevice, capabilities, peer, profile, issued)
        assertFalse(session.authenticated(otherDeviceDirectory))
        assertNull(session.receive("hello", hello, otherDeviceDirectory))
        val remoteSession = RelayPeerSession(peer, token)
        val localHello = RelayPeerAuthentication.create("hello", device, "", token, ::sign)
        val localReady = RelayPeerAuthentication.create("ready", device, peer, token, ::sign)
        assertTrue(requireNotNull(remoteSession.receive("hello", localHello, otherDeviceDirectory)).reply)
        assertTrue(requireNotNull(remoteSession.receive("ready", localReady, otherDeviceDirectory)).becameAuthenticated)
        assertTrue(remoteSession.authenticated(otherDeviceDirectory))
        val replacement = JSONArray().put(devices.getJSONObject(0))
            .put(JSONObject(devices.getJSONObject(1).toString()).put("public_key", publicKey))
        val rotated = document(JSONObject(body.toString()).put("devices", replacement))
        val rotatedDirectory = VerifiedRelayDirectory.verifyOffline(rotated, capabilities, device, profile, issued)
        assertFalse(session.authenticated(rotatedDirectory))
        val waiting = RelayPeerSession(device, token)
        repeat(50) { assertTrue(waiting.bufferUntilAuthenticated(byteArrayOf(it.toByte()))) }
        assertFalse(waiting.bufferUntilAuthenticated(byteArrayOf(50)))
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedRelayDirectory.verifyOffline(signed, capabilities, device, profile, issued + 86_400_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedRelayDirectory.verify(signed, capabilities, profile, device, issued)
        }
    }

    @Test fun forgedOrWrongGroupDirectoryFailsClosed() {
        val json = directory()
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedRelayDirectory.verify(json, capabilities, "b76274b7-e21a-440e-ad0d-c51c6f6345e0", device, now)
        }
        json.put("document", Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { VerifiedRelayDirectory.verify(json, capabilities, profile, device, now) }
    }

    @Test fun offlineLeaseUsesElapsedTimeAndRejectsRebootOrMissingBootCount() {
            val lease = RelayOfflineLease(7, 1_000, now)
            assertTrue(lease.isCurrent(7, 1_000, 86_400_000))
            assertTrue(lease.isCurrent(7, 86_400_999, 86_400_000))
            assertFalse(lease.isCurrent(7, 86_401_000, 86_400_000))
            assertFalse(lease.isCurrent(8, 1_001, 86_400_000))
            assertFalse(lease.isCurrent(-1, 1_001, 86_400_000))
            assertFalse(RelayOfflineLease(-1, 1_000, now).isCurrent(-1, 1_001, 86_400_000))
            assertFalse(lease.isCurrent(7, 999, 86_400_000))
            assertFalse(lease.isCurrent(7, 1_001, 86_400_001))
    }

    @Test fun offlineDirectoryCannotBeExtendedByMovingWallClockBack() {
            val body = JSONObject().put("document_type", "offline_directory").put("profile_id", profile)
                .put("device_id", device).put("issued_at", Instant.ofEpochMilli(now).toString())
                .put("expires_at", Instant.ofEpochMilli(now + 86_400_000).toString())
                .put("devices", JSONArray().put(JSONObject().put("device_id", device)
                    .put("device_kind", "dog").put("public_key", publicKey)))
            var elapsed = 1_000L
            val directory = VerifiedRelayDirectory.verifyOffline(document(body), capabilities, device, profile, now)
                .withOfflineLease(RelayOfflineLease(7, elapsed, now), 7) { elapsed }
            assertTrue(directory.current(now - 3_600_000))
            elapsed += 86_400_000
            assertFalse(directory.current(now - 3_600_000))
            assertFalse(directory.accepts(envelope(), now - 3_600_000))
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedRelayDirectory.verifyOffline(document(body), capabilities, device, profile, now)
                    .withOfflineLease(RelayOfflineLease(7, 1_000, now), 8) { 1_001 }
            }
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

    @Test fun relayUploadRequiresSignedReceiptForTheExactEnvelope() {
        val current = System.currentTimeMillis()
        val envelope = RelayEnvelopeEncoder.create(device,
            participantLocationPayload(profile, 1, 51.0, 7.0, 5f, current, device), capabilities.encryption, ::sign,
            nowEpochMillis = current, lifetimeMillis = 90_000)
        val body = JSONObject().put("document_type", "receipt").put("profile_id", profile)
            .put("origin_device_id", device).put("message_id", envelope.messageId)
            .put("envelope_digest", relayEnvelopeDigest(envelope))
            .put("received_at", Instant.ofEpochMilli(current).toString())
            .put("expires_at", Instant.ofEpochMilli(current + 86_400_000).toString())
        val receipt = document(body)
        verifyRelayUploadReceipt(receipt, capabilities, profile, envelope)
        assertThrows(IllegalStateException::class.java) {
            verifyRelayUploadReceipt(receipt, capabilities, profile, envelope.copy(nonce = envelope.nonce.reversed()))
        }
        val forged = JSONObject(receipt.toString())
            .put("document", Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            verifyRelayUploadReceipt(forged, capabilities, profile, envelope)
        }
    }

    @Test fun dogDeletesOnlyTheExactOwnEnvelopeAfterVerifiedServerReceipt() {
        val current = System.currentTimeMillis()
        val envelope = RelayEnvelopeEncoder.create(device, "fix".toByteArray(), capabilities.encryption, ::sign,
            nowEpochMillis = current)
        val message = CommunicationMessage(envelope.messageId, RelayEnvelope.OUTGOING_MESSAGE_TYPE,
            "https://example.test/relay", current, envelope.expiresAtEpochMillis, envelope.toByteArray())
        val store = ReceiptTestStore(message)
        val body = JSONObject().put("document_type", "receipt").put("profile_id", profile)
            .put("origin_device_id", device).put("message_id", envelope.messageId)
            .put("envelope_digest", relayEnvelopeDigest(envelope))
            .put("received_at", Instant.ofEpochMilli(current).toString())
            .put("expires_at", Instant.ofEpochMilli(current + 86_400_000).toString())
        val receipt = VerifiedRelayReceipt.verify(document(body), capabilities, profile, current)

        assertFalse(acknowledgeDogRelayReceipt(store, receipt, "b16cb2bc-6dca-40bb-8e10-fc33b4d9272e"))
        val wrongDigest = VerifiedRelayReceipt.verify(document(JSONObject(body.toString())
            .put("envelope_digest", "0".repeat(64))), capabilities, profile, current)
        assertFalse(acknowledgeDogRelayReceipt(store, wrongDigest, device))
        store.message = CommunicationMessage(message.messageId, "location.fix", message.destination,
            message.createdAtEpochMillis, message.expiresAtEpochMillis, message.payload)
        assertFalse(acknowledgeDogRelayReceipt(store, receipt, device))
        store.message = message
        assertNotNull(store.findPending(envelope.messageId))
        assertTrue(acknowledgeDogRelayReceipt(store, receipt, device))
        assertNull(store.findPending(envelope.messageId))
        assertFalse(acknowledgeDogRelayReceipt(store, receipt, device))

        val forged = document(body).put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(256)))
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedRelayReceipt.verify(forged, capabilities, profile, current)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedRelayReceipt.verify(document(body), capabilities, "d76274b7-e21a-440e-ad0d-c51c6f6345e0", current)
        }
    }

    @Test fun nearbySendWindowRetriesUnconfirmedPacketsWithoutResendingOnEveryFix() {
        val window = NearbySendWindow(retryMillis = 30_000)
        val old = NearbyPacket("old", byteArrayOf(1))
        val fresh = NearbyPacket("fresh", byteArrayOf(2))
        val first = window.select("peer", listOf(old), 0).single()
        assertTrue(window.select("peer", listOf(old), 15_000).isEmpty())
        assertEquals(listOf("fresh"), window.select("peer", listOf(fresh, old), 15_000).map { it.packet.id })
        assertEquals(listOf("old"), window.select("peer", listOf(fresh, old), 30_000).map { it.packet.id })
        window.failed("peer", first)
        assertTrue(window.select("peer", listOf(old), 30_001).isEmpty())
        assertEquals(listOf("old"), window.select("other", listOf(old), 30_001).map { it.packet.id })
        window.forgetEndpoint("peer")
        val reconnected = window.select("peer", listOf(old), 30_001).single()
        window.failed("peer", reconnected)
        assertEquals(listOf("old"), window.select("peer", listOf(old), 30_002).map { it.packet.id })
    }

    @Test fun nearbySendWindowReachesAllReceiptsWithoutStarvingOlderBatches() {
        val window = NearbySendWindow(retryMillis = 30_000, batchSize = 50)
        val packets = (0 until 525).map { NearbyPacket(it.toString(), byteArrayOf(1), receipt = true) }
        val batches = (0 until 11).map { window.select("peer", packets, it * 10_000L) }
        assertEquals(List(11) { 50 }, batches.map { it.size })
        assertEquals(525, batches.flatten().map { it.packet.id }.toSet().size)
        assertEquals(packets.takeLast(25).map { it.id }, batches.last().take(25).map { it.packet.id })
        assertEquals(batches.first().take(25).map { it.packet.id }, batches.last().takeLast(25).map { it.packet.id })
        assertEquals(batches.first().takeLast(25).map { it.packet.id },
            window.select("peer", packets, 110_000).take(25).map { it.packet.id })
    }

    @Test fun receiptAcknowledgementStopsOnlyThatPeersReceiptRetries() {
        val window = NearbySendWindow(retryMillis = 30_000)
        val receipt = NearbyPacket("receipt", byteArrayOf(1), receipt = true)
        val envelope = NearbyPacket("envelope", byteArrayOf(2))
        val attempts = window.select("peer", listOf(receipt, envelope), 0)
        window.acknowledge("other", receipt.id)
        window.acknowledge("peer", envelope.id)
        assertTrue(window.needsRetry("peer", listOf(receipt, envelope)))
        window.acknowledge("peer", receipt.id)
        window.failed("peer", attempts.first())
        assertFalse(window.needsRetry("peer", listOf(receipt)))
        assertEquals(listOf("envelope"), window.select("peer", listOf(receipt, envelope), 30_000).map { it.packet.id })
        assertEquals(listOf("receipt"), window.select("other", listOf(receipt), 30_000).map { it.packet.id })
        window.forgetEndpoint("peer")
        assertEquals(listOf("receipt"), window.select("peer", listOf(receipt), 30_001).map { it.packet.id })
    }

    private class ReceiptTestStore(var message: CommunicationMessage?) : MessageStore {
        override fun findPending(messageId: String): StoredMessage? =
            message?.takeIf { it.messageId == messageId }?.let { StoredMessage(1, it) }
        override fun acknowledge(messageId: String) {
            if (message?.messageId == messageId) message = null
        }
        override fun enqueue(message: CommunicationMessage) { this.message = message }
        override fun oldestPending(): StoredMessage? = message?.let { StoredMessage(1, it) }
        override fun pendingOfType(type: String, limit: Int): List<StoredMessage> =
            message?.takeIf { it.type == type }?.let { listOf(StoredMessage(1, it)) } ?: emptyList()
        override fun pendingCount(): Int = if (message == null) 0 else 1
        override fun removeExpired(nowEpochMillis: Long) = Unit
        override fun clear() { message = null }
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
        assertTrue(RelayGatewayService.requiredRelayPermissions(32).contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(32).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(RelayGatewayService.requiredRelayPermissions(32).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(33).contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(33).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(33).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(RelayGatewayService.requiredRelayPermissions(37).contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }

    @Test fun eitherDiscoveringPeerEventuallyRequestsAConnection() {
        assertEquals(0L, nearbyConnectionDelayMillis("a", "b", firstAttempt = true))
        assertTrue(nearbyConnectionDelayMillis("b", "a", firstAttempt = true) > 0)
        assertTrue(nearbyConnectionDelayMillis("b", "a", firstAttempt = false) >
            nearbyConnectionDelayMillis("a", "b", firstAttempt = false))
    }

    @Test fun nearbyStartupRetriesFailuresButNotAfterStopOrRestart() {
        val scheduled = ArrayDeque<() -> Unit>()
        val failures = mutableListOf<String>()
        val startup = NearbyStartupRetry({ scheduled.addLast(it) }, failures::add)
        var generation = 0L
        var retries = 0
        startup.start { generation = it }
        startup.failed(generation, "search failed") { retries++ }
        assertEquals(listOf("search failed"), failures)
        scheduled.removeFirst()()
        assertEquals(1, retries)

        startup.failed(generation, "advertising failed") { retries++ }
        val oldGeneration = generation
        startup.stop {}
        startup.start { generation = it }
        scheduled.removeFirst()()
        startup.failed(oldGeneration, "late failure") { retries++ }
        assertTrue(scheduled.isEmpty())
        assertEquals(1, retries)
        startup.failed(generation, "new failure") { retries++ }
        startup.stop {}
        scheduled.removeFirst()()
        assertEquals(1, retries)
        assertEquals(listOf("search failed", "advertising failed", "new failure"), failures)
    }

    @Test fun gatewayShutdownKeepsIdentityForUnexpectedStopsButRevokesExplicitStops() {
        val shutdown = RelayGatewayShutdown()
        shutdown.start()
        assertFalse(shutdown.shouldRevoke(ineligible = false))
        assertTrue(shutdown.shouldRevoke(ineligible = true))
        shutdown.requestStop()
        assertTrue(shutdown.shouldRevoke(ineligible = false))
        shutdown.start()
        assertFalse(shutdown.shouldRevoke(ineligible = false))
    }

    @Test fun gatewayCleanupPreservesRegistrationAndQueueOnlyForUnexpectedStop() = runBlocking {
        val actions = mutableListOf<String>()
        val revoke: suspend (String) -> Unit = { actions.add("revoke:$it"); Unit }
        val delete: (String) -> Unit = { actions.add("delete:$it"); Unit }
        cleanupGatewayRegistration(device, false, revoke, delete)
        assertTrue(actions.isEmpty())
        cleanupGatewayRegistration(device, true, revoke, delete)
        assertEquals(listOf("revoke:$device", "delete:$device"), actions)
        actions.clear()
        val failure = try {
            cleanupGatewayRegistration(device, true, { throw IOException("offline") }, delete)
            null
        } catch (error: IOException) {
            error
        }
        assertEquals("offline", failure?.cause?.message)
        assertEquals(listOf("delete:$device"), actions)
    }
}