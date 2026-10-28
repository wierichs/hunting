package org.wierichs.dogtracker.communication

import java.util.UUID

class CommunicationMessage(
    val messageId: String,
    val type: String,
    val destination: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    payload: ByteArray
) {
    private val payloadBytes = payload.copyOf()
    val payload: ByteArray
        get() = payloadBytes.copyOf()

    init {
        require(messageId.isNotBlank())
        require(type.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        require(destination.isNotBlank())
        require(createdAtEpochMillis >= 0)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis)
        require(payloadBytes.isNotEmpty())
        require(payloadBytes.size <= MAX_PAYLOAD_BYTES)
    }

    fun isExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMillis?.let { nowEpochMillis >= it } ?: false

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        fun create(
            type: String,
            destination: String,
            payload: ByteArray,
            createdAtEpochMillis: Long = System.currentTimeMillis(),
            expiresAtEpochMillis: Long? = null
        ) = CommunicationMessage(
            messageId = UUID.randomUUID().toString(),
            type = type,
            destination = destination,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            payload = payload
        )
    }
}