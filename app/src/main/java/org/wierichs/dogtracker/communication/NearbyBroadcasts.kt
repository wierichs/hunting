package org.wierichs.dogtracker.communication

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class BroadcastTemplate(val code: String, val text: String) {
    DRIVE_BEGINS("drive_begins", "Das Treiben beginnt!"),
    CEASE_FIRE("cease_fire", "Hahn in Ruh!"),
    ASSEMBLY("assembly", "Zurück zum Sammelplatz.");

    companion object {
        fun fromCode(code: String): BroadcastTemplate? = entries.firstOrNull { it.code == code }
    }
}

internal const val BROADCAST_ACTION = "nearby_broadcast_v1"
internal const val BROADCAST_ACK_ACTION = "nearby_broadcast_ack_v1"
internal const val BROADCAST_MAX_AGE_MS = 30_000L

internal data class NearbyBroadcast(
    val id: String,
    val template: BroadcastTemplate,
    val sender: String,
    val sentAt: Long,
    val recipients: Set<String> = emptySet(),
    val connected: Set<String> = emptySet(),
    val confirmed: Set<String> = emptySet(),
    val outgoing: Boolean = false
)

internal fun broadcastPayload(profileId: String, message: NearbyBroadcast): ByteArray =
    JSONObject().put("action", BROADCAST_ACTION).put("profile_id", profileId).put("id", message.id)
        .put("template", message.template.code).put("sent_at_ms", message.sentAt).broadcastBytes()

internal fun broadcastAck(profileId: String, id: String): ByteArray =
    JSONObject().put("action", BROADCAST_ACK_ACTION).put("profile_id", profileId).put("id", id).broadcastBytes()

private fun JSONObject.broadcastBytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

internal fun broadcastId(json: JSONObject): String? =
    json.optString("id").takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }

internal fun parseNearbyBroadcast(json: JSONObject, profileId: String, sender: String, now: Long): NearbyBroadcast? {
    if (json.optString("profile_id") != profileId) return null
    val id = broadcastId(json) ?: return null
    val template = BroadcastTemplate.fromCode(json.optString("template")) ?: return null
    val sentAt = json.optLong("sent_at_ms", -1)
    if (sentAt !in (now - BROADCAST_MAX_AGE_MS)..(now + BROADCAST_MAX_AGE_MS)) return null
    return NearbyBroadcast(id, template, sender, sentAt)
}

internal class NearbyBroadcasts {
    private val mutableMessages = MutableStateFlow<List<NearbyBroadcast>>(emptyList())
    val messages = mutableMessages.asStateFlow()
    private val seen = mutableMapOf<Pair<String, String>, Long>()
    private val targets = mutableMapOf<String, Map<String, String>>()

    @Synchronized
    fun sent(message: NearbyBroadcast, targetDevices: Map<String, String>) {
        targets[message.id] = targetDevices
        append(message)
    }

    @Synchronized
    fun receive(deviceId: String, message: NearbyBroadcast, now: Long): Boolean {
        seen.entries.removeAll { it.value < now }
        val key = deviceId to message.id
        if (key in seen || seen.size >= 256) return false
        seen[key] = message.sentAt + BROADCAST_MAX_AGE_MS
        append(message)
        return true
    }

    @Synchronized
    fun confirm(id: String, deviceId: String, userId: String) {
        if (targets[id]?.get(deviceId) != userId) return
        mutableMessages.value = mutableMessages.value.map {
            if (it.id == id && it.outgoing) it.copy(confirmed = it.confirmed + userId) else it
        }
    }

    private fun append(message: NearbyBroadcast) {
        mutableMessages.value = (listOf(message) + mutableMessages.value).take(20)
        targets.keys.retainAll(mutableMessages.value.filter { it.outgoing }.map { it.id }.toSet())
    }
}
