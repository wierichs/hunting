package org.wierichs.dogtracker

import android.content.Intent
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class LocationUploadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        while (true) {
            val pending = LocationOutbox.first(applicationContext)
                ?: return@withContext Result.success()

            try {
                val deviceToken = TrackerPreferences.deviceToken(applicationContext)
                    ?: run {
                        TrackerPreferences.setStatus(applicationContext, "Geräteschlüssel fehlt. Bitte erneut aus der Jagdkarte einfügen.")
                        return@withContext Result.failure()
                    }
                val responseCode = postLocation(pending, deviceToken)
                if (responseCode in 200..299) {
                    LocationOutbox.delete(applicationContext, pending.id)
                    TrackerPreferences.setStatus(applicationContext, "Letzter Upload erfolgreich (HTTP $responseCode).")
                } else if (responseCode == 401) {
                    TrackerPreferences.setStatus(applicationContext, "Geräteschlüssel ungültig oder widerrufen. Bitte in der Jagdkarte neu koppeln.")
                    return@withContext Result.failure()
                } else if (responseCode == 409) {
                    LocationOutbox.clear(applicationContext)
                    TrackerPreferences.setTracking(applicationContext, false)
                    TrackerPreferences.setStatus(applicationContext, "Jagdmodus ist aus. Tracking wurde gestoppt und gepufferte Positionen wurden gelöscht.")
                    applicationContext.stopService(
                        Intent(applicationContext, LocationTrackingService::class.java)
                    )
                    return@withContext Result.failure()
                } else if (responseCode in 400..499 && responseCode != 409) {
                    LocationOutbox.delete(applicationContext, pending.id)
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

    private fun postLocation(pending: PendingLocation, deviceToken: String): Int {
        val connection = URL(TrackerPreferences.endpoint(applicationContext)).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Only HTTPS endpoints are supported")

        return try {
            val body = pending.payload.toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}