package org.wierichs.dogtracker.communication

import java.util.Base64
import org.json.JSONObject

internal object RelayPeerAuthentication {
    private val idPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val base64Pattern = Regex("^[A-Za-z0-9_-]+$")

    fun create(action: String, sender: String, recipient: String, token: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        require(action in setOf("hello", "ready") && idPattern.matches(sender))
        require((action == "hello" && recipient.isEmpty()) || (action == "ready" && idPattern.matches(recipient)))
        require(token.size in 16..128)
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(input(action, sender, recipient, token)))
        return JSONObject().put("action", "peer_$action").put("sender", sender)
            .put("recipient", recipient).put("signature", signature).toString().toByteArray(Charsets.UTF_8)
    }

    internal data class RelayPeerUpdate(val peerId: String, val reply: Boolean, val becameAuthenticated: Boolean)

    internal class RelayPeerSession(private val localDeviceId: String, token: ByteArray) {
        val token = token.copyOf()
        private var peerId: String? = null
        private var profileId: String? = null
        private var hello: ByteArray? = null
        private var ready = false
        private var complete = false
        private val pending = ArrayDeque<ByteArray>()

        @Synchronized
        fun bufferUntilAuthenticated(bytes: ByteArray): Boolean {
            if (complete || pending.size >= 50) return false
            pending.addLast(bytes.copyOf())
            return true
        }

        @Synchronized
        fun drain(): List<ByteArray> {
            check(complete)
            return pending.toList().also { pending.clear() }
        }

        @Synchronized
        fun receive(action: String, bytes: ByteArray, directory: VerifiedRelayDirectory): RelayPeerUpdate? {
            if (directory.localDeviceId != localDeviceId || (profileId != null && profileId != directory.profileId)) return null
            val sender = RelayPeerAuthentication.verify(bytes, action, localDeviceId, token, directory) ?: return null
            if (peerId != null && peerId != sender) return null
            peerId = sender
            profileId = directory.profileId
            if (action == "hello") hello = bytes.copyOf() else ready = true
            val becameAuthenticated = !complete && hello != null && ready
            if (becameAuthenticated) complete = true
            return RelayPeerUpdate(sender, action == "hello", becameAuthenticated)
        }

        @Synchronized
        fun authenticatedPeerId(directory: VerifiedRelayDirectory): String? {
            val proof = hello ?: return null
            val sender = peerId ?: return null
            return sender.takeIf {
                complete && directory.localDeviceId == localDeviceId && directory.profileId == profileId &&
                    RelayPeerAuthentication.verify(proof, "hello", localDeviceId, token, directory) == sender
            }
        }

        fun authenticated(directory: VerifiedRelayDirectory): Boolean = authenticatedPeerId(directory) != null
    }

    fun verify(bytes: ByteArray, action: String, recipient: String, token: ByteArray,
        directory: VerifiedRelayDirectory): String? {
        if (bytes.size !in 1..8192 || action !in setOf("hello", "ready") || token.size !in 16..128 ||
            directory.localDeviceId != recipient
        ) return null
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val sender = json.getString("sender")
            val target = json.getString("recipient")
            val encoded = json.getString("signature")
            if (json.getString("action") != "peer_$action" || !idPattern.matches(sender) || sender == recipient ||
                target != (if (action == "hello") "" else recipient) ||
                !base64Pattern.matches(encoded) || encoded.length > 1024
            ) return null
            val signature = Base64.getUrlDecoder().decode(encoded)
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(signature) != encoded) return null
            if (directory.verifyPeer(sender, input(action, sender, target, token), signature)) sender else null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun input(action: String, sender: String, recipient: String, token: ByteArray): ByteArray =
        listOf("jagdbruder-nearby-auth-v1", action, sender, recipient,
            Base64.getUrlEncoder().withoutPadding().encodeToString(token)).joinToString("\n").toByteArray(Charsets.UTF_8)
}
