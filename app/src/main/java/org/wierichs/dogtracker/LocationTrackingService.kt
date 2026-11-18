package org.wierichs.dogtracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.wierichs.dogtracker.communication.CommunicationMessage
import org.wierichs.dogtracker.communication.DogRelayAuthorization
import org.wierichs.dogtracker.communication.LocationPayloadCryptoException
import org.wierichs.dogtracker.communication.NearbyRelayTransport
import org.wierichs.dogtracker.communication.RelayEnvelope
import org.wierichs.dogtracker.communication.RelayEnvelopeEncoder
import org.wierichs.dogtracker.communication.RelayServerEncryptionKey
import org.wierichs.dogtracker.communication.RelaySigningIdentity
import org.wierichs.dogtracker.communication.SqliteMessageStore
import org.wierichs.dogtracker.communication.VerifiedRelayReceipt
import org.wierichs.dogtracker.communication.acknowledgeDogRelayReceipt
import org.wierichs.dogtracker.communication.localPosition
import org.wierichs.dogtracker.communication.nextRelaySequence
import org.wierichs.dogtracker.communication.internetRestorations
import org.wierichs.dogtracker.communication.RelayGateway
import org.wierichs.dogtracker.communication.RelayGatewayService

class LocationTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null
    private var nearbyRelayTransport: NearbyRelayTransport? = null
    private var networkMonitor: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (!readHuntingPermissions(this).ready) {
            stopTracking("Tracking beendet: Jagdfreigabe oder erforderliche Berechtigung fehlt.")
            return START_NOT_STICKY
        }
        if (RelayGateway.state.value.active || RelayGateway.state.value.starting) {
            TrackerPreferences.setStatus(this, "DogTracker pausiert: Revier-Relaisfreigabe ist aktiv.")
            TrackerPreferences.setTracking(this, false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELAY_READY) {
            if (!TrackerPreferences.isTracking(this)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (locationCallback != null) {
                if (hasNearbyPermissions()) updateForegroundServiceTypes(includeNearbyRelay = true)
                startNearbyRelayIfEligible()
                return START_STICKY
            }
        }

        val endpoint = TrackerPreferences.endpoint(this)
        val intervalSeconds = intent?.getLongExtra(
            EXTRA_INTERVAL_SECONDS,
            TrackerPreferences.intervalSeconds(this)
        ) ?: TrackerPreferences.intervalSeconds(this)

        if (!endpoint.startsWith("https://", ignoreCase = true) || intervalSeconds !in 15L..3600L) {
            TrackerPreferences.setStatus(this, "Endpunkt oder Intervall ist ungültig.")
            TrackerPreferences.setTracking(this, false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            TrackerPreferences.setStatus(this, "Die genaue Standortfreigabe wurde entzogen.")
            TrackerPreferences.setTracking(this, false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        TrackerPreferences.saveConfiguration(this, intervalSeconds)
        TrackerPreferences.setTracking(this, true)
        createNotificationChannel()
        val relayCanStart = TrackerPreferences.relayEnabled(this) && hasNearbyPermissions()
        if (TrackerPreferences.relayEnabled(this) && !relayCanStart) {
            TrackerPreferences.setStatus(this, "Relay pausiert: Berechtigungen für Geräte in der Nähe fehlen.")
        }
        updateForegroundServiceTypes(includeNearbyRelay = relayCanStart)
        startNearbyRelayIfEligible()
        requestLocationUpdates(intervalSeconds)
        if (networkMonitor == null) networkMonitor = serviceScope.launch {
            internetRestorations(applicationContext).collect {
                if (TrackerPreferences.isTracking(this@LocationTrackingService)) scheduleUpload(reconnected = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopNearbyRelay()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalSeconds: Long) {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        val intervalMillis = TimeUnit.SECONDS.toMillis(intervalSeconds)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(::storeLocation)
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    TrackerPreferences.setStatus(this, "Standortupdates konnten nicht gestartet werden.")
                    stopTracking()
                }
            TrackerPreferences.setStatus(this, "Warte auf Standortdaten …")
        } catch (_: SecurityException) {
            TrackerPreferences.setStatus(this, "Die Standortfreigabe ist nicht verfügbar.")
            stopTracking()
        }
    }

    private fun storeLocation(location: Location) {
        if (!readHuntingPermissions(this).ready) {
            stopTracking("Tracking beendet: Jagdfreigabe oder erforderliche Berechtigung fehlt.")
            return
        }
        val endpoint = TrackerPreferences.endpoint(this)
        val payload = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toDouble())
            .put("recorded_at", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(location.time)))
            .toString()

        serviceScope.launch(Dispatchers.IO) {
            startNearbyRelayIfEligible()
            publishNearbyPosition(location)
            val pendingCount = try {
                SqliteMessageStore(this@LocationTrackingService).use { store ->
                    store.enqueue(
                        CommunicationMessage.create(
                            type = "location.fix",
                            destination = endpoint,
                            createdAtEpochMillis = location.time,
                            payload = payload.toByteArray(Charsets.UTF_8)
                        )
                    )
                    createRelayEnvelopeIfReady(payload)?.let(store::enqueue)
                    store.pendingUploadCount()
                }
            } catch (_: LocationPayloadCryptoException) {
                TrackerPreferences.setStatus(
                    this@LocationTrackingService,
                    "GPS-Position konnte nicht sicher gespeichert werden; der naechste Fix wird erneut versucht."
                )
                return@launch
            }
            TrackerPreferences.setStatus(
                this@LocationTrackingService,
                "Position lokal gespeichert; Upload ausstehend ($pendingCount)."
            )
            nearbyRelayTransport?.sendPendingToAll()
            scheduleUpload()
        }
    }

    private fun publishNearbyPosition(location: Location) {
        val transport = nearbyRelayTransport ?: return
        if (!canPublishNearbyPosition() ||
            System.currentTimeMillis() - location.time !in -30_000L..30_000L) return
        val deviceId = TrackerPreferences.relayDeviceId(this) ?: return
        val directory = DogRelayAuthorization.current(this, deviceId) ?: return
        if (directory.deviceKind(deviceId) != "dog" || directory.subjectId(deviceId) == null) return
        try {
            directory.localPosition(location, nextRelaySequence(this, deviceId))?.let(transport::publishPosition)
        } catch (failure: IOException) {
            android.util.Log.w("LocationTrackingService", "Nearby-Positionssequenz konnte nicht gespeichert werden.", failure)
        } catch (failure: GeneralSecurityException) {
            android.util.Log.w("LocationTrackingService", "Nearby-Position konnte nicht signiert werden.", failure)
        } catch (failure: IllegalArgumentException) {
            android.util.Log.w("LocationTrackingService", "Nearby-Position konnte nicht vorbereitet werden.", failure)
        }
    }

    private fun canPublishNearbyPosition(): Boolean =
        TrackerPreferences.isTracking(this) && TrackerPreferences.relayEnabled(this) &&
            readHuntingPermissions(this).ready && hasNearbyPermissions()

    private fun createRelayEnvelopeIfReady(locationPayload: String): CommunicationMessage? {
        if (!TrackerPreferences.relayEnabled(this)) return null
        val originDeviceId = TrackerPreferences.relayDeviceId(this) ?: return null
        val keyId = TrackerPreferences.relayEncryptionKeyId(this) ?: return null
        val publicKey = TrackerPreferences.relayEncryptionPublicKey(this) ?: return null
        return runCatching {
            val envelope = RelayEnvelopeEncoder.create(
                originDeviceId = originDeviceId,
                locationPayload = locationPayload.toByteArray(Charsets.UTF_8),
                serverKey = RelayServerEncryptionKey(keyId, publicKey),
                sign = RelaySigningIdentity::sign
            )
            CommunicationMessage(
                messageId = envelope.messageId,
                type = RelayEnvelope.OUTGOING_MESSAGE_TYPE,
                destination = TrackerPreferences.RELAY_ENDPOINT,
                createdAtEpochMillis = System.currentTimeMillis(),
                expiresAtEpochMillis = envelope.expiresAtEpochMillis,
                payload = envelope.toByteArray()
            )
        }.getOrNull()
    }

    private fun startNearbyRelayIfEligible() {
        if (!TrackerPreferences.isTracking(this) || !TrackerPreferences.relayEnabled(this)) return
        if (!hasNearbyPermissions()) {
            TrackerPreferences.setStatus(this, "Relay pausiert: Berechtigungen für Geräte in der Nähe fehlen.")
            return
        }
        val deviceId = TrackerPreferences.relayDeviceId(this)
        if (deviceId == null) {
            TrackerPreferences.setStatus(this, "Relay wird nach erfolgreicher Geräteschlüssel-Registrierung gestartet.")
            return
        }
        if (DogRelayAuthorization.current(this, deviceId) == null) {
            TrackerPreferences.setStatus(this, "Relay pausiert: Revierberechtigung muss online eingerichtet werden.")
            return
        }
        if (nearbyRelayTransport != null) return

        nearbyRelayTransport = NearbyRelayTransport(
            context = this,
            localDeviceId = deviceId,
            authorization = { DogRelayAuthorization.current(this, deviceId) },
            onIncomingEnvelope = { envelope -> storeIncomingRelayEnvelope(envelope) },
            onTransportStatus = { status -> TrackerPreferences.setStatus(this, status) },
            onIncomingDocument = { bytes, acknowledge -> acceptRelayReceipt(deviceId, bytes, acknowledge) },
            canPublishPosition = ::canPublishNearbyPosition
        ).also(NearbyRelayTransport::start)
    }

    private fun acceptRelayReceipt(deviceId: String, bytes: ByteArray, acknowledge: () -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val authorization = checkNotNull(DogRelayAuthorization.authorization(this@LocationTrackingService, deviceId)) {
                    "Relay-Berechtigung fehlt oder ist abgelaufen."
                }
                val receipt = VerifiedRelayReceipt.verify(
                    JSONObject(String(bytes, Charsets.UTF_8)), authorization.capabilities, authorization.directory.profileId
                )
                SqliteMessageStore(this@LocationTrackingService).use { store ->
                    val cleared = acknowledgeDogRelayReceipt(store, receipt, deviceId)
                    if (receipt.originDeviceId != deviceId || store.findPending(receipt.messageId) == null || cleared) {
                        acknowledge()
                    } else TrackerPreferences.setStatus(
                        this@LocationTrackingService, "Relay-Bestätigung passt nicht zum gespeicherten Umschlag."
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TrackerPreferences.setStatus(this@LocationTrackingService, "Relay-Bestätigung konnte nicht geprüft oder gespeichert werden.")
            }
        }
    }

    private fun storeIncomingRelayEnvelope(envelope: RelayEnvelope) {
        serviceScope.launch(Dispatchers.IO) {
            val message = CommunicationMessage(
                messageId = envelope.messageId,
                type = RelayEnvelope.MESSAGE_TYPE,
                destination = TrackerPreferences.RELAY_ENDPOINT,
                createdAtEpochMillis = System.currentTimeMillis(),
                expiresAtEpochMillis = envelope.expiresAtEpochMillis,
                payload = envelope.toByteArray()
            )
            val stored = runCatching {
                SqliteMessageStore(this@LocationTrackingService).use { it.enqueue(message) }
            }.isSuccess
            if (stored) {
                TrackerPreferences.setStatus(this@LocationTrackingService, "Relay-Nachricht lokal angenommen; Server-Upload ausstehend.")
                scheduleUpload()
            }
        }
    }

    private fun stopNearbyRelay() {
        nearbyRelayTransport?.stop()
        nearbyRelayTransport = null
    }

    private fun hasNearbyPermissions(): Boolean = nearbyPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun nearbyPermissions(): List<String> = RelayGatewayService.requiredRelayPermissions()

    private fun updateForegroundServiceTypes(includeNearbyRelay: Boolean) {
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            if (includeNearbyRelay) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, trackingNotification(), serviceTypes)
    }

    private fun scheduleUpload(reconnected: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LocationUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(LocationUploadWorker.REFRESH_RELAY_ON_RECONNECT to reconnected))
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UPLOAD_WORK_NAME,
            if (reconnected) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun trackingNotification(): Notification {
        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Hundetracker aktiv")
            .setContentText("Standort wird erfasst und übertragen")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Tracking stoppen", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Standort-Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt eine aktive Hundetracker-Sitzung an"
            }
        )
    }

    private fun stopTracking(status: String = "Tracking gestoppt.") {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        TrackerPreferences.setTracking(this, false)
        TrackerPreferences.setStatus(this, status)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "org.wierichs.dogtracker.action.START"
        const val ACTION_STOP = "org.wierichs.dogtracker.action.STOP"
        const val ACTION_RELAY_READY = "org.wierichs.dogtracker.action.RELAY_READY"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"

        private const val NOTIFICATION_CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_REQUEST_CODE = 1002
        private const val UPLOAD_WORK_NAME = "location_upload_queue"
    }
}