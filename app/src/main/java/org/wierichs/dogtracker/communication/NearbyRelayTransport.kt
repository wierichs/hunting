package org.wierichs.dogtracker.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.wierichs.dogtracker.communication.RelayPeerAuthentication.RelayPeerSession

internal class NearbyRelayTransport(
    context: Context,
    private val localDeviceId: String,
    private val authorization: () -> VerifiedRelayDirectory?,
    private val onIncomingEnvelope: (RelayEnvelope) -> Unit,
    private val onTransportStatus: (String) -> Unit,
    private val pendingPackets: (() -> List<NearbyPacket>)? = null,
    private val onIncomingDocument: (ByteArray, () -> Unit) -> Unit = { _, _ -> },
    private val onIncomingPosition: ((OfflinePositionMessage, () -> Unit) -> Unit)? = null,
    private val canPublishPosition: () -> Boolean = { false },
    private val broadcasts: NearbyBroadcasts? = null,
    private val onDogSignal: ((DogSignal) -> Boolean)? = null,
    private val huntingEndPacket: () -> NearbyPacket? = { null },
    private val onHuntingEnd: ((ByteArray, () -> Unit) -> Unit)? = null,
    private val regularTrafficAllowed: () -> Boolean = { true }
) {
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val sessionId = UUID.randomUUID().toString()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val requestedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val discoveredEndpoints = ConcurrentHashMap<String, String>()
    private val sendWindow = NearbySendWindow()
    private val sessions = ConcurrentHashMap<String, RelayPeerSession>()
    private data class PendingDogSignal(val session: RelayPeerSession, val dogId: String,
        val result: CompletableDeferred<DogSignalResult> = CompletableDeferred())
    private val pendingDogSignals = ConcurrentHashMap<String, PendingDogSignal>()
    private val recentDogSignals = mutableMapOf<String, Long>()
    private var lastDogSignal = -5_000L
    private val handler = Handler(Looper.getMainLooper())
    private val sendJob = SupervisorJob()
    private val sendScope = CoroutineScope(sendJob + Dispatchers.IO)
    private val retryScheduled = AtomicBoolean(false)
    private var ownPosition: Pair<OfflinePositionMessage, NearbyPacket>? = null
    private val startup = NearbyStartupRetry(
        schedule = { retry -> handler.postDelayed({ retry() }, TimeUnit.SECONDS.toMillis(10)) },
        onFailure = onTransportStatus
    )
    @Volatile private var isStarted = false
    private val retryPending = object : Runnable {
        override fun run() {
            retryScheduled.set(false)
            if (!isStarted || connectedEndpoints.isEmpty()) return
            sendScope.launch { sendPendingToAll() }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.size > 8192) return
            val session = sessions[endpointId] ?: return
            val action = try {
                JSONObject(String(bytes, Charsets.UTF_8)).optString("action")
            } catch (_: org.json.JSONException) {
                ""
            }
            if (action == "peer_hello" || action == "peer_ready") {
                receiveAuthentication(endpointId, session, action.removePrefix("peer_"), bytes)
                return
            }
            if (!authenticated(session)) {
                if (session.bufferUntilAuthenticated(bytes)) return
                if (!authenticated(session)) {
                    disconnect(endpointId, "Relay-Verbindung nicht authentifiziert oder Berechtigung abgelaufen.")
                    return
                }
            }
            deliver(endpointId, session, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            requestedEndpoints.add(endpointId)
            val directory = authorization()
            val token = info.rawAuthenticationToken
            if (directory == null || !directory.current() || token.size !in 16..128 ||
                sessions.size >= MAX_SESSIONS
            ) {
                requestedEndpoints.remove(endpointId)
                client.rejectConnection(endpointId)
                onTransportStatus("Relay-Verbindung abgelehnt: gültige Revierberechtigung fehlt.")
                retryConnection(endpointId)
                return
            }
            val session = RelayPeerSession(localDeviceId, token)
            sessions[endpointId] = session
            client.acceptConnection(endpointId, payloadCallback).addOnFailureListener {
                disconnect(endpointId, "Relay-Verbindung konnte nicht angenommen werden.")
            }
            handler.postDelayed({
                if (sessions[endpointId] === session && !authenticated(session)) {
                    disconnect(endpointId, "Relay-Authentifizierung fehlgeschlagen oder Zeitüberschreitung.")
                }
            }, TimeUnit.SECONDS.toMillis(30))
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            requestedEndpoints.remove(endpointId)
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val session = sessions[endpointId] ?: return
                sendAuthentication(endpointId, session, "hello", "")
            } else {
                sessions.remove(endpointId)
                onTransportStatus("Tracker-Verbindung wurde nicht hergestellt.")
                retryConnection(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            requestedEndpoints.remove(endpointId)
            sessions.remove(endpointId)
            sendWindow.forgetEndpoint(endpointId)
            if (connectedEndpoints.isEmpty()) cancelRetry()
            retryConnection(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discoveredEndpoints[endpointId] = info.endpointName
            scheduleConnection(endpointId, firstAttempt = true)
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            requestedEndpoints.remove(endpointId)
        }
    }

    fun start() {
        startup.start { generation ->
            isStarted = true
            startAdvertising(generation)
            startDiscovery(generation)
            onTransportStatus("Suche nach freigegebenen Trackern in der Nähe …")
        }
    }

    private fun startAdvertising(generation: Long) {
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(
            sessionId,
            "org.wierichs.dogtracker",
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { failure ->
            startup.failed(generation, "Nearby-Relais konnte nicht gestartet werden (Code ${failure.statusCode()}); erneuter Versuch.") {
                startAdvertising(generation)
            }
        }
    }

    private fun startDiscovery(generation: Long) {
        val strategy = Strategy.P2P_CLUSTER
        client.startDiscovery(
            "org.wierichs.dogtracker",
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { failure ->
            startup.failed(generation, "Nearby-Suche konnte nicht gestartet werden (Code ${failure.statusCode()}); erneuter Versuch.") {
                startDiscovery(generation)
            }
        }
    }

    @Synchronized
    fun stop() {
        startup.stop {
            isStarted = false
            clearOwnPosition()
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
            connectedEndpoints.clear()
            requestedEndpoints.clear()
            discoveredEndpoints.clear()
            sessions.clear()
            pendingDogSignals.values.forEach { it.result.complete(DogSignalResult.UNCONFIRMED) }
            sendWindow.clear()
            cancelRetry()
            sendJob.cancelChildren()
        }
    }

    fun sendPendingToAll() {
        connectedEndpoints.forEach(::sendPendingTo)
    }

    suspend fun signalDog(dogId: String, signal: DogSignal): DogSignalResult {
        val directory = authorization()?.takeIf { isStarted && canPublishPosition() && it.current() }
            ?: return DogSignalResult.UNAVAILABLE
        val target = connectedEndpoints.firstNotNullOfOrNull { endpoint ->
            val session = sessions[endpoint] ?: return@firstNotNullOfOrNull null
            val device = session.authenticatedPeerId(directory) ?: return@firstNotNullOfOrNull null
            if (directory.canSignalDog(localDeviceId, device, dogId)) endpoint to session else null
        } ?: return DogSignalResult.UNAVAILABLE
        val (endpoint, session) = target
        val packet = DogSignalPacket.create(dogId, signal, System.currentTimeMillis())
        val pending = PendingDogSignal(session, dogId)
        pendingDogSignals[packet.id] = pending
        return try {
            if (sessions[endpoint] !== session || !authenticated(session)) return DogSignalResult.UNAVAILABLE
            client.sendPayload(endpoint, Payload.fromBytes(packet.encode(directory.profileId)))
                .addOnFailureListener { pending.result.complete(DogSignalResult.UNCONFIRMED) }
            withTimeoutOrNull(4_000) { pending.result.await() } ?: DogSignalResult.UNCONFIRMED
        } finally {
            pendingDogSignals.remove(packet.id)
        }
    }

    @Synchronized
    fun sendBroadcast(template: BroadcastTemplate) {
        val mailbox = checkNotNull(broadcasts)
        val directory = authorization()
        check(isStarted && canPublishPosition() && directory?.current() == true) {
            "Nearby ist für dieses Revier nicht bereit."
        }
        check(directory.canBroadcast(localDeviceId)) {
            "Nur Owner und Bearbeiter mit aktueller Revierberechtigung können senden. Bitte online erneuern."
        }
        val sender = checkNotNull(directory.subjectId(localDeviceId))
        val targets = connectedEndpoints.mapNotNull { endpoint ->
            val session = sessions[endpoint] ?: return@mapNotNull null
            val device = session.authenticatedPeerId(directory) ?: return@mapNotNull null
            val user = directory.subjectId(device) ?: return@mapNotNull null
            if (directory.deviceKind(device) != "participant" || user == sender) return@mapNotNull null
            Triple(endpoint, session, device to user)
        }.distinctBy { it.third.first }
        val devices = targets.associate { it.third }
        val message = NearbyBroadcast(UUID.randomUUID().toString(), template, sender, System.currentTimeMillis(),
            recipients = directory.participantSubjects() - sender, connected = devices.values.toSet(), outgoing = true)
        mailbox.sent(message, devices)
        val bytes = broadcastPayload(directory.profileId, message)
        targets.forEach { (endpoint, session, _) -> sendBroadcastPayload(endpoint, session, bytes) }
    }

    private fun sendBroadcastPayload(endpoint: String, session: RelayPeerSession, bytes: ByteArray) {
        if (sessions[endpoint] !== session || !authenticated(session)) return
        client.sendPayload(endpoint, Payload.fromBytes(bytes)).addOnFailureListener {
            android.util.Log.w("NearbyRelayTransport", "Nearby-Rundnachricht oder Empfangsbestätigung nicht übertragen.", it)
        }
    }

    @Synchronized
    fun publishPosition(position: OfflinePositionMessage) {
        if (!isStarted) return
        val previous = ownPosition?.first
        if (previous != null && (previous.sequence >= position.sequence ||
                previous.recordedAtEpochMillis > position.recordedAtEpochMillis)) return
        val bytes = position.toByteArray()
        ownPosition = position to NearbyPacket(nearbyPacketDigest(bytes), bytes, receipt = true)
        sendPendingToAll()
    }

    @Synchronized
    fun clearOwnPosition() {
        ownPosition = null
    }

    @Synchronized
    private fun sendPendingTo(endpointId: String) {
        if (!isStarted) return
        val session = sessions[endpointId] ?: return
        val directory = authorization()
        val peerId = directory?.let(session::authenticatedPeerId)
        if (directory == null || peerId == null) {
            disconnect(endpointId, "Relay-Berechtigung abgelaufen oder widerrufen.")
            return
        }
        val packets = if (!regularTrafficAllowed()) emptyList() else pendingPackets?.invoke() ?: run {
            val messages = SqliteMessageStore(appContext).use { store ->
                (store.newestPendingOfType(RelayEnvelope.OUTGOING_MESSAGE_TYPE, MAX_MESSAGES_PER_SYNC) +
                    store.newestPendingOfType(RelayEnvelope.MESSAGE_TYPE, MAX_MESSAGES_PER_SYNC))
                    .sortedByDescending { it.localId }
            }
            messages.asSequence().mapNotNull { stored ->
                val envelope = RelayEnvelope.parse(stored.message.payload) ?: return@mapNotNull null
                if (!envelope.originDeviceId.equals(localDeviceId, ignoreCase = true)) return@mapNotNull null
                NearbyPacket(envelope.messageId, stored.message.payload)
            }.take(MAX_MESSAGES_PER_SYNC).toList()
        }
        val positionPacket = ownPosition?.takeIf { (position, _) ->
            regularTrafficAllowed() &&
            directory.deviceKind(peerId) == "participant" && directory.subjectId(peerId) != null &&
                position.profileId == directory.profileId &&
                position.senderDeviceId == localDeviceId &&
                position.subjectId == directory.subjectId(localDeviceId) && canPublishPosition()
        }?.second
        val endPacket = huntingEndPacket().takeIf { directory.deviceKind(peerId) == "participant" }
        val validPackets = (listOfNotNull(endPacket, positionPacket) + packets).filter { it.bytes.size <= 8192 }
        val selected = sendWindow.select(endpointId, validPackets, SystemClock.elapsedRealtime())
        selected.forEach { attempt ->
            sendPacket(endpointId, session, attempt)
        }
        if (sendWindow.needsRetry(endpointId, validPackets)) scheduleRetry()
    }

    private fun sendPacket(endpointId: String, session: RelayPeerSession, attempt: NearbySendAttempt) {
        if (!attempt.packet.huntingEnd && !regularTrafficAllowed()) return
        if (sessions[endpointId] !== session || !authenticated(session)) {
            sendWindow.failed(endpointId, attempt)
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(attempt.packet.bytes)).addOnFailureListener {
            sendWindow.failed(endpointId, attempt)
            onTransportStatus("Relay-Nachricht konnte nicht übergeben werden.")
        }
    }

    companion object {
        private const val MAX_MESSAGES_PER_SYNC = 50
        private const val MAX_SESSIONS = 16
        private val RETRY_CHECK_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(10)
    }

    private fun sendAuthentication(endpointId: String, session: RelayPeerSession, action: String, recipient: String) {
        val bytes = try {
            RelayPeerAuthentication.create(action, localDeviceId, recipient, session.token, RelaySigningIdentity::sign)
        } catch (_: Exception) {
            disconnect(endpointId, "Relay-Authentifizierung konnte nicht signiert werden.")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).addOnFailureListener {
            disconnect(endpointId, "Relay-Authentifizierung konnte nicht übertragen werden.")
        }
    }

    private fun receiveAuthentication(endpointId: String, session: RelayPeerSession, action: String, bytes: ByteArray) {
        val directory = authorization()
        val update = directory?.let { session.receive(action, bytes, it) }
        if (update == null) {
            disconnect(endpointId, "Relay-Authentifizierung oder Revierberechtigung ungültig.")
            return
        }
        if (update.reply) sendAuthentication(endpointId, session, "ready", update.peerId)
        if (update.becameAuthenticated && sessions[endpointId] === session && authenticated(session)) {
            connectedEndpoints.add(endpointId)
            onTransportStatus("Relay-Gerät authentifiziert; Nachrichten werden übertragen.")
            session.drain().forEach { bytes ->
                if (authenticated(session)) deliver(endpointId, session, bytes)
                else disconnect(endpointId, "Relay-Berechtigung abgelaufen oder widerrufen.")
            }
            sendScope.launch { sendPendingTo(endpointId) }
        }
    }

    private fun deliver(endpointId: String, session: RelayPeerSession, bytes: ByteArray) {
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: org.json.JSONException) {
            null
        }
        val action = json?.optString("action")
        if (action == HUNT_END_NEARBY_ACTION) {
            val directory = authorization() ?: return
            val peer = session.authenticatedPeerId(directory) ?: return
            if (sessions[endpointId] !== session || directory.deviceKind(peer) != "participant") return
            val document = json.opt("signed_end") as? String ?: return
            onHuntingEnd?.invoke(document.toByteArray(Charsets.UTF_8)) { acknowledgeReceipt(endpointId, session, bytes) }
            return
        }
        if (!regularTrafficAllowed() && action != "peer_receipt_ack") return
        if (action in setOf(DOG_SIGNAL_ACTION, DOG_SIGNAL_ACK)) {
            receiveDogSignal(endpointId, session, checkNotNull(json))
            return
        }
        if (json?.optString("action") in setOf(BROADCAST_ACTION, BROADCAST_ACK_ACTION)) {
            receiveBroadcast(endpointId, session, checkNotNull(json))
            return
        }
        if (json?.optString("action") == "peer_receipt_ack") {
            val digest = json.optString("digest")
            if (sessions[endpointId] !== session) return
            // This acknowledges only receipt delivery to this peer, not server acceptance.
            if (Regex("^[A-Za-z0-9_-]{43}$").matches(digest)) {
                sendWindow.acknowledge(endpointId, digest)
            }
            else onTransportStatus("Relay-Bestätigung für Funkübergabe ungültig.")
            return
        }
        if (json?.optString("action") == OfflinePositionMessage.ACTION) {
            val receive = onIncomingPosition ?: return
            val directory = authorization() ?: return
            if (sessions[endpointId] !== session) return
            val peerId = session.authenticatedPeerId(directory) ?: return
            val position = OfflinePositionMessage.fromPeer(bytes, peerId, directory)
            if (position == null) {
                android.util.Log.w("NearbyRelayTransport", "Ungueltige Offline-Position verworfen.")
                return
            }
            receive(position) { acknowledgeReceipt(endpointId, session, bytes) }
            return
        }
        val envelope = RelayEnvelope.parse(bytes)
        if (envelope != null) onIncomingEnvelope(envelope)
        else onIncomingDocument(bytes) { acknowledgeReceipt(endpointId, session, bytes) }
    }

    @Synchronized
    private fun receiveDogSignal(endpointId: String, session: RelayPeerSession, json: JSONObject) {
        val directory = authorization() ?: return
        if (sessions[endpointId] !== session || json.optString("profile_id") != directory.profileId) return
        val peer = session.authenticatedPeerId(directory) ?: return
        if (json.optString("action") == DOG_SIGNAL_ACK) {
            val pending = broadcastId(json)?.let(pendingDogSignals::get) ?: return
            if (pending.session !== session || !directory.canSignalDog(localDeviceId, peer, pending.dogId)) return
            val result = when (json.optString("result")) {
                "accepted" -> DogSignalResult.CONFIRMED
                "suppressed" -> DogSignalResult.SUPPRESSED
                else -> return
            }
            pending.result.complete(result)
            return
        }
        val notify = onDogSignal ?: return
        val packet = DogSignalPacket.parse(json, System.currentTimeMillis())
        if (packet == null || !directory.canSignalDog(peer, localDeviceId, packet.dogId)) {
            android.util.Log.w("NearbyRelayTransport", "Ungültiges oder nicht berechtigtes Hundesignal verworfen.")
            return
        }
        val elapsed = SystemClock.elapsedRealtime()
        recentDogSignals.entries.removeAll { elapsed - it.value > 15_000L }
        if (packet.id in recentDogSignals || recentDogSignals.size >= 32) return
        recentDogSignals[packet.id] = elapsed
        if (elapsed - lastDogSignal < 5_000L) {
            acknowledgeDogSignal(endpointId, session, directory.profileId, packet.id, false)
            return
        }
        lastDogSignal = elapsed
        acknowledgeDogSignal(endpointId, session, directory.profileId, packet.id, notify(packet.signal))
    }

    private fun acknowledgeDogSignal(endpointId: String, session: RelayPeerSession, profileId: String,
        id: String, accepted: Boolean) {
        val bytes = JSONObject().put("action", DOG_SIGNAL_ACK).put("profile_id", profileId).put("id", id)
            .put("result", if (accepted) "accepted" else "suppressed").toString().toByteArray(Charsets.UTF_8)
        sendBroadcastPayload(endpointId, session, bytes)
    }

    private fun receiveBroadcast(endpointId: String, session: RelayPeerSession, json: JSONObject) {
        val mailbox = broadcasts ?: return
        val directory = authorization() ?: return
        if (sessions[endpointId] !== session || !canPublishPosition()) return
        val device = session.authenticatedPeerId(directory) ?: return
        val user = directory.subjectId(device) ?: return
        if (directory.deviceKind(device) != "participant" || user == directory.subjectId(localDeviceId)) return
        if (json.optString("profile_id") != directory.profileId) return
        if (json.optString("action") == BROADCAST_ACK_ACTION) {
            broadcastId(json)?.let { mailbox.confirm(it, device, user) }
            return
        }
        val now = System.currentTimeMillis()
        val message = parseNearbyBroadcast(json, directory.profileId, user, now)
        if (!directory.canBroadcast(device) || message == null) {
            android.util.Log.w("NearbyRelayTransport", "Ungültige oder nicht berechtigte Nearby-Rundnachricht verworfen.")
            return
        }
        if (mailbox.receive(device, message, now)) {
            sendBroadcastPayload(endpointId, session, broadcastAck(directory.profileId, message.id))
            NearbyBroadcastNotifications.show(appContext, device, message)
        }
    }

    private fun acknowledgeReceipt(endpointId: String, session: RelayPeerSession, bytes: ByteArray) {
        if (sessions[endpointId] !== session || !authenticated(session)) return
        val ack = JSONObject().put("action", "peer_receipt_ack").put("digest", nearbyPacketDigest(bytes))
            .toString().toByteArray(Charsets.UTF_8)
        client.sendPayload(endpointId, Payload.fromBytes(ack)).addOnFailureListener {
            onTransportStatus("Relay-Bestätigung konnte nicht übergeben werden.")
        }
    }

    private fun authenticated(session: RelayPeerSession): Boolean {
        val directory = authorization() ?: return false
        return session.authenticated(directory)
    }

    private fun disconnect(endpointId: String, status: String) {
        sessions.remove(endpointId)
        connectedEndpoints.remove(endpointId)
        requestedEndpoints.remove(endpointId)
        sendWindow.forgetEndpoint(endpointId)
        if (connectedEndpoints.isEmpty()) cancelRetry()
        client.disconnectFromEndpoint(endpointId)
        onTransportStatus(status)
        retryConnection(endpointId)
    }

    private fun requestConnection(endpointId: String) {
        val name = discoveredEndpoints[endpointId] ?: return
        if (!isStarted || sessionId == name || sessions.containsKey(endpointId) ||
            !requestedEndpoints.add(endpointId)
        ) return
        client.requestConnection(sessionId, endpointId, lifecycleCallback).addOnFailureListener {
            requestedEndpoints.remove(endpointId)
            onTransportStatus("Relay-Verbindung konnte nicht angefragt werden.")
            retryConnection(endpointId)
        }
    }

    private fun retryConnection(endpointId: String) {
        scheduleConnection(endpointId, firstAttempt = false)
    }

    private fun scheduleConnection(endpointId: String, firstAttempt: Boolean) {
        val name = discoveredEndpoints[endpointId] ?: return
        val delayMillis = nearbyConnectionDelayMillis(sessionId, name, firstAttempt)
        if (delayMillis == 0L) requestConnection(endpointId)
        else handler.postDelayed({ requestConnection(endpointId) }, delayMillis)
    }

    private fun scheduleRetry() {
        if (isStarted && connectedEndpoints.isNotEmpty() && retryScheduled.compareAndSet(false, true) &&
            !handler.postDelayed(retryPending, RETRY_CHECK_INTERVAL_MILLIS)
        ) {
            retryScheduled.set(false)
            onTransportStatus("Relay-Wiederholung konnte nicht eingeplant werden.")
        }
    }

    private fun cancelRetry() {
        handler.removeCallbacks(retryPending)
        retryScheduled.set(false)
    }

    private fun Exception.statusCode(): String = (this as? ApiException)?.statusCode?.toString()
        ?: javaClass.simpleName
}

internal fun nearbyPacketDigest(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

internal const val HUNT_END_NEARBY_ACTION = "server_hunt_end"

internal data class NearbyPacket(val id: String, val bytes: ByteArray, val receipt: Boolean = false,
    val huntingEnd: Boolean = false)
internal data class NearbySendAttempt(val packet: NearbyPacket, val generation: Long)

internal class NearbySendWindow(
    private val retryMillis: Long = TimeUnit.SECONDS.toMillis(30),
    private val batchSize: Int = 50
) {
    private data class Sent(val at: Long, val generation: Long, val receipt: Boolean, val acknowledged: Boolean = false)
    private val endpoints = mutableMapOf<String, MutableMap<String, Sent>>()
    private var generation = 0L

    @Synchronized
    fun select(endpointId: String, packets: List<NearbyPacket>, now: Long): List<NearbySendAttempt> {
        val sent = endpoints.getOrPut(endpointId) { mutableMapOf() }
        val distinct = packets.distinctBy { it.id }
        val available = distinct.map { it.id }.toSet()
        sent.keys.retainAll(available)
        return distinct.asSequence()
            .filter { packet -> sent[packet.id]?.let { !it.acknowledged && (now < it.at || now - it.at >= retryMillis) } ?: true }
            .sortedBy { sent[it.id]?.at ?: Long.MIN_VALUE }
            .take(batchSize)
            .map { packet ->
                val attempt = NearbySendAttempt(packet, ++generation)
                sent[packet.id] = Sent(now, attempt.generation, packet.receipt)
                attempt
            }.toList()
    }

    @Synchronized
    fun failed(endpointId: String, attempt: NearbySendAttempt) {
        val sent = endpoints[endpointId] ?: return
        val current = sent[attempt.packet.id]
        if (current?.generation == attempt.generation && !current.acknowledged) sent.remove(attempt.packet.id)
    }

    @Synchronized
    fun acknowledge(endpointId: String, id: String) {
        val sent = endpoints[endpointId] ?: return
        val attempt = sent[id] ?: return
        if (attempt.receipt) sent[id] = attempt.copy(acknowledged = true)
    }

    @Synchronized
    fun needsRetry(endpointId: String, packets: List<NearbyPacket>): Boolean {
        val sent = endpoints[endpointId]
        return packets.any { sent?.get(it.id)?.acknowledged != true }
    }

    @Synchronized
    fun forgetEndpoint(endpointId: String) { endpoints.remove(endpointId) }

    @Synchronized
    fun clear() { endpoints.clear() }
}

internal class NearbyStartupRetry(
    private val schedule: (() -> Unit) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private var generation = 0L
    private var active = false

    @Synchronized
    fun start(begin: (Long) -> Unit) {
        if (active) return
        active = true
        begin(++generation)
    }

    @Synchronized
    fun stop(end: () -> Unit) {
        if (!active) return
        active = false
        end()
    }

    @Synchronized
    fun failed(attempt: Long, status: String, retry: () -> Unit) {
        if (!active || attempt != generation) return
        onFailure(status)
        schedule { retryIfCurrent(attempt, retry) }
    }

    @Synchronized
    private fun retryIfCurrent(attempt: Long, retry: () -> Unit) {
        if (active && attempt == generation) retry()
    }
}

internal fun nearbyConnectionDelayMillis(localSessionId: String, remoteSessionId: String, firstAttempt: Boolean): Long =
    when {
        localSessionId < remoteSessionId && firstAttempt -> 0L
        localSessionId < remoteSessionId -> TimeUnit.SECONDS.toMillis(10)
        else -> TimeUnit.SECONDS.toMillis(20)
    }