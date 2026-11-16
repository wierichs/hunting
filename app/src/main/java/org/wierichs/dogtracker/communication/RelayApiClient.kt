package org.wierichs.dogtracker.communication

import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.wierichs.dogtracker.BackendConfiguration

internal data class RelayServerEncryptionKey(
    val keyId: String,
    val publicKey: String
)

internal class RelayApiClient(
    private val endpoint: String,
    private val deviceToken: String
) {
    fun registerSigningKey(publicKey: String): String {
        val response = post(
            body = JSONObject()
                .put("action", "register_key")
                .put("public_key", publicKey),
            authenticated = true
        )
        if (!response.optBoolean("registered")) {
            throw IllegalStateException("Relay signing key registration was not accepted")
        }
        return response.optString("device_id")
            .takeIf { UUID_PATTERN.matches(it) }
            ?: throw IllegalStateException("Relay registration returned no valid device ID")
    }

    fun fetchServerEncryptionKey(): RelayServerEncryptionKey {
        val response = post(
            body = JSONObject().put("action", "get_server_key"),
            authenticated = false
        )
        val keyId = response.optString("key_id")
        val publicKey = response.optString("public_key")
        if (!KEY_ID_PATTERN.matches(keyId) || !isValidBase64UrlPublicKey(publicKey)) {
            throw IllegalStateException("Relay server returned an invalid encryption key")
        }
        return RelayServerEncryptionKey(keyId, publicKey)
    }

    fun fetchOfflineCapabilities(): RelayCapabilities =
        RelayCapabilities.parse(post(JSONObject().put("action", "capabilities"), false, BackendConfiguration.relayEndpoint))

    fun fetchOfflineDirectory(deviceId: String): JSONObject =
        post(JSONObject().put("action", "offline_directory").put("device_id", deviceId), true, BackendConfiguration.relayEndpoint)

    fun submitRelayEnvelope(envelope: RelayEnvelope, authorization: DogRelayAuthorization.Authorization): Int {
        val directory = authorization.directory
        check(directory.accepts(envelope)) { "Relay-Ursprung ist nicht aktuell für dieses Revier autorisiert." }
        val response = try {
            post(JSONObject().put("action", "relay").put("device_id", directory.localDeviceId)
                .put("envelope", envelope.toJson()), true, BackendConfiguration.relayEndpoint)
        } catch (failure: RelayApiException) {
            return failure.statusCode
        }
        verifyRelayUploadReceipt(response, authorization.capabilities, directory.profileId, envelope)
        return 200
    }

    private fun post(body: JSONObject, authenticated: Boolean, url: String = endpoint): JSONObject {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Only HTTPS relay endpoints are supported")
        return try {
            val requestBody = body.toString().toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) {
                connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            }
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.outputStream.use { it.write(requestBody) }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val errorCode = runCatching { JSONObject(responseBody).optString("error") }.getOrDefault("")
                throw RelayApiException(responseCode, errorCode)
            }
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidBase64UrlPublicKey(encoded: String): Boolean {
        if (!BASE64_URL_PATTERN.matches(encoded)) return false
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(encoded)
            decoded.size in MIN_RSA_SPKI_BYTES..MAX_RSA_SPKI_BYTES
        }.getOrDefault(false)
    }

    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val KEY_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private const val MIN_RSA_SPKI_BYTES = 256
        private const val MAX_RSA_SPKI_BYTES = 1024
    }
}

internal fun verifyRelayUploadReceipt(
    response: JSONObject,
    capabilities: RelayCapabilities,
    profileId: String,
    envelope: RelayEnvelope
) {
    val receipt = VerifiedRelayReceipt.verify(response, capabilities, profileId)
    check(receipt.matches(envelope)) { "Relay-Serverbestätigung passt nicht zur übertragenen Meldung." }
}

internal class RelayApiException(
    val statusCode: Int,
    val errorCode: String
) : Exception("Relay API returned HTTP $statusCode${errorCode.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")