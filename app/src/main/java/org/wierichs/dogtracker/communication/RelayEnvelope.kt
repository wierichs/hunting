package org.wierichs.dogtracker.communication

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.json.JSONObject

internal data class RelayEnvelope(
    val originDeviceId: String,
    val messageId: String,
    val expiresAt: String,
    val keyId: String,
    val encryptedKey: String,
    val nonce: String,
    val ciphertext: String,
    val signature: String
) {
    val expiresAtEpochMillis: Long
        get() = Instant.parse(expiresAt).toEpochMilli()

    fun toJson(): JSONObject = JSONObject()
        .put("action", "relay")
        .put("origin_device_id", originDeviceId)
        .put("message_id", messageId)
        .put("expires_at", expiresAt)
        .put("key_id", keyId)
        .put("encrypted_key", encryptedKey)
        .put("nonce", nonce)
        .put("ciphertext", ciphertext)
        .put("signature", signature)

    fun toByteArray(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val MESSAGE_TYPE = "relay.envelope"
        private const val MAX_BYTES = 8192
        private const val MAX_LOCATION_BYTES = 4096
        private const val MAX_RELAY_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
        private val canonicalInstantFormatter: DateTimeFormatter =
            DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        private val UUID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val KEY_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")

        fun parse(bytes: ByteArray, nowEpochMillis: Long = System.currentTimeMillis()): RelayEnvelope? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            return runCatching {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val envelope = RelayEnvelope(
                    originDeviceId = json.getString("origin_device_id"),
                    messageId = json.getString("message_id"),
                    expiresAt = json.getString("expires_at"),
                    keyId = json.getString("key_id"),
                    encryptedKey = json.getString("encrypted_key"),
                    nonce = json.getString("nonce"),
                    ciphertext = json.getString("ciphertext"),
                    signature = json.getString("signature")
                )
                require(json.getString("action") == "relay")
                require(UUID_PATTERN.matches(envelope.originDeviceId))
                require(UUID_PATTERN.matches(envelope.messageId))
                require(KEY_ID_PATTERN.matches(envelope.keyId))
                require(canonicalInstantFormatter.format(Instant.parse(envelope.expiresAt)) == envelope.expiresAt)
                require(envelope.expiresAtEpochMillis > nowEpochMillis)
                require(envelope.expiresAtEpochMillis <= nowEpochMillis + MAX_RELAY_LIFETIME_MILLIS)
                require(decode(envelope.encryptedKey).size in 256..1024)
                require(decode(envelope.nonce).size == 12)
                require(decode(envelope.ciphertext).size in 17..(MAX_LOCATION_BYTES + 16))
                require(decode(envelope.signature).size in 256..512)
                envelope
            }.getOrNull()
        }

        private fun decode(value: String): ByteArray {
            require(BASE64_URL_PATTERN.matches(value))
            return Base64.getUrlDecoder().decode(value)
        }
    }
}

internal object RelayEnvelopeEncoder {
    private const val AES_KEY_SIZE_BITS = 256
    private const val GCM_NONCE_SIZE_BYTES = 12
    private const val GCM_TAG_SIZE_BITS = 128
    private const val MAX_LOCATION_BYTES = 4096
    private const val MAX_RELAY_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
    private val canonicalInstantFormatter: DateTimeFormatter =
        DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private val random = SecureRandom()

    fun create(
        originDeviceId: String,
        locationPayload: ByteArray,
        serverKey: RelayServerEncryptionKey,
        sign: (ByteArray) -> ByteArray,
        nowEpochMillis: Long = System.currentTimeMillis(),
        messageId: String = UUID.randomUUID().toString()
    ): RelayEnvelope {
        require(UUID_PATTERN.matches(originDeviceId))
        require(UUID_PATTERN.matches(messageId))
        require(locationPayload.isNotEmpty() && locationPayload.size <= MAX_LOCATION_BYTES)
        require(KEY_ID_PATTERN.matches(serverKey.keyId))

        val serverPublicKey = importServerPublicKey(serverKey.publicKey)
        val aesKey = KeyGenerator.getInstance("AES").apply { init(AES_KEY_SIZE_BITS) }.generateKey()
        val nonce = ByteArray(GCM_NONCE_SIZE_BYTES).also(random::nextBytes)
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, javax.crypto.spec.GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce))
        val encryptedPayload = aesCipher.doFinal(locationPayload)

        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, serverPublicKey, oaepParameters())
        val encryptedAesKey = rsaCipher.doFinal(aesKey.encoded)

        val expiresAt = canonicalInstantFormatter.format(Instant.ofEpochMilli(nowEpochMillis + MAX_RELAY_LIFETIME_MILLIS))
        val encodedEncryptedKey = encodeBase64Url(encryptedAesKey)
        val encodedNonce = encodeBase64Url(nonce)
        val encodedCiphertext = encodeBase64Url(encryptedPayload)
        val signatureBytes = sign(
            signingInput(
                originDeviceId,
                messageId,
                expiresAt,
                serverKey.keyId,
                encodedEncryptedKey,
                encodedNonce,
                encodedCiphertext
            )
        )

        return RelayEnvelope(
            originDeviceId = originDeviceId.lowercase(),
            messageId = messageId,
            expiresAt = expiresAt,
            keyId = serverKey.keyId,
            encryptedKey = encodedEncryptedKey,
            nonce = encodedNonce,
            ciphertext = encodedCiphertext,
            signature = encodeBase64Url(signatureBytes)
        )
    }

    internal fun signingInput(
        originDeviceId: String,
        messageId: String,
        expiresAt: String,
        keyId: String,
        encryptedKey: String,
        nonce: String,
        ciphertext: String
    ): ByteArray = listOf(
        "dog-location-relay-v1",
        originDeviceId.lowercase(),
        messageId.lowercase(),
        expiresAt,
        keyId,
        encryptedKey,
        nonce,
        ciphertext
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun importServerPublicKey(encoded: String) = runCatching {
        require(BASE64_URL_PATTERN.matches(encoded))
        val keyBytes = Base64.getUrlDecoder().decode(encoded)
        require(keyBytes.size in 256..1024)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        require((key as java.security.interfaces.RSAPublicKey).modulus.bitLength() in 2048..4096)
        key
    }.getOrElse { throw IllegalArgumentException("Invalid relay server public key", it) }

    private fun oaepParameters() = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT
    )

    private fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val UUID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
    private val KEY_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
}