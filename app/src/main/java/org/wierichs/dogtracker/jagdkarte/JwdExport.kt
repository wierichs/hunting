package org.wierichs.dogtracker.jagdkarte

import org.json.JSONArray
import org.json.JSONObject

private val JWD_MARKER_TYPE_LABELS = mapOf(
    "hochsitz" to "Hochsitz",
    "bock" to "Bock",
    "erdsitz" to "Erdsitz",
    "kirrung" to "Kirrung",
    "leiter" to "Leiter",
    "salzlecke" to "Salzlecke"
)

internal fun jwdFileName(name: String): String {
    val safeName = name.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_")
        .replace(Regex("[. ]+$"), "").trim()
    return "${safeName.ifBlank { "jagdrevier" }}.jwd"
}

internal fun createJwdExport(profile: HuntingProfile, points: List<HuntingMapPoint>, includeMarkers: Boolean): JSONObject {
    val boundary = profile.boundary ?: throw IllegalArgumentException("Fuer dieses Revier ist keine Grenze hinterlegt.")
    val coordinates = boundary.optJSONArray("coordinates")
        ?: throw IllegalArgumentException("Die Reviergrenze enthaelt keine Koordinaten.")
    val polygons = when (boundary.optString("type")) {
        "Polygon" -> listOf(coordinates)
        "MultiPolygon" -> (0 until coordinates.length()).map { coordinates.getJSONArray(it) }
        else -> throw IllegalArgumentException("Die Reviergrenze ist kein Polygon.")
    }
    require(polygons.isNotEmpty()) { "Die Reviergrenze enthaelt keine Flaechen." }

    var minLatitude = Double.POSITIVE_INFINITY
    var maxLatitude = Double.NEGATIVE_INFINITY
    var minLongitude = Double.POSITIVE_INFINITY
    var maxLongitude = Double.NEGATIVE_INFINITY

    fun convertRing(ring: JSONArray, label: String): JSONArray {
        require(ring.length() >= 4) { "$label enthaelt zu wenige Grenzpunkte." }
        val converted = JSONArray()
        for (index in 0 until ring.length()) {
            val coordinate = ring.getJSONArray(index)
            require(coordinate.length() >= 2) { "$label enthaelt ungueltige Koordinaten." }
            val longitude = coordinate.getDouble(0)
            val latitude = coordinate.getDouble(1)
            require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                "$label enthaelt ungueltige Koordinaten."
            }
            if (index < ring.length() - 1) {
                minLatitude = minOf(minLatitude, latitude)
                maxLatitude = maxOf(maxLatitude, latitude)
                minLongitude = minOf(minLongitude, longitude)
                maxLongitude = maxOf(maxLongitude, longitude)
            }
            converted.put(JSONObject().put("latitude", latitude).put("longitude", longitude))
        }
        return converted
    }

    val borders = JSONArray()
    val pacifiedAreas = JSONArray()
    polygons.forEachIndexed { polygonIndex, polygon ->
        require(polygon.length() > 0) { "Revierflaeche ${polygonIndex + 1} enthaelt keinen Aussenring." }
        borders.put(convertRing(polygon.getJSONArray(0), "Revierflaeche ${polygonIndex + 1}"))
        for (ringIndex in 1 until polygon.length()) {
            pacifiedAreas.put(convertRing(polygon.getJSONArray(ringIndex), "Befriedeter Bereich ${ringIndex}"))
        }
    }
    require(minLatitude.isFinite() && minLongitude.isFinite()) { "Die Reviergrenze enthaelt keine gueltigen Koordinaten." }

    val facilities = JSONArray()
    if (includeMarkers) {
        points.asSequence()
            .filter { it.kind == MapPointKind.FACILITY && it.expiresAt == null &&
                HuntingMarkerType.entries.firstOrNull { type -> type.id == it.markerTypeId }?.temporary != true }
            .forEach { marker ->
                val typeId = marker.markerTypeId.orEmpty()
                val exportType = JWD_MARKER_TYPE_LABELS[typeId]
                val originalType = HuntingMarkerType.entries.firstOrNull { it.id == typeId }?.label
                    ?: typeId.ifBlank { "Unbekannt" }
                val title = marker.title.trim().ifBlank { originalType }
                facilities.put(JSONObject()
                    .put("coordinate", JSONObject().put("latitude", marker.latitude).put("longitude", marker.longitude))
                    .put("type", exportType ?: "Hochsitz")
                    .put("title", if (exportType != null) title else "$originalType: $title")
                    .put("notes", marker.markerNotes))
            }
    }

    return JSONObject()
        .put("lat", (minLatitude + maxLatitude) / 2.0)
        .put("lng", (minLongitude + maxLongitude) / 2.0)
        .put("title", profile.name)
        .put("huntingGround", JSONObject()
            .put("borders", borders)
            .put("pacifiedAreas", pacifiedAreas)
            .put("facilities", facilities))
}