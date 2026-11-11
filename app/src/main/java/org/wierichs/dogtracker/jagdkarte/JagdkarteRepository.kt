package org.wierichs.dogtracker.jagdkarte

import android.content.Context
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.Base64
import java.util.UUID
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
    val boundary: JSONObject?,
    val ownerEmail: String? = null
)

internal fun canDeleteHuntingProfile(profile: HuntingProfile, userId: String): Boolean =
    userId.isNotBlank() && profile.ownerId == userId

internal enum class MapPointKind { FACILITY, PARTICIPANT, DOG }

internal enum class HuntingParticipantRole(val id: String) {
    GUEST("guest"),
    EDITOR("editor"),
    OWNER("owner");

    companion object {
        fun fromId(id: String?): HuntingParticipantRole = entries.firstOrNull { it.id == id } ?: GUEST
    }
}

internal fun participantMarkerIconAsset(role: HuntingParticipantRole): String = when (role) {
    HuntingParticipantRole.GUEST -> "location-member.svg"
    HuntingParticipantRole.EDITOR -> "location-editor.svg"
    HuntingParticipantRole.OWNER -> "location-owner.svg"
}

internal fun dogLocationIconAsset(stale: Boolean): String =
    if (stale) "location-dog-stale.svg" else "location-dog.svg"

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
    val markerNotes: String = "",
    val participantRole: HuntingParticipantRole = HuntingParticipantRole.GUEST,
    val createdBy: String? = null,
    val subjectId: String? = null,
    val accuracyMeters: Double? = null
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
    val canEdit: Boolean,
    val cachedAt: Instant? = null,
    val isStale: Boolean = false
)

internal data class HuntingDog(val id: String, val name: String)

internal class JagdkarteApiException(val status: Int, val errorCode: String, message: String) : Exception(message)

internal fun participantNames(array: JSONArray): Map<String, String> = (0 until array.length()).associate { index ->
    val row = array.getJSONObject(index)
    val name = row.optString("display_name").trim().takeUnless { it.isBlank() || it == "null" }
        ?: row.optString("participant_email").trim().takeUnless { it.isBlank() || it == "null" }
        ?: "Teilnehmer"
    row.getString("participant_user_id") to name
}

internal fun participantRoles(array: JSONArray): Map<String, HuntingParticipantRole> =
    (0 until array.length()).associate { index ->
        val row = array.getJSONObject(index)
        row.getString("participant_user_id") to HuntingParticipantRole.fromId(row.optString("participant_role"))
    }

internal fun ownerEmailsByProfileId(array: JSONArray): Map<String, String> = buildMap {
    for (index in 0 until array.length()) {
        val row = array.getJSONObject(index)
        val email = row.optString("owner_email").trim()
        if (email.isNotBlank() && email != "null") put(row.getString("profile_id"), email)
    }
}

internal fun profileSelectionLabel(profile: HuntingProfile, userId: String): String =
    if (profile.ownerId == userId) {
        "${profile.name} (Eigen)"
    } else {
        "${profile.name} (Geteilt) @${profile.ownerEmail ?: profile.ownerId.take(8)}"
    }

internal fun displayNameWriteBody(userId: String, displayName: String): JSONObject {
    require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        .matches(userId)) { "Ungueltige Benutzerkennung." }
    val normalizedName = displayName.trim()
    require(normalizedName.length <= 60) { "Der Anzeigename darf hoechstens 60 Zeichen enthalten." }
    return JSONObject().put("user_id", userId)
        .put("display_name", normalizedName.ifBlank { JSONObject.NULL })
        .put("updated_at", Instant.now().toString())
}

internal enum class HuntingProfileMemberRole(val id: String, val label: String) {
    GUEST("guest", "Gast"),
    EDITOR("editor", "Bearbeiter");

    companion object {
        fun fromId(id: String?): HuntingProfileMemberRole = entries.firstOrNull { it.id == id } ?: GUEST
    }
}

private val INVITATION_EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$")

internal fun normalizeInvitationEmail(email: String): String {
    val normalizedEmail = email.trim().lowercase(Locale.ROOT)
    require(normalizedEmail.length in 3..254 && INVITATION_EMAIL_PATTERN.matches(normalizedEmail)) {
        "Bitte eine gueltige E-Mail-Adresse eingeben."
    }
    return normalizedEmail
}

internal fun inviteMemberRequestBody(profileId: String, email: String, role: HuntingProfileMemberRole): JSONObject {
    require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        .matches(profileId)) { "Ungueltige Revierkennung." }
    val normalizedEmail = normalizeInvitationEmail(email)
    return JSONObject().put("p_profile_id", profileId).put("p_email", normalizedEmail).put("p_role", role.id)
}

internal class InvitationEmailDeliveryException(message: String) : Exception(message)

internal data class GuestLinkTarget(val profileId: String, val markerId: String)

private val GUEST_LINK_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

internal fun createGuestLinkToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun guestLinkTokenFromFragment(fragment: String?): String? {
    val components = fragment?.removePrefix("#")?.split('&') ?: return null
    val token = components.firstNotNullOfOrNull { component ->
        val separator = component.indexOf('=')
        if (separator > 0 && component.substring(0, separator) == "gast") component.substring(separator + 1) else null
    }
    return token?.takeIf { GUEST_LINK_TOKEN_PATTERN.matches(it) }
}

internal fun guestLinkUrl(token: String): String {
    require(GUEST_LINK_TOKEN_PATTERN.matches(token)) { "Ungueltiger Gastlink-Token." }
    return "https://wierichs.github.io/Jagdkarte/#gast=$token"
}

internal fun guestLinkTargetFromJson(json: JSONObject): GuestLinkTarget {
    val profileId = json.optString("profile_id")
    val markerId = json.optString("marker_id")
    require(UUID_PATTERN.matches(profileId) && UUID_PATTERN.matches(markerId)) {
        "Gastlink enthaelt kein gueltiges Revierziel."
    }
    return GuestLinkTarget(profileId, markerId)
}

internal fun createdProfileIdFromResponse(response: String): String? {
    val objectId = runCatching { JSONObject(response).optString("id") }.getOrNull()
    val arrayId = if (objectId.isNullOrBlank() || objectId == "null") {
        runCatching { JSONArray(response).optJSONObject(0)?.optString("id") }.getOrNull()
    } else null
    return (objectId ?: arrayId)?.takeIf { UUID_PATTERN.matches(it) }
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

    suspend fun acceptProfileInvitations() = authenticated { token ->
        request("rest/v1/rpc/accept_hunting_profile_invitations", "POST", JSONObject(), token)
        Unit
    }

    suspend fun ownDisplayName(): String? = authenticated { token ->
        val result = JSONArray(request(
            "rest/v1/hunting_user_profiles?select=display_name&user_id=eq.${uuid(userId)}", bearer = token))
        result.optJSONObject(0)?.optString("display_name")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun saveOwnDisplayName(displayName: String) = authenticated { token ->
        val body = displayNameWriteBody(userId, displayName)
        request("rest/v1/hunting_user_profiles?on_conflict=user_id", "POST", body, token, upsert = true)
        Unit
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

    suspend fun profiles(): List<HuntingProfile> = authenticated(::loadProfiles)

    suspend fun editableProfiles(): List<HuntingProfile> = authenticated { token ->
        val profiles = loadProfiles(token)
        val memberships = JSONArray(request(
            "rest/v1/hunting_profile_members?select=profile_id,role&user_id=eq.${uuid(userId)}", bearer = token))
        val editableIds = (0 until memberships.length()).mapNotNull { index ->
            memberships.getJSONObject(index).takeIf { it.optString("role") == "editor" }
                ?.optString("profile_id")
        }.toSet()
        profiles.filter { it.ownerId == userId || it.id in editableIds }
    }

    private fun loadProfiles(token: String): List<HuntingProfile> {
        val profiles = parseProfiles(JSONArray(request(
            "rest/v1/hunting_profiles?select=id,district_name,owner_id,hunting_mode,boundary_geojson&order=district_name",
            bearer = token
        )))
        val sharedProfileIds = profiles.filter { it.ownerId != userId }.map { it.id }
        if (sharedProfileIds.isEmpty()) return profiles
        val ownerEmails = try {
            ownerEmailsByProfileId(JSONArray(request("rest/v1/rpc/get_hunting_profile_owner_emails", "POST",
                JSONObject().put("p_profile_ids", JSONArray(sharedProfileIds)), token)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        return profiles.map { it.copy(ownerEmail = ownerEmails[it.id]) }
    }

    suspend fun snapshot(profileId: String): HuntingSnapshot = authenticated { token ->
        val profile = loadProfile(token, profileId)
        val canEdit = canEditProfile(token, profile)
        val points = mutableListOf<HuntingMapPoint>()
        rows(request("rest/v1/hunting_markers?select=id,title,notes,type_id,latitude,longitude,expires_at,created_by&profile_id=eq.$profileId", bearer = token)) { row ->
            points += point(row, row.getString("title"), "${row.optString("type_id")}: ${row.optString("notes")}", MapPointKind.FACILITY)
                .copy(expiresAt = row.instant("expires_at"), markerId = row.getString("id"),
                    markerTypeId = row.optString("type_id"), markerNotes = row.optString("notes"),
                    createdBy = row.optString("created_by").takeIf { it.isNotBlank() && it != "null" })
        }
        if (profile.huntingMode) {
            val participantRows = JSONArray(request("rest/v1/rpc/get_hunting_profile_participants", "POST",
                JSONObject().put("p_profile_id", profileId), token))
            val participants = participantNames(participantRows)
            val participantRoles = participantRoles(participantRows)
            rows(request("rest/v1/hunting_profile_locations?select=user_id,latitude,longitude,accuracy,updated_at&profile_id=eq.$profileId", bearer = token)) { row ->
                val participantId = row.getString("user_id")
                points += point(row, participants[participantId] ?: "Teilnehmer",
                    "Genauigkeit: ${row.optDouble("accuracy").toInt()} m", MapPointKind.PARTICIPANT)
                    .copy(subjectId = participantId,
                        accuracyMeters = row.optDouble("accuracy", Double.NaN).takeIf(Double::isFinite),
                        timestamp = row.instant("updated_at"),
                        participantRole = participantRoles[participantId] ?: HuntingParticipantRole.GUEST)
            }
            val dogs = mutableMapOf<String, String>()
            rows(request("rest/v1/hunting_dogs?select=id,name&profile_id=eq.$profileId", bearer = token)) { row ->
                dogs[row.getString("id")] = row.getString("name")
            }
            rows(request("rest/v1/hunting_dog_locations?select=dog_id,latitude,longitude,accuracy,recorded_at&profile_id=eq.$profileId", bearer = token)) { row ->
                val recorded = row.instant("recorded_at")
                val dogId = row.getString("dog_id")
                points += point(row, dogs[dogId] ?: "Hund",
                    "Messzeit: ${recorded ?: "unbekannt"}\nGenauigkeit: ${row.optDouble("accuracy").toInt()} m", MapPointKind.DOG)
                    .copy(subjectId = dogId,
                        accuracyMeters = row.optDouble("accuracy", Double.NaN).takeIf(Double::isFinite),
                        timestamp = recorded)
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
        val path = markerDeletePath(profile, canEditProfile(token, profile), userId, marker)
        val deleted = JSONArray(request(path, "DELETE", bearer = token, returnRepresentation = true))
        check(deleted.length() == 1) { "Markierung nicht gefunden oder keine Berechtigung." }
        Unit
    }

    suspend fun moveMarkerToLocation(profileId: String, markerId: String, coordinates: MarkerCoordinates) = authenticated { token ->
        val profile = loadProfile(token, profileId)
        val move = markerMoveRequest(profile, canEditProfile(token, profile), markerId, coordinates)
        request(move.path, "POST", move.body, token)
        Unit
    }

    suspend fun inviteMember(profileId: String, email: String, role: HuntingProfileMemberRole) = authenticated { token ->
        val profile = loadProfile(token, profileId)
        check(canEditProfile(token, profile)) { "Nur Owner und Bearbeiter duerfen Benutzer einladen." }
        val body = inviteMemberRequestBody(profile.id, email, role)
        request("rest/v1/rpc/invite_hunting_profile_member", "POST", body, token)
        try {
            request("auth/v1/otp", "POST", JSONObject()
                .put("email", body.getString("p_email")).put("create_user", true))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw InvitationEmailDeliveryException(
                "Einladung gespeichert, E-Mail konnte nicht versendet werden: ${failure.message ?: "Versand fehlgeschlagen."}")
        }
        Unit
    }

    suspend fun createGuestLink(profileId: String, markerId: String): String = authenticated { token ->
        val profile = loadProfile(token, profileId)
        check(canEditProfile(token, profile)) { "Nur Owner und Bearbeiter duerfen Gastlinks erstellen." }
        require(UUID_PATTERN.matches(markerId)) { "Ungueltige Markierungskennung." }
        val guestToken = createGuestLinkToken()
        request("rest/v1/rpc/create_hunting_profile_guest_link", "POST", JSONObject()
            .put("p_profile_id", profile.id).put("p_marker_id", markerId).put("p_token", guestToken), token)
        guestLinkUrl(guestToken)
    }

    suspend fun acceptGuestLink(token: String): GuestLinkTarget = authenticated { accessToken ->
        require(GUEST_LINK_TOKEN_PATTERN.matches(token)) { "Gastlink ungueltig oder abgelaufen." }
        guestLinkTargetFromJson(JSONObject(request("rest/v1/rpc/accept_hunting_profile_guest_link", "POST",
            JSONObject().put("p_token", token), accessToken)))
    }

    suspend fun importJwdProfile(imported: JwdImportedProfile): String = authenticated { token ->
        val featureId = "jwd-${UUID.randomUUID()}"
        val createBody = JSONObject().put("p_source_id", "user-imports")
            .put("p_feature_id", featureId).put("p_district_name", imported.name)
        val createdProfile = request("rest/v1/rpc/create_hunting_profile", "POST", createBody, token)
        val profileId = createdProfileIdFromResponse(createdProfile)
            ?: error("Das Revierprofil wurde ohne Profil-ID angelegt.")
        try {
            request("rest/v1/rpc/set_hunting_profile_boundary", "POST", JSONObject()
                .put("p_profile_id", profileId).put("p_geometry", imported.boundary), token)
            if (imported.facilities.isNotEmpty()) {
                val markers = JSONArray()
                imported.facilities.forEach { facility ->
                    markers.put(JSONObject().put("profile_id", profileId).put("type_id", facility.typeId)
                        .put("title", facility.title).put("notes", facility.notes).put("created_by", userId)
                        .put("latitude", facility.latitude).put("longitude", facility.longitude))
                }
                request("rest/v1/hunting_markers", "POST", markers, token)
            }
        } catch (failure: Exception) {
            runCatching {
                request("rest/v1/hunting_profiles?id=eq.$profileId", "DELETE", bearer = token)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        profileId
    }

    suspend fun createPublicBoundaryProfile(source: PublicBoundarySource, feature: PublicBoundaryFeature): String = authenticated { token ->
        check(feature.source.id == source.id) { "Revier und Datenquelle passen nicht zusammen." }
        val sourceId = if (source.userDefined) "user-imports" else source.id
        val featureId = if (source.userDefined) "search-${UUID.randomUUID()}" else feature.id
        require(featureId.length <= 240) { "Die Revierkennung der Quelle ist zu lang." }
        val createdProfile = request("rest/v1/rpc/create_hunting_profile", "POST", JSONObject()
            .put("p_source_id", sourceId).put("p_feature_id", featureId)
            .put("p_district_name", feature.name), token)
        val profileId = createdProfileIdFromResponse(createdProfile)
            ?: error("Das Revierprofil wurde ohne Profil-ID angelegt.")
        try {
            request("rest/v1/rpc/set_hunting_profile_boundary", "POST", JSONObject()
                .put("p_profile_id", profileId).put("p_geometry", feature.geometry), token)
        } catch (failure: Exception) {
            runCatching { request("rest/v1/hunting_profiles?id=eq.$profileId", "DELETE", bearer = token) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        profileId
    }

    suspend fun mergeJwdProfile(profileId: String, imported: JwdImportedProfile): String = authenticated { token ->
        val existingProfile = loadProfile(token, profileId)
        check(canEditProfile(token, existingProfile)) { "Nur Owner und Bearbeiter duerfen Revierdaten zusammenfuehren." }
        val mergedBoundary = mergeRevierBoundaries(existingProfile.boundary, imported.boundary)
        val existingFacilities = JSONArray(request(
            "rest/v1/hunting_markers?select=type_id,title,notes,latitude,longitude&profile_id=eq.${uuid(profileId)}&expires_at=is.null",
            bearer = token))
        val knownFacilities = (0 until existingFacilities.length()).map { index ->
            val row = existingFacilities.getJSONObject(index)
            jwdFacilityMergeKey(row.getString("type_id"), row.getString("title"), row.optString("notes"),
                row.getDouble("latitude"), row.getDouble("longitude"))
        }.toMutableSet()
        val facilitiesToAdd = imported.facilities.filter { knownFacilities.add(it.mergeKey()) }
        val insertedIds = facilitiesToAdd.map { UUID.randomUUID().toString() }
        val markerRows = JSONArray()
        facilitiesToAdd.forEachIndexed { index, facility ->
            markerRows.put(JSONObject().put("id", insertedIds[index]).put("profile_id", profileId)
                .put("type_id", facility.typeId).put("title", facility.title).put("notes", facility.notes)
                .put("created_by", userId).put("latitude", facility.latitude).put("longitude", facility.longitude))
        }
        try {
            if (markerRows.length() > 0) request("rest/v1/hunting_markers", "POST", markerRows, token)
            request("rest/v1/rpc/set_hunting_profile_boundary", "POST", JSONObject()
                .put("p_profile_id", profileId).put("p_geometry", mergedBoundary), token)
        } catch (failure: Exception) {
            if (insertedIds.isNotEmpty()) {
                val insertedIdsFilter = insertedIds.joinToString(",")
                runCatching {
                    request("rest/v1/hunting_markers?id=in.($insertedIdsFilter)&profile_id=eq.${uuid(profileId)}",
                        "DELETE", bearer = token)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        profileId
    }

    suspend fun updateBoundary(profileId: String, original: BoundaryDraft, draft: BoundaryDraft) = authenticated { token ->
        val geometry = draft.validatedGeometry()
        val profile = loadProfile(token, profileId)
        check(canEditProfile(token, profile)) { "Nur Owner und Bearbeiter duerfen die Reviergrenze bearbeiten." }
        check(profile.boundary?.let(BoundaryDraft::fromGeometry) == original) {
            "Die Reviergrenze wurde inzwischen geaendert. Bitte den Editor schliessen und das Revier neu laden."
        }
        request("rest/v1/rpc/set_hunting_profile_boundary", "POST", JSONObject()
            .put("p_profile_id", profile.id).put("p_geometry", geometry), token)
        Unit
    }

    suspend fun deleteProfile(profileId: String) = authenticated { token ->
        val profile = loadProfile(token, profileId)
        check(canDeleteHuntingProfile(profile, userId)) { "Nur der Owner darf dieses Revier loeschen." }
        val deleted = JSONArray(request("rest/v1/hunting_profiles?id=eq.${uuid(profile.id)}", "DELETE",
            bearer = token, returnRepresentation = true))
        check(deleted.length() == 1) { "Revier nicht gefunden oder keine Berechtigung zum Loeschen." }
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

    suspend fun deleteDog(dogId: String) = authenticated { token ->
        request("rest/v1/rpc/delete_hunting_dog", "POST", JSONObject()
            .put("p_dog_id", uuid(dogId)), token)
        Unit
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

    suspend fun registerDogDeviceToken(dogId: String, deviceToken: String) = authenticated { token ->
        require(Regex("^[0-9a-f]{64}$").matches(deviceToken)) { "Ungueltiger Geraeteschluessel." }
        request("rest/v1/rpc/register_hunting_dog_device_token", "POST", JSONObject()
            .put("p_dog_id", uuid(dogId)).put("p_device_token", deviceToken), token)
        Unit
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

    private fun request(path: String, method: String = "GET", body: Any? = null, bearer: String? = null,
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