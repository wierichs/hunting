package org.wierichs.dogtracker.communication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wierichs.dogtracker.MainActivity
import org.wierichs.dogtracker.TrackerPreferences
import org.wierichs.dogtracker.jagdkarte.JagdkarteApiException
import org.wierichs.dogtracker.jagdkarte.JagdkarteRepository

class RelayGatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gateway: RelayGatewayEngine? = null
    private var started = false
    private var terminalStatus: String? = null
    private val incoming = Channel<ByteArray>(64)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId == null || TrackerPreferences.isTracking(this) || requiredRelayPermissions().any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            terminalStatus = "Relais nicht gestartet: Berechtigung fehlt oder DogTracker ist aktiv."
            RelayGateway.state.value = RelayGatewayStatus(status = terminalStatus!!)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        started = true
        try { startNotification() }
        catch (_: Exception) {
            terminalStatus = "Relaisdienst kann ohne sichtbare Benachrichtigung nicht gestartet werden."
            RelayGateway.state.value = RelayGatewayStatus(status = terminalStatus!!)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                val repository = JagdkarteRepository(applicationContext)
                val profile = repository.profiles().singleOrNull { it.id == profileId }
                    ?: error("Kein Zugriff auf dieses Revier.")
                check(profile.huntingMode) { "Relaisfreigabe benoetigt aktiven Jagdmodus." }
                val engine = RelayGatewayEngine(applicationContext, repository, profile,
                    onIncoming = { envelope -> incoming.trySend(envelope.toByteArray()) },
                    onDocument = { bytes -> incoming.trySend(bytes) })
                gateway = engine
                engine.initialize()
                RelayGateway.engine = engine
                scope.launch {
                    for (bytes in incoming) {
                        try {
                            val envelope = RelayEnvelope.parse(bytes)
                            if (envelope != null) engine.acceptEnvelope(envelope) else engine.acceptDocument(bytes)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            RelayGateway.state.value = RelayGateway.state.value.copy(status = "Funkmeldung konnte nicht dauerhaft gespeichert werden; nicht bestaetigt.")
                        }
                    }
                }
                while (true) { engine.synchronize(); delay(10_000) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val status = when {
                    failure is JagdkarteApiException && failure.status == 409 -> "Jagdmodus aus; Relaisfreigabe beendet."
                    failure is JagdkarteApiException && failure.status in setOf(401, 403) -> "Relaisberechtigung widerrufen oder Sitzung ungueltig."
                    else -> "Relais konnte nicht eingerichtet werden. Verbindung und Serverbereitstellung pruefen."
                }
                terminalStatus = status
                RelayGateway.state.value = RelayGateway.state.value.copy(status = status)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Revier-Relais", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 21, Intent(this, RelayGatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Jagdkarte-Relais aktiv").setContentText("Verschluesselte Reviermeldungen weiterleiten")
            .setOngoing(true).setContentIntent(open).addAction(0, "Relais stoppen", stop).build()
        ServiceCompat.startForeground(this, 2101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onDestroy() {
        RelayGateway.engine = null
        incoming.close()
        val engine = gateway
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) {
                runCatching { engine?.cancelOwn() }
                runCatching { engine?.close() }
                RelayGateway.state.value = RelayGatewayStatus(status = terminalStatus ?: "Relaisfreigabe beendet.")
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.wierichs.jagdkarte.RELAY_START"
        const val ACTION_STOP = "org.wierichs.jagdkarte.RELAY_STOP"
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