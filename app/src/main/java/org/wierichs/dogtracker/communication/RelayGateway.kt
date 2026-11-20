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
    val confirmed: Int = 0,
    val huntingSessionId: String? = null,
    val forwardingHuntEnd: Boolean = false
)

internal enum class PositionSharing { NEARBY_ONLY, SERVER_QUEUED }

internal object RelayGateway {
    val state = MutableStateFlow(RelayGatewayStatus())
    val broadcastSession = MutableStateFlow<RelayBroadcastSession?>(null)
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
        if (engine === current) {
            engine = null
            broadcastSession.value = null
        }
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

    suspend fun enqueue(profileId: String, location: Location): PositionSharing? {
        val current = engine?.takeIf { state.value.active && !state.value.forwardingHuntEnd && it.profileId == profileId }
            ?: return null
        return current.enqueueLocation(location)
    }

    suspend fun cancelOwn(profileId: String) {
        engine?.takeIf { it.profileId == profileId }?.cancelOwn()
    }

    suspend fun refreshHuntingEnd(profileId: String) {
        engine?.takeIf { it.profileId == profileId }?.onServerHuntingModeDisabled()
    }

    suspend fun sendBroadcast(profileId: String, template: BroadcastTemplate) {
        val current = checkNotNull(engine?.takeIf { it.profileId == profileId && state.value.active && !state.value.starting }) {
            "Nearby ist für dieses Revier nicht bereit."
        }
        current.sendBroadcast(template)
    }

    suspend fun signalDog(profileId: String, dogId: String, signal: DogSignal): DogSignalResult {
        val current = engine?.takeIf { it.profileId == profileId && state.value.active && !state.value.starting }
            ?: return DogSignalResult.UNAVAILABLE
        return current.signalDog(dogId, signal)
    }
}

internal data class RelayBroadcastSession(val profileId: String, val userId: String, val mailbox: NearbyBroadcasts)

internal class RelayGatewayEngine(
    context: Context,
    private val repository: JagdkarteRepository,
    private val profile: HuntingProfile,
    private val onIncoming: (RelayEnvelope) -> Unit,
    private val onDocument: (ByteArray, () -> Unit) -> Unit,
    private val onPosition: ((OfflinePositionMessage, () -> Unit) -> Unit)? = null,
    private val onHuntingEnd: ((ByteArray, () -> Unit) -> Unit)? = null,
    private val onForwardingHuntingEnd: () -> Unit = {}
) {
    val profileId: String get() = profile.id
    private val appContext = context.applicationContext
    private val positionUserId = repository.userId
    private val broadcastMailbox = NearbyBroadcasts()
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
    @Volatile private var huntingEnd: VerifiedHuntingEnd? = null
    @Volatile private var endPacket: NearbyPacket? = null
    @Volatile private var endOnly = false

    fun forwardingFinished(): Boolean = endOnly &&
        (directory?.current() != true || huntingEnd?.expiresAt?.isAfter(java.time.Instant.now()) == false)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = RelayOfflineAuthorization.load(appContext, AUTHORIZATION_FILE, positionUserId, null, profileId)
                ?.takeIf { !profile.huntingMode || profile.huntingSessionId == null ||
                    it.directory.huntingSessionId == profile.huntingSessionId }
            if (saved != null) {
                capabilities = saved.capabilities
                directory = saved.directory
            }
            val savedSession = saved?.directory?.huntingSessionId
            val storedEnd = savedSession?.let { session ->
                OfflinePositionStore(appContext).use { it.loadEndForForwarding(positionUserId, profileId, session) }
            }?.let { verifyHuntingEnd(it) }
            if (!profile.huntingMode && savedSession == null) {
                throw JagdkarteApiException(409, "hunting_mode_inactive", "Relaisfreigabe benoetigt aktiven Jagdmodus.")
            }
            val offline = try {
                if (!hasRelayInternetRoute(appContext)) throw IOException("Keine Internetverbindung.")
                if (storedEnd == null && profile.huntingMode) {
                    try { refreshAuthorization() }
                    catch (failure: JagdkarteApiException) {
                        if (savedSession == null || failure.errorCode != "hunting_mode_inactive") throw failure
                        enterEndOnly()
                    }
                }
                false
            } catch (failure: IOException) {
                if (failure is SSLException) throw failure
                if (saved == null) throw IOException("Offline-Relaisberechtigung fehlt oder ist abgelaufen; bitte online erneuern.", failure)
                nextRefresh = SystemClock.elapsedRealtime() + 10_000
                true
            }
            if (deviceId == null) deviceId = directory?.localDeviceId
            val sessionId = directory?.huntingSessionId
            val endedBefore = sessionId != null && OfflinePositionStore(appContext).use {
                it.isSessionEnded(positionUserId, profileId, sessionId)
            }
            val id = deviceId!!
            store = SqliteMessageStore(appContext, "relay_gateway_$id.db")
            store!!.removeExpired()
            purgeOwn()
            transport = NearbyRelayTransport(appContext, id, { directory }, onIncoming, { status -> update(status) },
                pendingPackets = { outgoing }, onIncomingDocument = onDocument, onIncomingPosition = onPosition,
                canPublishPosition = {
                    !endOnly && readHuntingPermissions(appContext).ready && !TrackerPreferences.isTracking(appContext)
                }, broadcasts = broadcastMailbox, huntingEndPacket = {
                    endPacket?.takeIf { huntingEnd?.expiresAt?.isAfter(java.time.Instant.now()) == true }
                }, onHuntingEnd = onHuntingEnd, regularTrafficAllowed = { !endOnly })
            if (storedEnd != null) enterEndOnly(storedEnd)
            else if (!profile.huntingMode || endedBefore) enterEndOnly()
            refreshOutgoing()
            transport!!.start()
            if (!endOnly) RelayGateway.broadcastSession.value = RelayBroadcastSession(profileId, positionUserId, broadcastMailbox)
            RelayGateway.state.value = RelayGatewayStatus(active = true, profileId = profileId,
                status = if (endOnly) endStatus() else if (offline) "Offline-Relaisfreigabe aktiv; Servererneuerung ausstehend."
                    else "Relaisfreigabe aktiv; bereit fuer signierte Reviermeldungen.", pending = pendingCount(),
                huntingSessionId = sessionId, forwardingHuntEnd = endOnly)
        }
    }

    suspend fun sendBroadcast(template: BroadcastTemplate) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed && !endOnly) { "Nearby ist für dieses Revier nicht bereit." }
            checkNotNull(transport).sendBroadcast(template)
        }
    }

    suspend fun signalDog(dogId: String, signal: DogSignal): DogSignalResult = withContext(Dispatchers.IO) {
        val nearby = mutex.withLock { transport?.takeUnless { closed || endOnly } }
        nearby?.signalDog(dogId, signal) ?: DogSignalResult.UNAVAILABLE
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
        val previousSession = directory?.huntingSessionId
        if (previousSession != null && verified.directory.huntingSessionId != previousSession) {
            throw JagdkarteApiException(409, "hunting_session_changed", "Ein neuer Jagddurchlauf ist aktiv.")
        }
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
            val current = checkNotNull(directory?.takeIf { !closed && !endOnly && it.current() }) {
                "Relaisberechtigung ist abgelaufen. Online-Verbindung erforderlich."
            }
            require(locationInsideBoundary(profile.boundary, location.latitude, location.longitude)) { "Standort ausserhalb des Reviers." }
            val now = System.currentTimeMillis()
            require(now - location.time in -30_000L..30_000L)
            val id = deviceId!!
            val hasRoute = hasRelayInternetRoute(appContext)
            val hasBinding = current.subjectId(id) == positionUserId && positionUserId.isNotBlank()
            check(hasRoute || hasBinding) { "Offline-Standortfreigabe muss einmal online erneuert werden." }
            val sequence = nextRelaySequence(appContext, id)
            val nearby = if (hasBinding) current.localPosition(location, sequence) else null
            if (!hasRoute) {
                nearby?.let { transport?.publishPosition(it) }
                return@withLock PositionSharing.NEARBY_ONLY
            }
            val publishedLocally = nearby != null && !hasRelayInternetRoute(appContext, requireValidation = true)
            if (publishedLocally) transport?.publishPosition(nearby!!)
            try {
                if (sharingStopPending) stopSharingOnServer()
                if (sharingEpoch == null) {
                    val response = repository.relayPost(JSONObject().put("action", "set_sharing").put("device_id", id).put("enabled", true))
                    sharingEpoch = response.getString("sharing_epoch")
                }
            } catch (failure: IOException) {
                if (nearby == null || failure is SSLException) throw failure
                if (!publishedLocally) transport?.publishPosition(nearby)
                update("Serverfreigabe nicht erreichbar; Position nur fuer Nearby bereit.")
                return@withLock PositionSharing.NEARBY_ONLY
            }
            if (!publishedLocally) nearby?.let { transport?.publishPosition(it) }
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
            PositionSharing.SERVER_QUEUED
        }
    }

    suspend fun acceptEnvelope(envelope: RelayEnvelope) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || endOnly || directory?.accepts(envelope) != true) return@withLock
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

    suspend fun acceptPosition(position: OfflinePositionMessage): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentDirectory = directory ?: return@withLock false
            if (closed || endOnly || positionUserId.isBlank() || !readHuntingPermissions(appContext).ready ||
                TrackerPreferences.isTracking(appContext) || !position.verify(currentDirectory)
            ) return@withLock false
            OfflinePositionStore(appContext).use { it.save(positionUserId, position, currentDirectory.huntingSessionId) }
        }
    }

    suspend fun acceptDocument(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || endOnly) return@withLock false
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

    suspend fun acceptHuntingEnd(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || directory?.current() != true) return@withLock false
            val verified = verifyHuntingEnd(bytes) ?: return@withLock false
            enterEndOnly(verified)
            true
        }
    }

    suspend fun onServerHuntingModeDisabled() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || directory?.huntingSessionId == null) return@withLock
            enterEndOnly()
            if (hasRelayInternetRoute(appContext)) fetchHuntingEndIfReachable()
        }
    }

    private fun verifyHuntingEnd(bytes: ByteArray): VerifiedHuntingEnd? {
        val session = directory?.huntingSessionId ?: return null
        val capability = capabilities ?: return null
        return try {
            VerifiedHuntingEnd.verify(bytes, capability, profileId, session)
        } catch (_: IllegalArgumentException) {
            android.util.Log.w("RelayGateway", "Jagdende-Nachweis passt nicht oder ist ungueltig.")
            null
        } catch (_: org.json.JSONException) {
            android.util.Log.w("RelayGateway", "Jagdende-Nachweis enthaelt kein gueltiges Dokument.")
            null
        } catch (_: java.time.format.DateTimeParseException) {
            android.util.Log.w("RelayGateway", "Jagdende-Nachweis enthaelt keine gueltige Zeit.")
            null
        }
    }

    private suspend fun fetchHuntingEnd() {
        val session = directory?.huntingSessionId ?: return
        if (huntingEnd != null) return
        val response = try {
            repository.relayPost(JSONObject().put("action", "hunt_end").put("profile_id", profileId).put("session_id", session))
        } catch (failure: JagdkarteApiException) {
            if (failure.status == 404 && failure.errorCode == "hunt_end_not_found") return
            throw failure
        }
        val verified = checkNotNull(verifyHuntingEnd(response.toString().toByteArray(Charsets.UTF_8))) {
            "Server-Nachweis fuer das Jagdende konnte nicht geprueft werden."
        }
        enterEndOnly(verified)
    }

    private suspend fun fetchHuntingEndIfReachable() {
        try { fetchHuntingEnd() }
        catch (failure: Exception) {
            if (failure is CancellationException || failure is SSLException ||
                failure is JagdkarteApiException && failure.status in setOf(401, 403)) throw failure
            update("Jagdende-Nachweis vorlaeufig nicht abrufbar.")
            android.util.Log.w("RelayGateway", "Jagdende-Nachweis konnte nicht abgerufen werden.", failure)
        }
    }

    private fun enterEndOnly(end: VerifiedHuntingEnd? = null) {
        if (end != null && huntingEnd != null) return
        if (end != null) {
            OfflinePositionStore(appContext).use { it.recordVerifiedEnd(positionUserId, end) }
            val bytes = JSONObject().put("action", HUNT_END_NEARBY_ACTION)
                .put("signed_end", String(end.toByteArray(), Charsets.UTF_8)).toString().toByteArray(Charsets.UTF_8)
            huntingEnd = end
            endPacket = NearbyPacket(nearbyPacketDigest(bytes), bytes, receipt = true, huntingEnd = true)
        }
        if (!endOnly) {
            endOnly = true
            sharingEpoch = null
            transport?.clearOwnPosition()
            outgoing = emptyList()
            RelayGateway.broadcastSession.value = null
            onForwardingHuntingEnd()
        }
        val database = store
        if (database != null) for (type in listOf(RelayEnvelope.MESSAGE_TYPE, RECEIPT_TYPE)) {
            database.pendingOfType(type, MAX_PENDING + 1).forEach { database.acknowledge(it.message.messageId) }
        }
        RelayGateway.state.value = RelayGateway.state.value.copy(forwardingHuntEnd = true,
            status = endStatus(),
            pending = 0, huntingSessionId = directory?.huntingSessionId)
        transport?.sendPendingToAll()
    }

    private fun endStatus() = if (huntingEnd == null) HUNT_END_PENDING_STATUS else HUNT_END_STATUS

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

    suspend fun synchronize(refreshOnReconnect: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            store!!.removeExpired()
            if (hasRelayInternetRoute(appContext)) fetchHuntingEndIfReachable()
            if (endOnly) {
                if (!forwardingFinished()) transport?.sendPendingToAll()
                return@withLock
            }
            if (!hasRelayInternetRoute(appContext)) {
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
            if (refreshOnReconnect || SystemClock.elapsedRealtime() >= nextRefresh) {
                try { refreshAuthorization(); transport?.start() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure is JagdkarteApiException && failure.errorCode == "hunting_mode_inactive" &&
                        directory?.huntingSessionId != null) {
                        enterEndOnly()
                        return@withLock
                    }
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
                if (failure is JagdkarteApiException && failure.errorCode == "hunting_mode_inactive" &&
                    directory?.huntingSessionId != null) {
                    enterEndOnly()
                    return@withLock
                }
                if (failure is JagdkarteApiException && failure.status in setOf(401, 403, 409)) throw failure
                update("Kein bestaetigter Upload; Meldungen bleiben bis zum Ablauf gespeichert.")
            }
            refreshOutgoing()
            transport?.sendPendingToAll()
        }
    }

    suspend fun cancelOwn() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || endOnly || store == null) return@withLock
            transport?.clearOwnPosition()
            sharingEpoch = null
            sharingStopPending = true
            purgeOwn()
            refreshOutgoing()
            if (!hasRelayInternetRoute(appContext)) {
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
        if (endOnly) return
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
        private const val HUNT_END_STATUS = "Positionsfreigabe beendet; Nearby gibt nur das bestaetigte Jagdende weiter."
        private const val HUNT_END_PENDING_STATUS = "Positionsfreigabe beendet; signierter Nachweis steht noch aus."
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