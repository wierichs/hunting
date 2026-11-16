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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.wierichs.dogtracker.communication.RelayPeerAuthentication.RelayPeerSession

internal class NearbyRelayTransport(
    context: Context,
    private val localDeviceId: String,
    private val authorization: () -> VerifiedRelayDirectory?,
    private val onIncomingEnvelope: (RelayEnvelope) -> Unit,
    private val onTransportStatus: (String) -> Unit,
    private val pendingPackets: (() -> List<NearbyPacket>)? = null,
    private val onIncomingDocument: (ByteArray, () -> Unit) -> Unit = { _, _ -> }
) {
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val sessionId = UUID.randomUUID().toString()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val requestedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val discoveredEndpoints = ConcurrentHashMap<String, String>()
    private val sendWindow = NearbySendWindow()
    private val sessions = ConcurrentHashMap<String, RelayPeerSession>()
    private val handler = Handler(Looper.getMainLooper())
    private val sendJob = SupervisorJob()
    private val sendScope = CoroutineScope(sendJob + Dispatchers.IO)
    private val retryScheduled = AtomicBoolean(false)
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

    fun stop() {
        startup.stop {
            isStarted = false
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
            connectedEndpoints.clear()
            requestedEndpoints.clear()
            discoveredEndpoints.clear()
            sessions.clear()
            sendWindow.clear()
            cancelRetry()
            sendJob.cancelChildren()
        }
    }

    fun sendPendingToAll() {
        connectedEndpoints.forEach(::sendPendingTo)
    }

    @Synchronized
    private fun sendPendingTo(endpointId: String) {
        if (!isStarted) return
        val session = sessions[endpointId] ?: return
        if (!authenticated(session)) {
            disconnect(endpointId, "Relay-Berechtigung abgelaufen oder widerrufen.")
            return
        }
        val packets = pendingPackets?.invoke() ?: run {
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
        val validPackets = packets.filter { it.bytes.size <= 8192 }
        val selected = sendWindow.select(endpointId, validPackets, SystemClock.elapsedRealtime())
        selected.forEach { attempt ->
            sendPacket(endpointId, session, attempt)
        }
        if (sendWindow.needsRetry(endpointId, validPackets)) scheduleRetry()
    }

    private fun sendPacket(endpointId: String, session: RelayPeerSession, attempt: NearbySendAttempt) {
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
        val envelope = RelayEnvelope.parse(bytes)
        if (envelope != null) onIncomingEnvelope(envelope)
        else onIncomingDocument(bytes) { acknowledgeReceipt(endpointId, session, bytes) }
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

internal data class NearbyPacket(val id: String, val bytes: ByteArray, val receipt: Boolean = false)
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