package org.wierichs.dogtracker.communication

import java.util.Base64
import java.util.UUID
import org.json.JSONObject

internal data class OfflinePositionMessage(
    val profileId: String,
    val senderDeviceId: String,
    val subjectType: String,
    val subjectId: String,
    val sequence: Long,
    val recordedAtEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val signature: String
) {
    fun toByteArray(): ByteArray = JSONObject()
        .put("action", ACTION)
        .put("version", VERSION)
        .put("profile_id", profileId)
        .put("sender_device_id", senderDeviceId)
        .put("subject_type", subjectType)
        .put("subject_id", subjectId)
        .put("sequence", sequence.toString())
        .put("recorded_at_ms", recordedAtEpochMillis)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy_m", accuracyMeters)
        .put("signature", signature)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun verify(directory: VerifiedRelayDirectory, nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        if (directory.profileId != profileId || directory.deviceKind(senderDeviceId) != subjectType ||
            directory.subjectId(senderDeviceId) != subjectId ||
            nowEpochMillis < 0 || !isNotTooFarInFuture(recordedAtEpochMillis, nowEpochMillis)
        ) return false
        val decodedSignature = try {
            validate(profileId, senderDeviceId, subjectType, subjectId, sequence, recordedAtEpochMillis,
                latitude, longitude, accuracyMeters)
            require(BASE64_URL_PATTERN.matches(signature))
            signatureBytes().also {
                require(it.size in 256..512)
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == signature)
            }
        } catch (_: IllegalArgumentException) {
            return false
        }
        return directory.verifyPeer(senderDeviceId, signingInput(), decodedSignature, nowEpochMillis)
    }

    private fun signingInput(): ByteArray = listOf(
        DOMAIN,
        profileId,
        senderDeviceId,
        subjectType,
        subjectId,
        sequence.toString(),
        recordedAtEpochMillis.toString(),
        java.lang.Double.toString(latitude),
        java.lang.Double.toString(longitude),
        java.lang.Double.toString(accuracyMeters)
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun signatureBytes(): ByteArray = Base64.getUrlDecoder().decode(signature)

    companion object {
        const val ACTION = "offline_position"
        private const val VERSION = 1
        private const val DOMAIN = "jagdbruder-offline-position-v1"
        private const val MAX_MESSAGE_BYTES = 8192
        private const val MAX_FUTURE_SKEW_MILLIS = 30_000L
        private const val MAX_SEQUENCE = 9_007_199_254_740_991L
        private val UUID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")

        fun fromPeer(bytes: ByteArray, peerId: String, directory: VerifiedRelayDirectory,
            now: Long = System.currentTimeMillis()): OfflinePositionMessage? =
            parse(bytes, now)?.takeIf { it.senderDeviceId == peerId && it.verify(directory, now) }

        private fun isNotTooFarInFuture(recordedAtEpochMillis: Long, nowEpochMillis: Long): Boolean =
            recordedAtEpochMillis <= nowEpochMillis ||
                recordedAtEpochMillis - nowEpochMillis <= MAX_FUTURE_SKEW_MILLIS

        fun create(
            profileId: String,
            senderDeviceId: String,
            subjectType: String,
            subjectId: String,
            sequence: Long,
            recordedAtEpochMillis: Long,
            latitude: Double,
            longitude: Double,
            accuracyMeters: Double,
            sign: (ByteArray) -> ByteArray
        ): OfflinePositionMessage {
            validate(profileId, senderDeviceId, subjectType, subjectId, sequence, recordedAtEpochMillis,
                latitude, longitude, accuracyMeters)
            val unsigned = OfflinePositionMessage(profileId, senderDeviceId, subjectType, subjectId, sequence,
                recordedAtEpochMillis, latitude, longitude, accuracyMeters, "")
            return unsigned.copy(signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(unsigned.signingInput())))
        }

        fun parse(bytes: ByteArray, nowEpochMillis: Long = System.currentTimeMillis()): OfflinePositionMessage? {
            if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return null
            return runCatching {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                require(json.getString("action") == ACTION && json.getInt("version") == VERSION)
                val message = OfflinePositionMessage(
                    profileId = json.getString("profile_id"),
                    senderDeviceId = json.getString("sender_device_id"),
                    subjectType = json.getString("subject_type"),
                    subjectId = json.getString("subject_id"),
                    sequence = json.getString("sequence").toLong(),
                    recordedAtEpochMillis = json.getLong("recorded_at_ms"),
                    latitude = json.getDouble("latitude"),
                    longitude = json.getDouble("longitude"),
                    accuracyMeters = json.getDouble("accuracy_m"),
                    signature = json.getString("signature")
                )
                validate(message.profileId, message.senderDeviceId, message.subjectType, message.subjectId,
                    message.sequence, message.recordedAtEpochMillis, message.latitude, message.longitude,
                    message.accuracyMeters)
                require(nowEpochMillis >= 0 &&
                    isNotTooFarInFuture(message.recordedAtEpochMillis, nowEpochMillis))
                require(BASE64_URL_PATTERN.matches(message.signature))
                val signatureBytes = Base64.getUrlDecoder().decode(message.signature)
                require(signatureBytes.size in 256..512)
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes) == message.signature)
                message
            }.getOrNull()
        }

        private fun validate(
            profileId: String,
            senderDeviceId: String,
            subjectType: String,
            subjectId: String,
            sequence: Long,
            recordedAtEpochMillis: Long,
            latitude: Double,
            longitude: Double,
            accuracyMeters: Double
        ) {
            require(UUID_PATTERN.matches(profileId) && UUID_PATTERN.matches(senderDeviceId) &&
                UUID_PATTERN.matches(subjectId))
            require(subjectType in setOf("participant", "dog"))
            require(sequence in 1..MAX_SEQUENCE && recordedAtEpochMillis >= 0)
            require(latitude.isFinite() && latitude in -90.0..90.0)
            require(longitude.isFinite() && longitude in -180.0..180.0)
            require(accuracyMeters.isFinite() && accuracyMeters in 0.0..5000.0)
        }
    }
}
