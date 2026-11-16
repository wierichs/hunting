package org.wierichs.dogtracker.communication

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.net.ssl.SSLException
import org.json.JSONObject
import org.wierichs.dogtracker.jagdkarte.HuntingProfile
import org.wierichs.dogtracker.jagdkarte.JagdkarteApiException
import org.wierichs.dogtracker.jagdkarte.JagdkarteRepository
import org.wierichs.dogtracker.jagdkarte.locationInsideBoundary
import org.wierichs.dogtracker.BackendConfiguration
import org.wierichs.dogtracker.DeviceTokenStorage
import org.wierichs.dogtracker.TrackerPreferences
import org.wierichs.dogtracker.readHuntingPermissions

internal data class RelayGatewayStatus(
    val active: Boolean = false,
    val starting: Boolean = false,
    val profileId: String? = null,
    val status: String = "",
    val pending: Int = 0,
    val confirmed: Int = 0
)

internal object RelayGateway {
    val state = MutableStateFlow(RelayGatewayStatus())
    @Volatile internal var engine: RelayGatewayEngine? = null
    @Volatile private var service: RelayGatewayService? = null
    internal val shutdown = RelayGatewayShutdown()

    @Synchronized
    fun start(context: Context, profileId: String) {
        require(UUID.fromString(profileId).toString() == profileId)
        check(service == null && !state.value.active && !state.value.starting) {
            "Relaisdienst ist bereits aktiv oder wird beendet."
        }
        shutdown.start()
        state.value = RelayGatewayStatus(starting = true, profileId = profileId, status = "Relaisfreigabe wird eingerichtet.")
        try {
            ContextCompat.startForegroundService(context, Intent(context, RelayGatewayService::class.java)
                .setAction(RelayGatewayService.ACTION_START).putExtra(RelayGatewayService.EXTRA_PROFILE_ID, profileId))
        } catch (failure: Exception) {
            state.value = RelayGatewayStatus(profileId = profileId, status = "Relaisdienst konnte nicht gestartet werden.")
            throw failure
        }
    }

    @Synchronized
    fun stop(context: Context) {
        shutdown.requestStop()
        state.value = state.value.copy(starting = true, status = "Relaisfreigabe wird beendet.")
        if (!context.stopService(Intent(context, RelayGatewayService::class.java)) && engine == null && service == null) {
            state.value = RelayGatewayStatus(profileId = state.value.profileId, status = "Relaisfreigabe beendet.")
        }
    }

    @Synchronized
    internal fun serviceCreated(instance: RelayGatewayService) { service = instance }

    @Synchronized
    internal fun serviceDestroyed(instance: RelayGatewayService, current: RelayGatewayEngine?, status: RelayGatewayStatus) {
        if (service !== instance) return
        if (engine === current) engine = null
        service = null
        state.value = status
    }

    suspend fun stopAndAwait(context: Context) {
        try {
            withTimeout(60_000) {
                val current = engine
                stop(context)
                current?.cancelOwn()
                current?.close()
                state.first { !it.active && !it.starting }
            }
        } catch (failure: TimeoutCancellationException) {
            throw IOException("Der Relaisdienst konnte nicht rechtzeitig beendet werden. Bitte erneut versuchen.", failure)
        }
    }

    suspend fun enqueue(profileId: String, location: Location): Boolean {
        val current = engine?.takeIf { state.value.active && it.profileId == profileId } ?: return false
        current.enqueueLocation(location)
        return true
    }

    suspend fun cancelOwn(profileId: String) {
        engine?.takeIf { it.profileId == profileId }?.cancelOwn()
    }
}

internal class RelayGatewayEngine(
    context: Context,
    private val repository: JagdkarteRepository,
    private val profile: HuntingProfile,
    private val onIncoming: (RelayEnvelope) -> Unit,
    private val onDocument: (ByteArray, () -> Unit) -> Unit
) {
    val profileId: String get() = profile.id
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var capabilities: RelayCapabilities? = null
    @Volatile private var directory: VerifiedRelayDirectory? = null
    private var deviceId: String? = null
    private var store: SqliteMessageStore? = null
    private var transport: NearbyRelayTransport? = null
    private var nextRefresh = 0L
    private var closed = false
    private var sharingEpoch: String? = null
    private var sharingStopPending = false
    @Volatile private var outgoing = emptyList<NearbyPacket>()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val offline = try {
                if (!hasValidatedRelayInternet(appContext)) throw IOException("Keine Internetverbindung.")
                refreshAuthorization()
                false
            } catch (failure: IOException) {
                if (failure is SSLException) throw failure
                val saved = RelayOfflineAuthorization.load(appContext, AUTHORIZATION_FILE, repository.userId, null, profileId)
                    ?: throw IOException("Offline-Relaisberechtigung fehlt oder ist abgelaufen; bitte online erneuern.", failure)
                capabilities = saved.capabilities
                directory = saved.directory
                deviceId = saved.directory.localDeviceId
                nextRefresh = SystemClock.elapsedRealtime() + 10_000
                true
            }
            val id = deviceId!!
            store = SqliteMessageStore(appContext, "relay_gateway_$id.db")
            store!!.removeExpired()
            purgeOwn()
            transport = NearbyRelayTransport(appContext, id, { directory }, onIncoming, { status -> update(status) },
                pendingPackets = { outgoing }, onIncomingDocument = onDocument)
            refreshOutgoing()
            transport!!.start()
            RelayGateway.state.value = RelayGatewayStatus(active = true, profileId = profileId,
                status = if (offline) "Offline-Relaisfreigabe aktiv; Servererneuerung ausstehend."
                    else "Relaisfreigabe aktiv; bereit fuer signierte Reviermeldungen.", pending = pendingCount())
        }
    }

    private suspend fun refreshAuthorization() {
        val capability = RelayCapabilities.parse(repository.relayPost(JSONObject().put("action", "capabilities")))
        val registration = repository.relayPost(JSONObject().put("action", "register_offline").put("profile_id", profileId)
            .put("public_key", RelaySigningIdentity.publicKeyBase64Url()))
        val id = registration.getString("device_id")
        require(UUID.fromString(id).toString() == id)
        if (deviceId != null && deviceId != id) {
            throw JagdkarteApiException(403, "relay_identity_changed", "Relaisgeraet wurde erneut registriert.")
        }
        if (deviceId == null) repository.relayPost(JSONObject().put("action", "set_sharing").put("device_id", id).put("enabled", false))
        val (boot, requestElapsed) = RelayOfflineLease.requestStarted(appContext)
        val response = repository.relayPost(JSONObject().put("action", "offline_directory").put("device_id", id))
        val lease = RelayOfflineLease(boot, requestElapsed, System.currentTimeMillis())
        val verified = RelayOfflineAuthorization.verify(appContext, capability, response, id, profileId, lease)
        require(repository.userId.isNotBlank())
        RelayOfflineAuthorization.save(appContext, AUTHORIZATION_FILE, repository.userId, id, capability, response, lease)
        capabilities = capability
        directory = verified.directory
        deviceId = id
        nextRefresh = SystemClock.elapsedRealtime() + 60_000
    }

    suspend fun enqueueLocation(location: Location) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(readHuntingPermissions(appContext).ready) {
                "Jagdfreigabe oder erforderliche Berechtigung wurde entzogen."
            }
            check(!closed && directory?.current() == true) { "Relaisberechtigung ist abgelaufen. Online-Verbindung erforderlich." }
            check(hasValidatedRelayInternet(appContext)) { "Teilnehmerstandortfreigabe erfordert Internet." }
            require(locationInsideBoundary(profile.boundary, location.latitude, location.longitude)) { "Standort ausserhalb des Reviers." }
            val now = System.currentTimeMillis()
            require(now - location.time in -30_000L..30_000L)
            val id = deviceId!!
            if (sharingStopPending) stopSharingOnServer()
            if (sharingEpoch == null) {
                val response = repository.relayPost(JSONObject().put("action", "set_sharing").put("device_id", id).put("enabled", true))
                sharingEpoch = response.getString("sharing_epoch")
            }
            val preferences = appContext.getSharedPreferences("relay_sequences", Context.MODE_PRIVATE)
            val sequence = Math.addExact(preferences.getLong(id, 0), 1)
            check(preferences.edit().putLong(id, sequence).commit()) { "Sequenz konnte nicht dauerhaft gespeichert werden." }
            val payload = participantLocationPayload(profileId, sequence, location.latitude, location.longitude, location.accuracy, location.time, sharingEpoch!!)
            val envelope = RelayEnvelopeEncoder.create(id, payload, capabilities!!.encryption, RelaySigningIdentity::sign,
                nowEpochMillis = location.time, lifetimeMillis = 90_000)
            val database = store!!
            database.removeExpired()
            check(pendingCount() < MAX_PENDING) { "Relaiswarteschlange ist voll." }
            database.enqueue(envelope.toStoredMessage())
            refreshOutgoing()
            update("Eigene Position lokal gespeichert; Serverbestaetigung ausstehend.")
            transport?.sendPendingToAll()
        }
    }

    suspend fun acceptEnvelope(envelope: RelayEnvelope) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || directory?.accepts(envelope) != true) return@withLock
            val database = store ?: return@withLock
            if (database.findPending(relayStorageId(envelope.originDeviceId, envelope.messageId)) != null ||
                database.findPending(receiptId(envelope.originDeviceId, envelope.messageId)) != null) return@withLock
            database.removeExpired()
            if (pendingCount() >= MAX_PENDING) { update("Relaiswarteschlange voll; weitere Meldung nicht angenommen."); return@withLock }
            database.enqueue(envelope.toStoredMessage())
            refreshOutgoing()
            update("Reviermeldung dauerhaft gespeichert; Serverbestaetigung ausstehend.")
        }
    }

    suspend fun acceptDocument(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock false
            val receipt = try {
                VerifiedRelayReceipt.verify(JSONObject(String(bytes, Charsets.UTF_8)), capabilities!!, profileId)
            } catch (_: Exception) {
                update("Relay-Bestätigung konnte nicht geprüft werden.")
                return@withLock false
            }
            applyReceipt(receipt)
            true
        }
    }

    private fun applyReceipt(receipt: VerifiedRelayReceipt) {
        val database = store ?: return
        val storageId = relayStorageId(receipt.originDeviceId, receipt.messageId)
        val pending = database.findPending(storageId) ?: return
        val envelope = RelayEnvelope.parse(pending.message.payload) ?: return
        if (!receipt.matches(envelope)) return
        database.acknowledgeWithReceipt(storageId, CommunicationMessage(
            receiptId(receipt.originDeviceId, receipt.messageId), RECEIPT_TYPE, ENDPOINT, System.currentTimeMillis(), receipt.expiresAt, receipt.bytes))
        RelayGateway.state.value = RelayGateway.state.value.copy(confirmed = RelayGateway.state.value.confirmed + 1)
        refreshOutgoing()
        update("Positionsmeldung vom Server bestaetigt.")
    }

    suspend fun synchronize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            store!!.removeExpired()
            if (!hasValidatedRelayInternet(appContext)) {
                if (directory?.current() != true) {
                    outgoing = emptyList()
                    transport?.stop()
                    update("Relais pausiert: Revierberechtigung muss online erneuert werden.")
                } else {
                    refreshOutgoing()
                    transport?.sendPendingToAll()
                }
                return@withLock
            }
            if (SystemClock.elapsedRealtime() >= nextRefresh) {
                try { refreshAuthorization(); transport?.start() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure is JagdkarteApiException && failure.status in setOf(401, 403, 409)) throw failure
                    nextRefresh = SystemClock.elapsedRealtime() + 10_000
                    if (directory?.current() != true) {
                        outgoing = emptyList()
                        transport?.stop()
                        update("Relais pausiert: Revierberechtigung muss online erneuert werden.")
                        return@withLock
                    }
                }
                if (directory?.current() != true) {
                    outgoing = emptyList()
                    transport?.stop()
                    update("Relais pausiert: Revierberechtigung muss online erneuert werden.")
                    return@withLock
                }
            }
            val id = deviceId!!
            try {
                if (sharingStopPending) stopSharingOnServer()
                val receipts = repository.relayPost(JSONObject().put("action", "receipts").put("device_id", id)).getJSONArray("receipts")
                for (index in 0 until receipts.length()) {
                    applyReceipt(VerifiedRelayReceipt.verify(receipts.getJSONObject(index), capabilities!!, profileId))
                }
                val pending = store!!.pendingOfType(RelayEnvelope.MESSAGE_TYPE, 5)
                for (stored in pending) {
                    val envelope = RelayEnvelope.parse(stored.message.payload) ?: continue
                    if (directory?.accepts(envelope) != true) continue
                    try {
                        val json = repository.relayPost(JSONObject().put("action", "relay").put("device_id", id).put("envelope", envelope.toJson()))
                        applyReceipt(VerifiedRelayReceipt.verify(json, capabilities!!, profileId))
                    } catch (failure: JagdkarteApiException) {
                        if (failure.status in setOf(400, 410) || failure.errorCode in setOf("origin_not_authorized", "invalid_origin_signature", "relay_not_allowed")) {
                            store!!.acknowledge(stored.message.messageId)
                            update("Meldung vom Server abgelehnt und verworfen; nicht zugestellt.")
                        } else throw failure
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is JagdkarteApiException && failure.status in setOf(401, 403, 409)) throw failure
                update("Kein bestaetigter Upload; Meldungen bleiben bis zum Ablauf gespeichert.")
            }
            refreshOutgoing()
            transport?.sendPendingToAll()
        }
    }

    suspend fun cancelOwn() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || store == null) return@withLock
            sharingEpoch = null
            sharingStopPending = true
            purgeOwn()
            refreshOutgoing()
            if (!hasValidatedRelayInternet(appContext)) {
                update("Standortfreigabe lokal beendet; Serverwiderruf wartet auf Verbindung.")
                return@withLock
            }
            try { stopSharingOnServer() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update("Standortfreigabe lokal beendet; Serverwiderruf wartet auf Verbindung.") }
        }
    }

    private fun purgeOwn() {
        val database = store ?: return
        database.pendingOfType(RelayEnvelope.MESSAGE_TYPE, MAX_PENDING).forEach { pending ->
            if (RelayEnvelope.parse(pending.message.payload)?.originDeviceId == deviceId) database.acknowledge(pending.message.messageId)
        }
    }

    private suspend fun stopSharingOnServer() {
        repository.relayPost(JSONObject().put("action", "set_sharing").put("device_id", deviceId!!).put("enabled", false))
        sharingStopPending = false
    }

    suspend fun close(revoke: Boolean = true) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            outgoing = emptyList()
            transport?.stop()
            store?.close()
            store = null
            if (revoke) clearAuthorization(appContext)
            cleanupGatewayRegistration(deviceId, revoke,
                revokeDevice = { id -> repository.relayPost(JSONObject().put("action", "revoke").put("device_id", id)) },
                deleteDatabase = { id -> appContext.deleteDatabase("relay_gateway_$id.db") })
        }
    }

    private fun refreshOutgoing() {
        val database = store ?: return
        database.removeExpired()
        val receipts = database.pendingOfType(RECEIPT_TYPE, MAX_PENDING + 1)
        receipts.take((receipts.size - MAX_PENDING).coerceAtLeast(0)).forEach { database.acknowledge(it.message.messageId) }
        outgoing = if (directory?.current() == true) {
            val own = database.pendingOfType(RelayEnvelope.MESSAGE_TYPE, MAX_PENDING)
                .filter { RelayEnvelope.parse(it.message.payload)?.originDeviceId == deviceId }.take(25).map { it.message.payload }
            own.map { NearbyPacket(nearbyPacketDigest(it), it) } +
                database.newestPendingOfType(RECEIPT_TYPE, MAX_PENDING).map {
                    NearbyPacket(nearbyPacketDigest(it.message.payload), it.message.payload, receipt = true)
                }
        } else emptyList()
        RelayGateway.state.value = RelayGateway.state.value.copy(pending = pendingCount())
    }

    private fun pendingCount(): Int = store?.pendingOfType(RelayEnvelope.MESSAGE_TYPE, MAX_PENDING)?.size ?: 0

    private fun update(status: String) {
        RelayGateway.state.value = RelayGateway.state.value.copy(status = status)
    }

    private fun RelayEnvelope.toStoredMessage() = CommunicationMessage(relayStorageId(originDeviceId, messageId), RelayEnvelope.MESSAGE_TYPE, ENDPOINT,
        System.currentTimeMillis(), expiresAtEpochMillis, toByteArray())

    companion object {
        private const val MAX_PENDING = 500
        private const val AUTHORIZATION_FILE = "gateway_relay_authorization_v2"
        private const val RECEIPT_TYPE = "relay.receipt"
        private val ENDPOINT = BackendConfiguration.relayEndpoint
        private fun receiptId(origin: String, id: String) = "receipt.${relayStorageId(origin, id)}"
        fun clearAuthorization(context: Context) =
            DeviceTokenStorage.clear(context, AUTHORIZATION_FILE, synchronous = true)
    }
}

internal suspend fun cleanupGatewayRegistration(
    deviceId: String?,
    revoke: Boolean,
    revokeDevice: suspend (String) -> Unit,
    deleteDatabase: (String) -> Unit
) {
    if (deviceId == null || !revoke) return
    var revocationFailure: Exception? = null
    try {
        revokeDevice(deviceId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        revocationFailure = failure
    }
    deleteDatabase(deviceId)
    if (revocationFailure != null) {
        throw IOException("Relaisfreigabe lokal beendet; Serverwiderruf nicht bestaetigt.", revocationFailure)
    }
}

internal class RelayGatewayShutdown {
    private var requested = false

    @Synchronized fun start() { requested = false }
    @Synchronized fun requestStop() { requested = true }
    @Synchronized fun shouldRevoke(ineligible: Boolean): Boolean = requested || ineligible
}