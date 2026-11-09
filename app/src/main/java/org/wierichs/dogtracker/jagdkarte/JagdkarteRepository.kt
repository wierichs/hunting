package org.wierichs.dogtracker.jagdkarte

import android.content.Context
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.wierichs.dogtracker.DeviceTokenStorage
import org.wierichs.dogtracker.BackendConfiguration

internal data class HuntingProfile(
    val id: String,
    val name: String,
    val ownerId: String,
    val huntingMode: Boolean,
    val boundary: JSONObject?
)

internal enum class MapPointKind { FACILITY, PARTICIPANT, DOG }

internal data class HuntingMapPoint(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val detail: String,
    val kind: MapPointKind,
    val timestamp: Instant? = null,
    val expiresAt: Instant? = null,
    val markerId: String? = null,
    val markerTypeId: String? = null,
    val markerNotes: String = ""
) {
    fun visibleAt(now: Instant): Boolean = when {
        !latitude.isFinite() || !longitude.isFinite() -> false
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> false
        expiresAt != null && !expiresAt.isAfter(now) -> false
        kind == MapPointKind.PARTICIPANT -> timestamp != null &&
            !timestamp.isAfter(now.plusSeconds(30)) && timestamp.isAfter(now.minusSeconds(90))
        else -> true
    }

    fun staleAt(now: Instant): Boolean = kind == MapPointKind.DOG &&
        (timestamp == null || timestamp.isBefore(now.minusSeconds(120)) || timestamp.isAfter(now.plusSeconds(30)))
}

internal data class HuntingSnapshot(
    val profile: HuntingProfile,
    val points: List<HuntingMapPoint>,
    val canEdit: Boolean
)

internal data class HuntingDog(val id: String, val name: String)

internal class JagdkarteApiException(val status: Int, val errorCode: String, message: String) : Exception(message)

internal fun participantNames(array: JSONArray): Map<String, String> = (0 until array.length()).associate { index ->
    val row = array.getJSONObject(index)
    val name = row.optString("display_name").takeUnless { it.isBlank() || it == "null" }
        ?: row.optString("participant_email").takeUnless { it.isBlank() || it == "null" }
        ?: "Teilnehmer"
    row.getString("participant_user_id") to name
}

internal class JagdkarteRepository(context: Context) {
    private val appContext = context.applicationContext
    private var session = DeviceTokenStorage.read(appContext, SESSION_FILE)?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }

    val signedIn: Boolean get() = session != null
    val email: String get() = session?.optJSONObject("user")?.optString("email").orEmpty()
    val userId: String get() = session?.optJSONObject("user")?.optString("id").orEmpty()

    suspend fun requestCode(email: String, register: Boolean) = withContext(Dispatchers.IO) {
        request("auth/v1/otp", "POST", JSONObject()
            .put("email", email.trim()).put("create_user", register))
        Unit
    }

    suspend fun verifyCode(email: String, code: String) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            saveSession(JSONObject(request("auth/v1/verify", "POST", JSONObject()
                .put("email", email.trim()).put("token", code.trim()).put("type", "email"))))
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            try {
                session?.optString("access_token")?.let {
                    request("auth/v1/logout?scope=local", "POST", bearer = it)
                }
            } finally {
                session = null
                DeviceTokenStorage.clear(appContext, SESSION_FILE)
            }
        }
    }

    suspend fun profiles(): List<HuntingProfile> = authenticated { token ->
        parseProfiles(JSONArray(request(
            "rest/v1/hunting_profiles?select=id,district_name,owner_id,hunting_mode,boundary_geojson&order=district_name",
            bearer = token
        )))
    }

    suspend fun snapshot(profileId: String): HuntingSnapshot = authenticated { token ->
        val profile = loadProfile(token, profileId)
        val canEdit = canEditProfile(token, profile)
        val points = mutableListOf<HuntingMapPoint>()
        rows(request("rest/v1/hunting_markers?select=id,title,notes,type_id,latitude,longitude,expires_at&profile_id=eq.$profileId", bearer = token)) { row ->
            points += point(row, row.getString("title"), "${row.optString("type_id")}: ${row.optString("notes")}", MapPointKind.FACILITY)
                .copy(expiresAt = row.instant("expires_at"), markerId = row.getString("id"),
                    markerTypeId = row.optString("type_id"), markerNotes = row.optString("notes"))
        }
        if (profile.huntingMode) {
            val participants = participantNames(JSONArray(request("rest/v1/rpc/get_hunting_profile_participants", "POST",
                JSONObject().put("p_profile_id", profileId), token)))
            rows(request("rest/v1/hunting_profile_locations?select=user_id,latitude,longitude,accuracy,updated_at&profile_id=eq.$profileId", bearer = token)) { row ->
                points += point(row, participants[row.getString("user_id")] ?: "Teilnehmer",
                    "Genauigkeit: ${row.optDouble("accuracy").toInt()} m", MapPointKind.PARTICIPANT)
                    .copy(timestamp = row.instant("updated_at"))
            }
            val dogs = mutableMapOf<String, String>()
            rows(request("rest/v1/hunting_dogs?select=id,name&profile_id=eq.$profileId", bearer = token)) { row ->
                dogs[row.getString("id")] = row.getString("name")
            }
            rows(request("rest/v1/hunting_dog_locations?select=dog_id,latitude,longitude,accuracy,recorded_at&profile_id=eq.$profileId", bearer = token)) { row ->
                val recorded = row.instant("recorded_at")
                points += point(row, dogs[row.getString("dog_id")] ?: "Hund",
                    "Messzeit: ${recorded ?: "unbekannt"}\nGenauigkeit: ${row.optDouble("accuracy").toInt()} m", MapPointKind.DOG)
                    .copy(timestamp = recorded)
            }
        }
        HuntingSnapshot(profile, points, canEdit)
    }

    suspend fun createMarker(profileId: String, draft: HuntingMarkerDraft, currentLocation: MarkerCoordinates?) = authenticated { token ->
        val profile = loadProfile(token, profileId)
        val write = markerWriteRequest(profile, canEditProfile(token, profile), userId, draft, currentLocation)
        request(write.path, "POST", write.body, token)
        Unit
    }

    suspend fun updateMarker(profileId: String, marker: HuntingMapPoint, title: String, notes: String) = authenticated { token ->
        val profile = loadProfile(token, profileId)
        val write = markerUpdateRequest(profile, canEditProfile(token, profile), marker.markerId ?: error("Markierung nicht gefunden."),
            title, notes)
        val changed = JSONArray(request(write.path, "PATCH", write.body, token, returnRepresentation = true))
        check(changed.length() == 1) { "Markierung nicht gefunden oder keine Berechtigung." }
        Unit
    }

    suspend fun deleteMarker(profileId: String, marker: HuntingMapPoint) = authenticated { token ->
        val profile = loadProfile(token, profileId)
        val path = markerDeletePath(profile, canEditProfile(token, profile), marker.markerId ?: error("Markierung nicht gefunden."))
        val deleted = JSONArray(request(path, "DELETE", bearer = token, returnRepresentation = true))
        check(deleted.length() == 1) { "Markierung nicht gefunden oder keine Berechtigung." }
        Unit
    }

    private fun loadProfile(token: String, profileId: String): HuntingProfile = parseProfiles(JSONArray(request(
        "rest/v1/hunting_profiles?select=id,district_name,owner_id,hunting_mode,boundary_geojson&id=eq.${uuid(profileId)}",
        bearer = token
    ))).singleOrNull() ?: error("Kein Zugriff auf dieses Revier.")

    private fun canEditProfile(token: String, profile: HuntingProfile): Boolean {
        if (profile.ownerId == userId) return true
        val membership = JSONArray(request(
            "rest/v1/hunting_profile_members?select=role&profile_id=eq.${uuid(profile.id)}&user_id=eq.${uuid(userId)}", bearer = token))
        return membership.optJSONObject(0)?.optString("role") == "editor"
    }

    suspend fun setHuntingMode(profileId: String, enabled: Boolean) = authenticated { token ->
        request("rest/v1/rpc/set_hunting_mode", "POST", JSONObject()
            .put("p_profile_id", uuid(profileId)).put("p_enabled", enabled), token)
        Unit
    }

    suspend fun dogs(profileId: String): List<HuntingDog> = authenticated { token ->
        val result = mutableListOf<HuntingDog>()
        rows(request("rest/v1/hunting_dogs?select=id,name&profile_id=eq.${uuid(profileId)}&order=name", bearer = token)) {
            result += HuntingDog(it.getString("id"), it.getString("name"))
        }
        result
    }

    suspend fun createDog(profileId: String, name: String) = authenticated { token ->
        require(name.trim().length in 1..60)
        request("rest/v1/rpc/create_hunting_dog", "POST", JSONObject()
            .put("p_profile_id", uuid(profileId)).put("p_name", name.trim()), token)
        Unit
    }

    suspend fun pairDog(dogId: String): String = authenticated { token ->
        val result = JSONArray(request("rest/v1/rpc/create_hunting_dog_device", "POST", JSONObject()
            .put("p_dog_id", uuid(dogId)), token))
        result.getJSONObject(0).getString("device_token").also {
            check(Regex("[0-9a-f]{64}").matches(it)) { "Ungueltiger Geraeteschluessel in der Serverantwort." }
        }
    }

    suspend fun shareLocation(profileId: String, latitude: Double, longitude: Double, accuracy: Float): Boolean = authenticated { token ->
        require(accuracy.isFinite() && accuracy >= 0)
        val profile = parseProfiles(JSONArray(request(
            "rest/v1/hunting_profiles?select=id,district_name,owner_id,hunting_mode,boundary_geojson&id=eq.${uuid(profileId)}",
            bearer = token
        ))).singleOrNull() ?: error("Kein Zugriff auf dieses Revier.")
        if (!profile.huntingMode || !locationInsideBoundary(profile.boundary, latitude, longitude)) {
            removeLocation(token, profileId)
            false
        } else {
            request("rest/v1/hunting_profile_locations?on_conflict=profile_id,user_id", "POST", JSONObject()
                .put("profile_id", profileId).put("user_id", userId)
                .put("latitude", latitude).put("longitude", longitude).put("accuracy", accuracy.toDouble())
                .put("updated_at", Instant.now().toString()), token, upsert = true)
            true
        }
    }

    suspend fun removeLocation(profileId: String) = authenticated { token -> removeLocation(token, profileId) }

    suspend fun relayPost(body: JSONObject): JSONObject = authenticated { token ->
        JSONObject(request("functions/v1/location-relay", "POST", body, token))
    }

    private fun removeLocation(token: String, profileId: String) {
        request("rest/v1/hunting_profile_locations?profile_id=eq.${uuid(profileId)}&user_id=eq.${uuid(userId)}",
            "DELETE", bearer = token)
    }

    private suspend fun <T> authenticated(block: (String) -> T): T = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            session = DeviceTokenStorage.read(appContext, SESSION_FILE)?.let { JSONObject(it) }
            val current = session ?: error("Bitte erneut anmelden.")
            if (current.optLong("expires_at") <= Instant.now().epochSecond + 60) {
                saveSession(JSONObject(request("auth/v1/token?grant_type=refresh_token", "POST",
                    JSONObject().put("refresh_token", current.getString("refresh_token")))))
            }
            block(session!!.getString("access_token"))
        }
    }

    private fun saveSession(value: JSONObject) {
        require(value.getString("access_token").isNotBlank() && value.getString("refresh_token").isNotBlank())
        if (!value.has("expires_at")) value.put("expires_at", Instant.now().epochSecond + value.getLong("expires_in"))
        DeviceTokenStorage.write(appContext, value.toString(), SESSION_FILE)
        session = value
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, bearer: String? = null,
        upsert: Boolean = false, returnRepresentation: Boolean = false): String {
        val connection = URL("$BASE_URL/$path").openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("apikey", PUBLISHABLE_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (upsert) connection.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            else if (returnRepresentation) connection.setRequestProperty("Prefer", "return=representation")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val reason = when (status) {
                    400, 422 -> "Die Anfrage wurde abgelehnt. Eingaben und Berechtigungen pruefen."
                    401 -> "Sitzung oder Code ungueltig. Bitte erneut anmelden."
                    403 -> "Keine Berechtigung fuer diese Aktion."
                    429 -> "Zu viele Anfragen. Bitte spaeter erneut versuchen."
                    else -> "Server derzeit nicht erreichbar."
                }
                val code = runCatching { JSONObject(content).optString("error") }.getOrDefault("")
                    .takeIf { Regex("[a-z_]{1,64}").matches(it) }.orEmpty()
                throw JagdkarteApiException(status, code, "$reason (HTTP $status)")
            }
            content
        } finally {
            connection.disconnect()
        }
    }

    private fun parseProfiles(array: JSONArray): List<HuntingProfile> = (0 until array.length()).map { index ->
        val row = array.getJSONObject(index)
        HuntingProfile(row.getString("id"), row.getString("district_name"), row.getString("owner_id"),
            row.optBoolean("hunting_mode"), row.optJSONObject("boundary_geojson"))
    }

    private fun rows(json: String, block: (JSONObject) -> Unit) {
        val array = JSONArray(json)
        for (index in 0 until array.length()) block(array.getJSONObject(index))
    }

    private fun point(row: JSONObject, title: String, detail: String, kind: MapPointKind) = HuntingMapPoint(
        row.getDouble("latitude"), row.getDouble("longitude"), title, detail, kind
    )

    private fun JSONObject.instant(key: String): Instant? = optString(key).takeIf { it.isNotBlank() && it != "null" }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun uuid(value: String): String {
        require(Regex("[0-9a-fA-F-]{36}").matches(value))
        return value
    }

    companion object {
        private val sessionMutex = Mutex()
        private const val SESSION_FILE = "jagdkarte_auth_session"
        private val BASE_URL = BackendConfiguration.baseUrl
        private val PUBLISHABLE_KEY = BackendConfiguration.publicKey
    }
}