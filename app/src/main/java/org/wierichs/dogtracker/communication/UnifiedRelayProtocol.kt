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
    private val expiresAt: Long,
    private val publicKeys: Map<String, String>
) {
    fun accepts(envelope: RelayEnvelope, now: Long = System.currentTimeMillis()): Boolean {
        if (now >= expiresAt || envelope.expiresAtEpochMillis <= now) return false
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

    fun current(now: Long = System.currentTimeMillis()): Boolean = now < expiresAt

    companion object {
        fun verify(json: JSONObject, capabilities: RelayCapabilities, profileId: String, deviceId: String,
            now: Long = System.currentTimeMillis()): VerifiedRelayDirectory {
            val body = verifyRelayDocument(json, capabilities)
            require(body.getString("document_type") == "directory" && body.getString("profile_id") == profileId &&
                body.getString("device_id") == deviceId)
            val issuedAt = Instant.parse(body.getString("issued_at")).toEpochMilli()
            val expiresAt = Instant.parse(body.getString("expires_at")).toEpochMilli()
            require(issuedAt <= now + 30_000 && expiresAt > now && expiresAt <= issuedAt + 120_000)
            val devices = body.getJSONArray("devices")
            require(devices.length() in 1..128)
            val publicKeys = mutableMapOf<String, String>()
            for (index in 0 until devices.length()) {
                val device = devices.getJSONObject(index)
                val id = device.getString("device_id")
                require(UUID_PATTERN.matches(id) && device.getString("device_kind") in setOf("dog", "participant"))
                require(!publicKeys.containsKey(id))
                val key = device.getString("public_key")
                importRelayPublicKey(key)
                publicKeys[id] = key
            }
            require(publicKeys.containsKey(deviceId))
            return VerifiedRelayDirectory(profileId, deviceId, expiresAt, publicKeys)
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