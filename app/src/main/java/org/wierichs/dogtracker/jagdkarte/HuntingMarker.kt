package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject

internal enum class HuntingMarkerType(
    val id: String,
    val label: String,
    val temporary: Boolean = false,
    val requiresCurrentLocation: Boolean = false
) {
    HOCHSITZ("hochsitz", "Hochsitz"),
    BOCK("bock", "Bock"),
    ERDSITZ("erdsitz", "Erdsitz"),
    SALZLECKE("salzlecke", "Salzlecke"),
    HUETTE("huette", "Jagdhuette"),
    LEITER("leiter", "Leiter"),
    KANZEL("kanzel", "Kanzel"),
    WAGEN("wagen", "Wagen"),
    KIRRUNG("kirrung", "Kirrung"),
    FALLE("falle", "Falle"),
    WILDKAMERA("wildkamera", "Wildkamera"),
    ANSCHUSS("anschuss", "Anschuss", temporary = true, requiresCurrentLocation = true),
    WILDSICHTUNG("wildsichtung", "Wildsichtung", temporary = true, requiresCurrentLocation = true),
    STANDPLATZ("standplatz", "Standplatz", temporary = true),
    SONSTIGES("sonstiges", "Sonstiges");

    fun canCreate(canEdit: Boolean): Boolean = canEdit || requiresCurrentLocation
}

internal data class MarkerCoordinates(val latitude: Double, val longitude: Double)

internal data class HuntingMarkerDraft(
    val type: HuntingMarkerType,
    val title: String,
    val notes: String,
    val coordinates: MarkerCoordinates
)

internal data class MarkerWriteRequest(val path: String, val body: JSONObject)

internal fun markerWriteRequest(
    profile: HuntingProfile,
    canEdit: Boolean,
    userId: String,
    draft: HuntingMarkerDraft,
    currentLocation: MarkerCoordinates?
): MarkerWriteRequest {
    check(userId.isNotBlank()) { "Anmeldung erforderlich." }
    check(draft.type.canCreate(canEdit)) { "Nur Owner und Bearbeiter duerfen diese Markierung setzen." }
    val title = draft.title.trim().ifBlank { draft.type.label }
    val notes = draft.notes.trim()
    require(title.length <= 80) { "Die Bezeichnung darf hoechstens 80 Zeichen enthalten." }
    require(notes.length <= 300) { "Die Notiz darf hoechstens 300 Zeichen enthalten." }
    require(locationInsideBoundary(profile.boundary, draft.coordinates.latitude, draft.coordinates.longitude)) {
        "Die Markierung muss innerhalb der Reviergrenze liegen."
    }
    if (draft.type.requiresCurrentLocation) {
        check(currentLocation != null) { "Ein aktueller GPS-Standort ist erforderlich." }
        check(locationInsideBoundary(profile.boundary, currentLocation.latitude, currentLocation.longitude)) {
            "Dein aktueller Standort liegt ausserhalb des Reviers."
        }
    }
    return if (draft.type.temporary) {
        MarkerWriteRequest("rest/v1/rpc/create_temporary_hunting_marker", JSONObject()
            .put("p_profile_id", profile.id).put("p_type_id", draft.type.id)
            .put("p_title", title).put("p_notes", notes)
            .put("p_latitude", draft.coordinates.latitude).put("p_longitude", draft.coordinates.longitude)
            .put("p_user_latitude", if (draft.type.requiresCurrentLocation) currentLocation!!.latitude else JSONObject.NULL)
            .put("p_user_longitude", if (draft.type.requiresCurrentLocation) currentLocation!!.longitude else JSONObject.NULL))
    } else {
        MarkerWriteRequest("rest/v1/hunting_markers", JSONObject()
            .put("profile_id", profile.id).put("type_id", draft.type.id)
            .put("title", title).put("notes", notes).put("created_by", userId)
            .put("latitude", draft.coordinates.latitude).put("longitude", draft.coordinates.longitude))
    }
}

internal fun markerUpdateRequest(
    profile: HuntingProfile,
    canEdit: Boolean,
    markerId: String,
    title: String,
    notes: String
): MarkerWriteRequest {
    check(canEdit) { "Nur Owner und Bearbeiter duerfen Markierungen bearbeiten." }
    require(MARKER_ID_PATTERN.matches(markerId)) { "Ungueltige Markierung." }
    val normalizedTitle = title.trim()
    val normalizedNotes = notes.trim()
    require(normalizedTitle.isNotEmpty()) { "Die Bezeichnung darf nicht leer sein." }
    require(normalizedTitle.length <= 80) { "Die Bezeichnung darf hoechstens 80 Zeichen enthalten." }
    require(normalizedNotes.length <= 300) { "Die Notiz darf hoechstens 300 Zeichen enthalten." }
    return MarkerWriteRequest("rest/v1/hunting_markers?id=eq.$markerId&profile_id=eq.${profile.id}",
        JSONObject().put("title", normalizedTitle).put("notes", normalizedNotes))
}

internal fun markerDeletePath(profile: HuntingProfile, canEdit: Boolean, markerId: String): String {
    check(canEdit) { "Nur Owner und Bearbeiter duerfen Markierungen loeschen." }
    require(MARKER_ID_PATTERN.matches(markerId)) { "Ungueltige Markierung." }
    return "rest/v1/hunting_markers?id=eq.$markerId&profile_id=eq.${profile.id}"
}

private val MARKER_ID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")