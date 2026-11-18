package org.wierichs.dogtracker.communication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.wierichs.dogtracker.MainActivity
import org.wierichs.dogtracker.TrackerPreferences
import org.wierichs.dogtracker.readHuntingPermissions
import org.wierichs.dogtracker.jagdkarte.JagdkarteApiException
import org.wierichs.dogtracker.jagdkarte.JagdkarteOfflineCache
import org.wierichs.dogtracker.jagdkarte.JagdkarteRepository

class RelayGatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gateway: RelayGatewayEngine? = null
    private var started = false
    private var terminalStatus: String? = null
    private var requestedProfileId: String? = null
    private var revokeOnDestroy = false
    private val incoming = Channel<suspend (RelayGatewayEngine) -> Unit>(64)

    override fun onCreate() {
        super.onCreate()
        RelayGateway.serviceCreated(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_REDELIVER_INTENT
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        requestedProfileId = profileId
        if (profileId == null || TrackerPreferences.isTracking(this) || !readHuntingPermissions(this).ready) {
            terminalStatus = "Relais nicht gestartet: Freigabe/Berechtigung fehlt oder DogTracker ist aktiv."
            RelayGateway.state.value = RelayGatewayStatus(profileId = profileId, status = terminalStatus!!)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        started = true
        try { startNotification() }
        catch (_: Exception) {
            terminalStatus = "Relaisdienst kann ohne sichtbare Benachrichtigung nicht gestartet werden."
            RelayGateway.state.value = RelayGatewayStatus(profileId = profileId, status = terminalStatus!!)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                val repository = JagdkarteRepository(applicationContext)
                check(repository.signedIn && repository.userId.isNotBlank()) { "Bitte erneut anmelden." }
                val userId = repository.userId
                val profile = try {
                    if (!hasValidatedRelayInternet(applicationContext)) throw IOException("Keine Internetverbindung.")
                    repository.profiles().singleOrNull { it.id == profileId }
                        ?: throw JagdkarteApiException(403, "profile_unavailable", "Kein Zugriff auf dieses Revier.")
                } catch (failure: IOException) {
                    if (failure is SSLException) throw failure
                    JagdkarteOfflineCache(applicationContext).load(repository.userId, profileId)?.snapshot?.profile
                        ?: throw IOException("Gespeicherter Offline-Revierstand fehlt oder ist abgelaufen.", failure)
                }
                if (!profile.huntingMode) {
                    throw JagdkarteApiException(409, "hunting_mode_inactive", "Relaisfreigabe benoetigt aktiven Jagdmodus.")
                }
                val engine = RelayGatewayEngine(applicationContext, repository, profile,
                    onIncoming = { envelope -> incoming.trySend { it.acceptEnvelope(envelope) } },
                    onDocument = { bytes, acknowledge -> incoming.trySend { if (it.acceptDocument(bytes)) acknowledge() } },
                    onPosition = { position, acknowledge -> incoming.trySend { if (it.acceptPosition(position)) acknowledge() } })
                gateway = engine
                RelayGateway.engine = engine
                try {
                    engine.initialize()
                    scope.launch {
                        for (receive in incoming) {
                            try {
                                receive(engine)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                RelayGateway.state.value = RelayGateway.state.value.copy(status = "Funkmeldung konnte nicht dauerhaft gespeichert werden; nicht bestaetigt.")
                            }
                        }
                    }
                    coroutineScope {
                        val reconnect = Channel<Unit>(Channel.CONFLATED)
                        val monitor = launch {
                            internetRestorations(applicationContext).collect { reconnect.trySend(Unit) }
                        }
                        try {
                            var refresh = false
                            while (true) {
                                if (!readHuntingPermissions(this@RelayGatewayService).ready || TrackerPreferences.isTracking(this@RelayGatewayService)) {
                                    revokeOnDestroy = true
                                    terminalStatus = if (TrackerPreferences.isTracking(this@RelayGatewayService)) {
                                        "Der DogTracker uebernimmt den Relaisbetrieb."
                                    } else "Jagdteilnahme beendet: Jagdfreigabe oder erforderliche Berechtigung fehlt."
                                    stopSelf()
                                    break
                                }
                                engine.synchronize(refreshOnReconnect = refresh)
                                refresh = withTimeoutOrNull(10_000) { reconnect.receive() } != null
                            }
                        } finally {
                            monitor.cancel()
                        }
                    }
                } catch (failure: JagdkarteApiException) {
                    if (failure.errorCode == "hunting_mode_inactive") {
                        withContext(Dispatchers.IO) {
                            OfflinePositionStore(applicationContext).use { it.recordHuntingMode(userId, profileId, false) }
                        }
                    }
                    throw failure
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                revokeOnDestroy = failure is JagdkarteApiException && failure.status in setOf(401, 403, 409)
                val status = when {
                    failure is JagdkarteApiException && failure.status == 409 -> "Jagdmodus aus; Relaisfreigabe beendet."
                    failure is JagdkarteApiException && failure.status in setOf(401, 403) -> "Relaisberechtigung widerrufen oder Sitzung ungueltig."
                    failure is IOException && failure !is SSLException ->
                        "Offline-Relais nicht verfügbar: Revierstand oder Berechtigung fehlt. Bitte online erneuern."
                    else -> "Relais konnte nicht eingerichtet werden. Verbindung und Serverbereitstellung pruefen."
                }
                terminalStatus = status
                RelayGateway.state.value = RelayGateway.state.value.copy(status = status)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Revier-Relais", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Jagdbruder-Relais aktiv").setContentText("Verschluesselte Reviermeldungen weiterleiten")
            .setOngoing(true).setContentIntent(open).build()
        ServiceCompat.startForeground(this, 2101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onDestroy() {
        incoming.close()
        val engine = gateway
        val revoke = RelayGateway.shutdown.shouldRevoke(
            revokeOnDestroy || !readHuntingPermissions(this).ready || TrackerPreferences.isTracking(this)
        )
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) {
                var cleanupFailed = false
                try { engine?.cancelOwn() } catch (_: Exception) { cleanupFailed = true }
                if (engine == null && revoke) {
                    try { RelayGatewayEngine.clearAuthorization(applicationContext) } catch (_: Exception) { cleanupFailed = true }
                }
                try { engine?.close(revoke) } catch (_: Exception) { cleanupFailed = true }
                RelayGateway.serviceDestroyed(this@RelayGatewayService, engine, RelayGatewayStatus(profileId = requestedProfileId,
                    status = (terminalStatus ?: "Relaisfreigabe beendet.") +
                        if (cleanupFailed) " Aufraeumen oder Serverwiderruf nicht bestaetigt." else ""))
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.wierichs.jagdkarte.RELAY_START"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val CHANNEL_ID = "jagdkarte_relay"

        internal fun requiredRelayPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (sdk >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}