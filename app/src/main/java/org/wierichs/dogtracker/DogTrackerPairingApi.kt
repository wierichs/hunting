package org.wierichs.dogtracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object DogTrackerPairingApi {
    suspend fun complete(token: String, confirm: Boolean): String = withContext(Dispatchers.IO) {
        require(Regex("^[0-9a-f]{64}$").matches(token)) { "Ungueltiger Geraeteschluessel." }
        val connection = URL("${BackendConfiguration.baseUrl}/rest/v1/rpc/complete_hunting_dog_device_pairing")
            .openConnection() as? HttpsURLConnection
            ?: throw IOException("Nur HTTPS-Server werden fuer die Geraetekopplung unterstuetzt.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", BackendConfiguration.publicKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use {
                it.write(JSONObject()
                    .put("p_device_token", token)
                    .put("p_confirm", confirm)
                    .toString()
                    .toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Server konnte die Geraetekopplung nicht abschliessen (HTTP $status).")
            JSONTokener(content).nextValue() as? String
                ?: throw IOException("Ungueltige Antwort beim Abschluss der Geraetekopplung.")
        } finally {
            connection.disconnect()
        }
    }
}
