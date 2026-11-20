package org.wierichs.dogtracker.communication

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import org.json.JSONObject

internal data class RelayCapabilities(
    val encryption: RelayServerEncryptionKey,
    val receiptKeyId: String,
    val receiptPublicKey: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("protocol", "location-relay-v1")
        .put("encryption_key_id", encryption.keyId)
        .put("encryption_public_key", encryption.publicKey)
        .put("receipt_key_id", receiptKeyId)
        .put("receipt_public_key", receiptPublicKey)

    companion object {
        fun parse(json: JSONObject): RelayCapabilities {
            require(json.getString("protocol") == "location-relay-v1")
            return RelayCapabilities(RelayServerEncryptionKey(json.getString("encryption_key_id"), json.getString("encryption_public_key")),
                json.getString("receipt_key_id"), json.getString("receipt_public_key")).also {
                require(KEY_PATTERN.matches(it.encryption.keyId) && KEY_PATTERN.matches(it.receiptKeyId))
                importRelayPublicKey(it.encryption.publicKey)
                importRelayPublicKey(it.receiptPublicKey)
            }
        }
    }
}

internal class VerifiedRelayDirectory private constructor(
    val profileId: String,
    val localDeviceId: String,
    private val issuedAt: Long,
    private val expiresAt: Long,
    private val publicKeys: Map<String, String>,
    private val deviceKinds: Map<String, String>,
    private val subjects: Map<String, String>,
    private val broadcasters: Set<String>,
    private val dogRegistrants: Map<String, String>,
    val huntingSessionId: String?,
    private val leaseCurrent: () -> Boolean = { true }
) {
    fun accepts(envelope: RelayEnvelope, now: Long = System.currentTimeMillis()): Boolean {
        if (!current(now) || envelope.expiresAtEpochMillis <= now) return false
        val key = publicKeys[envelope.originDeviceId] ?: return false
        return runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(importRelayPublicKey(key))
                update(RelayEnvelopeEncoder.signingInput(envelope.originDeviceId, envelope.messageId, envelope.expiresAt,
                    envelope.keyId, envelope.encryptedKey, envelope.nonce, envelope.ciphertext))
                verify(decodeRelayBase64(envelope.signature))
            }
        }.getOrDefault(false)
    }

    fun current(now: Long = System.currentTimeMillis()): Boolean = now < expiresAt && leaseCurrent()

    fun withOfflineLease(anchor: RelayOfflineLease, bootCount: Int, elapsed: () -> Long): VerifiedRelayDirectory {
        require(anchor.isCurrent(bootCount, elapsed(), expiresAt - issuedAt))
        return VerifiedRelayDirectory(profileId, localDeviceId, issuedAt, expiresAt, publicKeys, deviceKinds, subjects,
            broadcasters, dogRegistrants, huntingSessionId) {
            anchor.isCurrent(bootCount, elapsed(), expiresAt - issuedAt)
        }
    }

    fun verifyPeer(deviceId: String, data: ByteArray, signature: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        if (!current(now)) return false
        val key = publicKeys[deviceId] ?: return false
        return runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(importRelayPublicKey(key))
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    fun containsKey(deviceId: String, publicKey: String): Boolean = publicKeys[deviceId] == publicKey

    fun deviceKind(deviceId: String): String? = deviceKinds[deviceId]

    fun subjectId(deviceId: String): String? = subjects[deviceId]

    fun canBroadcast(deviceId: String): Boolean = deviceId in broadcasters

    fun canSignalDog(participantDevice: String, dogDevice: String, dogId: String): Boolean =
        deviceKinds[participantDevice] == "participant" && deviceKinds[dogDevice] == "dog" &&
            subjects[dogDevice] == dogId && dogRegistrants[dogDevice]?.let { it == subjects[participantDevice] } == true

    fun participantSubjects(): Set<String> =
        subjects.filterKeys { deviceKinds[it] == "participant" }.values.toSet()

    companion object {
        fun verify(json: JSONObject, capabilities: RelayCapabilities, profileId: String, deviceId: String,
            now: Long = System.currentTimeMillis()): VerifiedRelayDirectory =
            verifyDocument(json, capabilities, deviceId, profileId, "directory", 120_000L, now)

        fun verifyOffline(json: JSONObject, capabilities: RelayCapabilities, deviceId: String,
            profileId: String? = null, now: Long = System.currentTimeMillis()): VerifiedRelayDirectory =
            verifyDocument(json, capabilities, deviceId, profileId, "offline_directory", 86_400_000L, now)

        private fun verifyDocument(json: JSONObject, capabilities: RelayCapabilities, deviceId: String,
            expectedProfileId: String?, documentType: String, maxLifetime: Long, now: Long): VerifiedRelayDirectory {
            val body = verifyRelayDocument(json, capabilities)
            val profileId = body.getString("profile_id")
            require(UUID_PATTERN.matches(profileId) && (expectedProfileId == null || profileId == expectedProfileId))
            require(body.getString("document_type") == documentType && body.getString("device_id") == deviceId)
            val huntingSessionId = if (body.has("session_id")) body.getString("session_id").also {
                require(UUID_PATTERN.matches(it))
            } else null
            val issuedAt = Instant.parse(body.getString("issued_at")).toEpochMilli()
            val expiresAt = Instant.parse(body.getString("expires_at")).toEpochMilli()
            require(issuedAt <= now + 30_000 && expiresAt > now && expiresAt > issuedAt &&
                expiresAt - issuedAt <= maxLifetime)
            val devices = body.getJSONArray("devices")
            require(devices.length() in 1..128)
            val publicKeys = mutableMapOf<String, String>()
            val deviceKinds = mutableMapOf<String, String>()
            val subjects = mutableMapOf<String, String>()
            val broadcasters = mutableSetOf<String>()
            val dogRegistrants = mutableMapOf<String, String>()
            for (index in 0 until devices.length()) {
                val device = devices.getJSONObject(index)
                val id = device.getString("device_id")
                val kind = device.getString("device_kind")
                require(UUID_PATTERN.matches(id) && kind in setOf("dog", "participant"))
                require(!publicKeys.containsKey(id))
                val key = device.getString("public_key")
                importRelayPublicKey(key)
                publicKeys[id] = key
                deviceKinds[id] = kind
                if (device.has("subject_id")) {
                    val subjectId = device.getString("subject_id")
                    require(UUID_PATTERN.matches(subjectId))
                    subjects[id] = subjectId
                }
                if (kind == "participant" && id in subjects && device.opt("can_broadcast") == true) {
                    broadcasters += id
                }
                if (kind == "dog" && id in subjects && !device.isNull("dog_registrant_id")) {
                    val registrant = device.getString("dog_registrant_id")
                    require(UUID_PATTERN.matches(registrant))
                    dogRegistrants[id] = registrant
                }
            }
            require(publicKeys.containsKey(deviceId))
            return VerifiedRelayDirectory(profileId, deviceId, issuedAt, expiresAt, publicKeys, deviceKinds, subjects,
                broadcasters, dogRegistrants, huntingSessionId)
        }
    }
}

internal class VerifiedHuntingEnd private constructor(
    val profileId: String,
    val sessionId: String,
    val endedAt: Instant,
    val expiresAt: Instant,
    private val wireDocument: String
) {
    fun toByteArray(): ByteArray = wireDocument.toByteArray(Charsets.UTF_8)

    companion object {
        fun verify(bytes: ByteArray, capabilities: RelayCapabilities, profileId: String, sessionId: String,
            now: Instant = Instant.now()): VerifiedHuntingEnd {
            require(bytes.size in 1..8192)
            require(UUID_PATTERN.matches(profileId) && UUID_PATTERN.matches(sessionId))
            val wireDocument = String(bytes, Charsets.UTF_8)
            val body = verifyRelayDocument(JSONObject(wireDocument), capabilities)
            require(body.getString("document_type") == "hunt_end" && body.getString("profile_id") == profileId &&
                body.getString("session_id") == sessionId)
            val endedAt = Instant.parse(body.getString("ended_at"))
            val expiresAt = Instant.parse(body.getString("expires_at"))
            require(endedAt <= now.plusSeconds(30) && expiresAt > now && expiresAt == endedAt.plusSeconds(86_400))
            return VerifiedHuntingEnd(profileId, sessionId, endedAt, expiresAt, wireDocument)
        }
    }
}

internal class VerifiedRelayReceipt private constructor(
    val profileId: String,
    val originDeviceId: String,
    val messageId: String,
    private val digest: String,
    val expiresAt: Long,
    val bytes: ByteArray
) {
    fun matches(envelope: RelayEnvelope): Boolean = envelope.originDeviceId == originDeviceId &&
        envelope.messageId == messageId && relayEnvelopeDigest(envelope) == digest

    companion object {
        fun verify(json: JSONObject, capabilities: RelayCapabilities, profileId: String,
            now: Long = System.currentTimeMillis()): VerifiedRelayReceipt {
            val body = verifyRelayDocument(json, capabilities)
            require(body.getString("document_type") == "receipt" && body.getString("profile_id") == profileId)
            val origin = body.getString("origin_device_id")
            val messageId = body.getString("message_id")
            val digest = body.getString("envelope_digest")
            val receivedAt = Instant.parse(body.getString("received_at")).toEpochMilli()
            val expiresAt = Instant.parse(body.getString("expires_at")).toEpochMilli()
            require(UUID_PATTERN.matches(origin) && UUID_PATTERN.matches(messageId) && Regex("[0-9a-f]{64}").matches(digest))
            require(receivedAt <= now + 30_000 && expiresAt > now && expiresAt <= now + 86_430_000)
            return VerifiedRelayReceipt(profileId, origin, messageId, digest, expiresAt, json.toString().toByteArray(Charsets.UTF_8))
        }
    }
}

internal fun acknowledgeDogRelayReceipt(store: MessageStore, receipt: VerifiedRelayReceipt, localDeviceId: String): Boolean {
    if (receipt.originDeviceId != localDeviceId) return false
    val message = store.findPending(receipt.messageId)?.message ?: return false
    if (message.type != RelayEnvelope.OUTGOING_MESSAGE_TYPE &&
        !isLegacyOwnRelayEnvelope(message, localDeviceId)
    ) return false
    val envelope = RelayEnvelope.parse(message.payload) ?: return false
    if (envelope.originDeviceId != localDeviceId || !receipt.matches(envelope)) return false
    store.acknowledge(message.messageId)
    return true
}

internal fun relayEnvelopeDigest(envelope: RelayEnvelope): String {
    val input = RelayEnvelopeEncoder.signingInput(envelope.originDeviceId, envelope.messageId, envelope.expiresAt,
        envelope.keyId, envelope.encryptedKey, envelope.nonce, envelope.ciphertext)
    val digest = MessageDigest.getInstance("SHA-256").digest(input + "\n${envelope.signature}".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun relayStorageId(originDeviceId: String, messageId: String): String {
    require(UUID_PATTERN.matches(originDeviceId) && UUID_PATTERN.matches(messageId))
    return "$originDeviceId:$messageId"
}

internal fun participantLocationPayload(profileId: String, sequence: Long, latitude: Double, longitude: Double,
    accuracy: Float, recordedAt: Long, sharingEpoch: String): ByteArray {
    require(UUID_PATTERN.matches(profileId) && sequence in 1..9_007_199_254_740_991L)
    require(UUID_PATTERN.matches(sharingEpoch))
    require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
    require(accuracy.isFinite() && accuracy in 0f..5000f && recordedAt >= 0)
    val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(recordedAt))
    return JSONObject().put("message_type", "participant.location").put("profile_id", profileId).put("sequence", sequence.toString())
        .put("sharing_epoch", sharingEpoch)
        .put("latitude", latitude).put("longitude", longitude).put("accuracy", accuracy.toDouble()).put("recorded_at", timestamp)
        .toString().toByteArray(Charsets.UTF_8)
}

private fun verifyRelayDocument(json: JSONObject, capabilities: RelayCapabilities): JSONObject {
    require(json.toString().toByteArray(Charsets.UTF_8).size <= 196_608)
    require(json.getString("action") == "relay_document" && json.getString("key_id") == capabilities.receiptKeyId)
    val document = json.getString("document")
    val signature = decodeRelayBase64(json.getString("signature"))
    val valid = Signature.getInstance("SHA256withRSA").run {
        initVerify(importRelayPublicKey(capabilities.receiptPublicKey))
        update("location-relay-document-v1\n${capabilities.receiptKeyId}\n$document".toByteArray(Charsets.UTF_8))
        verify(signature)
    }
    require(valid)
    return JSONObject(String(decodeRelayBase64(document), Charsets.UTF_8))
}

private fun decodeRelayBase64(value: String): ByteArray {
    require(value.length <= 180_000 && Regex("[A-Za-z0-9_-]+").matches(value))
    return Base64.getUrlDecoder().decode(value).also {
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value)
    }
}

private fun importRelayPublicKey(encoded: String): java.security.PublicKey {
    val bytes = decodeRelayBase64(encoded)
    require(bytes.size in 256..1024)
    return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)).also {
        require((it as java.security.interfaces.RSAPublicKey).modulus.bitLength() in 2048..4096)
    }
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val KEY_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")