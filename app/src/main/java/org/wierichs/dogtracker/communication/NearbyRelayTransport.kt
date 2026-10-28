package org.wierichs.dogtracker.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

internal data class RelayPairingPrompt(
    val endpointId: String,
    val endpointName: String,
    val authenticationDigits: String,
    internal val resolve: (Boolean) -> Unit
)

internal object RelayPairingPrompts {
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    val current = MutableStateFlow<RelayPairingPrompt?>(null)

    @Synchronized
    fun offer(prompt: RelayPairingPrompt): Boolean {
        if (current.value != null) return false
        current.value = prompt
        timeout = Runnable { respond(prompt.endpointId, accept = false) }.also {
            handler.postDelayed(it, TimeUnit.SECONDS.toMillis(45))
        }
        return true
    }

    @Synchronized
    fun respond(endpointId: String, accept: Boolean) {
        val prompt = current.value?.takeIf { it.endpointId == endpointId } ?: return
        timeout?.let(handler::removeCallbacks)
        timeout = null
        current.value = null
        prompt.resolve(accept)
    }

    @Synchronized
    fun clear() {
        current.value?.let { respond(it.endpointId, accept = false) }
    }
}

internal class NearbyRelayTransport(
    context: Context,
    private val localDeviceId: String,
    private val onIncomingEnvelope: (RelayEnvelope) -> Unit,
    private val onTransportStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val sessionId = UUID.randomUUID().toString()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val requestedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val sentMessageIds = ConcurrentHashMap.newKeySet<String>()
    private var isStarted = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            RelayEnvelope.parse(bytes)?.let(onIncomingEnvelope)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            requestedEndpoints.add(endpointId)
            val prompt = RelayPairingPrompt(
                endpointId = endpointId,
                endpointName = info.endpointName,
                authenticationDigits = info.authenticationDigits
            ) { accept ->
                if (accept) {
                    client.acceptConnection(endpointId, payloadCallback)
                } else {
                    client.rejectConnection(endpointId)
                }
            }
            if (!RelayPairingPrompts.offer(prompt)) client.rejectConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            requestedEndpoints.remove(endpointId)
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoints.add(endpointId)
                onTransportStatus("Tracker verbunden; Relay-Nachrichten werden übertragen.")
                sendPendingTo(endpointId)
            } else {
                onTransportStatus("Tracker-Verbindung wurde nicht hergestellt.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            requestedEndpoints.remove(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!requestedEndpoints.add(endpointId)) return
            if (sessionId < info.endpointName) {
                client.requestConnection(sessionId, endpointId, lifecycleCallback)
                    .addOnFailureListener {
                        requestedEndpoints.remove(endpointId)
                        onTransportStatus("Tracker-Verbindung konnte nicht angefragt werden.")
                    }
            } else {
                requestedEndpoints.remove(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            requestedEndpoints.remove(endpointId)
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(
            sessionId,
            appContext.packageName,
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener {
            onTransportStatus("Nearby-Relais konnte nicht gestartet werden.")
        }
        client.startDiscovery(
            appContext.packageName,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener {
            onTransportStatus("Nearby-Suche konnte nicht gestartet werden.")
        }
        onTransportStatus("Suche nach freigegebenen Trackern in der Nähe …")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        RelayPairingPrompts.clear()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connectedEndpoints.clear()
        requestedEndpoints.clear()
        sentMessageIds.clear()
    }

    fun sendPendingToAll() {
        connectedEndpoints.forEach(::sendPendingTo)
    }

    private fun sendPendingTo(endpointId: String) {
        val messages = SqliteMessageStore(appContext).use { store ->
            store.pendingOfType(RelayEnvelope.MESSAGE_TYPE, MAX_MESSAGES_PER_SYNC)
        }
        messages.forEach { stored ->
            val envelope = RelayEnvelope.parse(stored.message.payload) ?: return@forEach
            if (envelope.originDeviceId != localDeviceId || !sentMessageIds.add(envelope.messageId)) return@forEach
            client.sendPayload(endpointId, Payload.fromBytes(stored.message.payload))
                .addOnFailureListener {
                    sentMessageIds.remove(envelope.messageId)
                    onTransportStatus("Relay-Nachricht konnte nicht übergeben werden.")
                }
        }
    }

    companion object {
        private const val MAX_MESSAGES_PER_SYNC = 50
    }
}