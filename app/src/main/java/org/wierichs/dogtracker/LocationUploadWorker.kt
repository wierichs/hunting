package org.wierichs.dogtracker

import android.content.Intent
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wierichs.dogtracker.communication.RelayApiClient
import org.wierichs.dogtracker.communication.RelaySigningIdentity
import org.wierichs.dogtracker.communication.HttpsLocationTransport
import org.wierichs.dogtracker.communication.SqliteMessageStore

class LocationUploadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var relaySetupPending = false
        while (true) {
            val pending = SqliteMessageStore(applicationContext).use { store ->
                store.removeExpired()
                store.oldestPending()
            } ?: return@withContext Result.success()

            try {
                val deviceToken = TrackerPreferences.deviceToken(applicationContext)
                    ?: run {
                        TrackerPreferences.setStatus(applicationContext, "Geräteschlüssel fehlt. Bitte erneut aus der Jagdkarte einfügen.")
                        return@withContext Result.failure()
                    }
                if (!relayIsProvisioned(deviceToken)) relaySetupPending = true
                val responseCode = if (pending.message.type == RELAY_ENVELOPE_MESSAGE_TYPE) {
                    RelayApiClient(TrackerPreferences.RELAY_ENDPOINT, deviceToken)
                        .submitRelayEnvelope(pending.message.payload)
                } else {
                    HttpsLocationTransport(deviceToken).send(pending.message)
                }
                if (responseCode in 200..299) {
                    val remainingCount = SqliteMessageStore(applicationContext).use { store ->
                        store.acknowledge(pending.message.messageId)
                        store.pendingCount()
                    }
                    TrackerPreferences.setStatus(
                        applicationContext,
                        if (remainingCount == 0 && !relaySetupPending) "Position vom Server bestätigt (HTTP $responseCode)."
                        else if (remainingCount == 0) "Position vom Server bestätigt. Relay-Einrichtung wartet auf Verbindung."
                        else "Position vom Server bestätigt; $remainingCount weitere Meldungen ausstehend."
                    )
                } else if (responseCode == 401) {
                    TrackerPreferences.setStatus(applicationContext, "Geräteschlüssel ungültig oder widerrufen. Bitte in der Jagdkarte neu koppeln.")
                    return@withContext Result.failure()
                } else if (responseCode == 409) {
                    SqliteMessageStore(applicationContext).use { store -> store.clear() }
                    TrackerPreferences.setTracking(applicationContext, false)
                    TrackerPreferences.setStatus(applicationContext, "Jagdmodus ist aus. Tracking wurde gestoppt und gepufferte Positionen wurden gelöscht.")
                    applicationContext.stopService(
                        Intent(applicationContext, LocationTrackingService::class.java)
                    )
                    return@withContext Result.failure()
                } else if (responseCode in 400..499 && responseCode != 409) {
                    SqliteMessageStore(applicationContext).use { store ->
                        store.acknowledge(pending.message.messageId)
                    }
                    TrackerPreferences.setStatus(applicationContext, "Positionsdaten abgelehnt (HTTP $responseCode); bitte Einstellungen prüfen.")
                    continue
                } else {
                    TrackerPreferences.setStatus(applicationContext, "Upload fehlgeschlagen (HTTP $responseCode); erneuter Versuch folgt.")
                    return@withContext Result.retry()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TrackerPreferences.setStatus(applicationContext, "Kein erfolgreicher Upload; Standort bleibt gespeichert.")
                return@withContext Result.retry()
            }
        }

        @Suppress("UNREACHABLE_CODE")
        Result.success()
    }

    private fun relayIsProvisioned(deviceToken: String): Boolean {
        if (TrackerPreferences.relayDeviceId(applicationContext) == null) {
            try {
                val publicKey = RelaySigningIdentity.publicKeyBase64Url()
                val deviceId = RelayApiClient(TrackerPreferences.RELAY_ENDPOINT, deviceToken)
                    .registerSigningKey(publicKey)
                TrackerPreferences.saveRelayDeviceId(applicationContext, deviceId)
            } catch (_: Exception) {
                return false
            }
        }

        if (TrackerPreferences.relayEncryptionKeyId(applicationContext) == null ||
            TrackerPreferences.relayEncryptionPublicKey(applicationContext) == null
        ) {
            try {
                val serverKey = RelayApiClient(TrackerPreferences.RELAY_ENDPOINT, deviceToken)
                    .fetchServerEncryptionKey()
                TrackerPreferences.saveRelayEncryptionKey(
                    applicationContext,
                    serverKey.keyId,
                    serverKey.publicKey
                )
            } catch (_: Exception) {
                return false
            }
        }

        return true
    }

    companion object {
        private const val RELAY_ENVELOPE_MESSAGE_TYPE = "relay.envelope"
    }
}