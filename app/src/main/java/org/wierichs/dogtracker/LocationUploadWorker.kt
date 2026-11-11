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
import org.wierichs.dogtracker.communication.LocationPayloadCryptoException
import org.wierichs.dogtracker.communication.MessageTransportResponse
import org.wierichs.dogtracker.communication.SqliteMessageStore
import org.wierichs.dogtracker.communication.UnreadableLocationPayloadException

class LocationUploadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var relaySetupPending = false
        while (true) {
            val pending = try {
                SqliteMessageStore(applicationContext).use { store ->
                    store.removeExpired()
                    store.oldestPending()
                }
            } catch (error: UnreadableLocationPayloadException) {
                SqliteMessageStore(applicationContext).use { store -> store.acknowledge(error.messageId) }
                TrackerPreferences.setStatus(
                    applicationContext,
                    "Eine gespeicherte GPS-Position konnte nicht entschluesselt werden und wurde verworfen; sie muss neu erfasst werden."
                )
                continue
            } catch (_: LocationPayloadCryptoException) {
                TrackerPreferences.setStatus(
                    applicationContext,
                    "Lokale GPS-Daten konnten nicht sicher entschluesselt werden; erneuter Versuch folgt."
                )
                return@withContext Result.retry()
            } ?: return@withContext Result.success()

            try {
                if (pending.message.type == LOCATION_MESSAGE_TYPE) {
                    val retryAfterAt = TrackerPreferences.locationUploadRetryAfter(applicationContext)
                    if (retryAfterAt != null && retryAfterAt > System.currentTimeMillis()) {
                        TrackerPreferences.setStatus(
                            applicationContext,
                            "Der Server hat den GPS-Upload zurückgestellt; die Position bleibt gespeichert."
                        )
                        return@withContext Result.retry()
                    }
                    if (retryAfterAt != null) {
                        TrackerPreferences.clearLocationUploadRetryAfter(applicationContext)
                    }
                }

                val deviceToken = TrackerPreferences.deviceToken(applicationContext)
                    ?: run {
                        TrackerPreferences.setStatus(applicationContext, "Geräteschlüssel fehlt. Bitte erneut aus der Jagdkarte einfügen.")
                        return@withContext Result.failure()
                    }
                if (!relayIsProvisioned(deviceToken)) relaySetupPending = true
                val response = if (pending.message.type == RELAY_ENVELOPE_MESSAGE_TYPE) {
                    MessageTransportResponse(
                        statusCode = RelayApiClient(TrackerPreferences.RELAY_ENDPOINT, deviceToken)
                            .submitRelayEnvelope(pending.message.payload),
                        retryAfter = null
                    )
                } else {
                    HttpsLocationTransport(deviceToken).send(pending.message)
                }
                when (locationUploadDisposition(response.statusCode)) {
                    LocationUploadDisposition.ACCEPTED -> {
                        val remainingCount = SqliteMessageStore(applicationContext).use { store ->
                            store.acknowledge(pending.message.messageId)
                            store.pendingCount()
                        }
                        TrackerPreferences.setStatus(
                            applicationContext,
                            if (remainingCount == 0 && !relaySetupPending) "Position vom Server bestätigt (HTTP ${response.statusCode})."
                            else if (remainingCount == 0) "Position vom Server bestätigt. Relay-Einrichtung wartet auf Verbindung."
                            else "Position vom Server bestätigt; $remainingCount weitere Meldungen ausstehend."
                        )
                    }
                    LocationUploadDisposition.INVALID_CREDENTIALS -> {
                        TrackerPreferences.setStatus(applicationContext, "Geräteschlüssel ungültig oder widerrufen. Bitte in der Jagdkarte neu koppeln.")
                        return@withContext Result.failure()
                    }
                    LocationUploadDisposition.HUNTING_MODE_DISABLED -> {
                        SqliteMessageStore(applicationContext).use { store -> store.clear() }
                        TrackerPreferences.clearLocationUploadRetryAfter(applicationContext)
                        TrackerPreferences.setTracking(applicationContext, false)
                        TrackerPreferences.setStatus(applicationContext, "Jagdmodus ist aus. Tracking wurde gestoppt und gepufferte Positionen wurden gelöscht.")
                        applicationContext.stopService(
                            Intent(applicationContext, LocationTrackingService::class.java)
                        )
                        return@withContext Result.failure()
                    }
                    LocationUploadDisposition.PERMANENT_REJECTION -> {
                        SqliteMessageStore(applicationContext).use { store ->
                            store.acknowledge(pending.message.messageId)
                        }
                        TrackerPreferences.setStatus(
                            applicationContext,
                            "Positionsdaten abgelehnt (HTTP ${response.statusCode}); bitte Einstellungen prüfen."
                        )
                        continue
                    }
                    LocationUploadDisposition.RETRY -> {
                        val retryAfterDelay = if (pending.message.type == LOCATION_MESSAGE_TYPE) {
                            retryAfterDelayMillis(response.retryAfter)
                        } else {
                            null
                        }
                        if (retryAfterDelay != null) {
                            val retryAfterAt = Math.addExact(
                                System.currentTimeMillis(),
                                retryAfterDelay
                            )
                            if (!TrackerPreferences.setLocationUploadRetryAfter(
                                applicationContext,
                                retryAfterAt
                            )) {
                                TrackerPreferences.setStatus(
                                    applicationContext,
                                    "Die vom Server vorgegebene Wartefrist konnte nicht gespeichert werden; die GPS-Position bleibt gespeichert."
                                )
                                return@withContext Result.failure()
                            }
                            TrackerPreferences.setStatus(
                                applicationContext,
                                "Upload vom Server zurückgestellt (HTTP ${response.statusCode}); die Position bleibt gespeichert."
                            )
                        } else {
                            TrackerPreferences.setStatus(
                                applicationContext,
                                "Upload fehlgeschlagen (HTTP ${response.statusCode}); erneuter Versuch folgt."
                            )
                        }
                        return@withContext Result.retry()
                    }
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
        private const val LOCATION_MESSAGE_TYPE = "location.fix"
        private const val RELAY_ENVELOPE_MESSAGE_TYPE = "relay.envelope"
    }
}