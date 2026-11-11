package org.wierichs.dogtracker.jagdkarte

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.io.geojson.GeoJsonWriter
import kotlin.math.roundToLong
import java.util.Locale

internal data class JwdImportedFacility(
    val typeId: String,
    val title: String,
    val notes: String,
    val latitude: Double,
    val longitude: Double
)

internal data class JwdFacilityMergeKey(
    val typeId: String,
    val title: String,
    val notes: String,
    val latitudeE6: Long,
    val longitudeE6: Long
)

internal fun jwdFacilityMergeKey(typeId: String, title: String, notes: String,
    latitude: Double, longitude: Double) = JwdFacilityMergeKey(
    typeId, title.trim(), notes, (latitude * 1_000_000).roundToLong(), (longitude * 1_000_000).roundToLong())

internal fun JwdImportedFacility.mergeKey() =
    jwdFacilityMergeKey(typeId, title, notes, latitude, longitude)

internal fun jwdProfileNameKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase(Locale.ROOT)

internal fun suggestedJwdMergeTargetId(
    importedName: String,
    profiles: List<HuntingProfile>,
    currentUserId: String
): String? = profiles.filter { it.ownerId == currentUserId &&
    jwdProfileNameKey(it.name) == jwdProfileNameKey(importedName) }.singleOrNull()?.id

internal data class JwdImportedProfile(
    val name: String,
    val boundary: JSONObject,
    val facilities: List<JwdImportedFacility>,
    val pacifiedAreaCount: Int,
    val outsideBoundaryFacilityCount: Int = 0,
    val untitledFacilityCount: Int = 0,
    val unknownTypeCount: Int = 0
)

internal fun mergeRevierBoundaries(existingBoundary: JSONObject?, importedBoundary: JSONObject): JSONObject {
    if (existingBoundary == null) return JSONObject(importedBoundary.toString())
    val reader = GeoJsonReader()
    val existing = reader.read(existingBoundary.toString())
    val imported = reader.read(importedBoundary.toString())
    require(existing.isValid && !existing.isEmpty && imported.isValid && !imported.isEmpty) {
        "Bestehende oder importierte Reviergrenze ist ungueltig."
    }
    val merged = existing.union(imported)
    require(merged.isValid && !merged.isEmpty && merged.geometryType in setOf("Polygon", "MultiPolygon")) {
        "Reviergrenzen konnten nicht vereinigt werden."
    }
    return JSONObject(GeoJsonWriter().write(merged))
}

private data class JwdRing(val coordinates: JSONArray, val geometry: Geometry)

private val GEOJSON_ROOT_TYPES = setOf("Feature", "FeatureCollection", "Polygon", "MultiPolygon")
private val GEOJSON_NAME_KEYS = setOf("name", "gemeinde", "gemeindename", "gen", "bezeichnung", "ort", "ortsname", "municipality")

private fun normalizedJwdType(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace('_', ' ')
    .trim()
    .lowercase()

private val JWD_TITLE_MARKER_TYPE_SUFFIX = Regex("\\r?\\n\\[jagdkarte:v(\\d+):([a-z0-9][a-z0-9_-]{0,39})]$")

private data class JwdTitleMetadata(val title: String, val markerTypeId: String?)

private fun jwdTitleMetadata(value: String): JwdTitleMetadata {
    val match = JWD_TITLE_MARKER_TYPE_SUFFIX.find(value) ?: return JwdTitleMetadata(value, null)
    if (match.groupValues[1].toIntOrNull() != JWD_MARKER_METADATA_VERSION) return JwdTitleMetadata(value, null)
    return JwdTitleMetadata(value.removeRange(match.range).trimEnd(), match.groupValues[2])
}

private fun jwdCoordinate(point: JSONObject, label: String): Pair<Double, Double> {
    val latitude = point.optDouble("latitude", Double.NaN)
    val longitude = point.optDouble("longitude", Double.NaN)
    require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
        "$label: ungueltige Koordinaten."
    }
    return latitude to longitude
}

private fun jwdRing(points: JSONArray, label: String): JwdRing {
    require(points.length() >= 3) { "$label: mindestens drei Punkte erforderlich." }
    val vertices = (0 until points.length()).map { index ->
        jwdCoordinate(points.getJSONObject(index), "$label, Punkt ${index + 1}")
    }
    require(vertices.toSet().size >= 3) { "$label: mindestens drei unterschiedliche Punkte erforderlich." }
    val closed = if (vertices.first() == vertices.last()) vertices else vertices + vertices.first()
    val coordinates = JSONArray()
    closed.forEach { (latitude, longitude) -> coordinates.put(JSONArray().put(longitude).put(latitude)) }
    val geometry = GeoJsonReader().read(JSONObject().put("type", "Polygon")
        .put("coordinates", JSONArray().put(coordinates)).toString())
    require(geometry.isValid && !geometry.isEmpty) { "$label: ungueltige Flaeche." }
    return JwdRing(coordinates, geometry)
}

internal fun parseJwdImport(jsonText: String): JwdImportedProfile {
    val root = try {
        JSONObject(jsonText.removePrefix("\uFEFF"))
    } catch (_: Exception) {
        throw IllegalArgumentException("Die Datei enthaelt kein gueltiges JWD-JSON.")
    }
    val name = root.optString("title").trim()
    require(name.isNotEmpty()) { "Die Datei enthaelt keinen Reviertitel." }
    require(name.length <= 200) { "Der Reviername darf hoechstens 200 Zeichen enthalten." }
    val huntingGround = root.optJSONObject("huntingGround")
        ?: throw IllegalArgumentException("Die Datei enthaelt keine Revierdaten.")
    val borders = huntingGround.optJSONArray("borders")
        ?: throw IllegalArgumentException("Die Datei enthaelt keine Reviergrenze.")
    require(borders.length() > 0) { "Die Datei enthaelt keine Reviergrenze." }

    val outerRings = (0 until borders.length()).map { index ->
        jwdRing(borders.getJSONArray(index), "Grenze ${index + 1}")
    }
    val holes = MutableList(outerRings.size) { mutableListOf<JSONArray>() }
    require(!huntingGround.has("pacifiedAreas") || huntingGround.isNull("pacifiedAreas") ||
        huntingGround.optJSONArray("pacifiedAreas") != null) {
        "Die befriedeten Bereiche haben ein ungueltiges Format."
    }
    val pacifiedAreas = huntingGround.optJSONArray("pacifiedAreas") ?: JSONArray()
    for (index in 0 until pacifiedAreas.length()) {
        val area = jwdRing(pacifiedAreas.getJSONArray(index), "Befriedeter Bereich ${index + 1}")
        val containingPolygon = outerRings.indexOfFirst { it.geometry.covers(area.geometry) }
        require(containingPolygon >= 0) {
            "Befriedeter Bereich ${index + 1} liegt nicht vollstaendig innerhalb einer Reviergrenze."
        }
        holes[containingPolygon] += area.coordinates
    }

    val polygonCoordinates = JSONArray()
    outerRings.forEachIndexed { index, outer ->
        val rings = JSONArray().put(outer.coordinates)
        holes[index].forEach(rings::put)
        polygonCoordinates.put(rings)
    }
    val boundary = if (outerRings.size == 1) {
        JSONObject().put("type", "Polygon").put("coordinates", polygonCoordinates.getJSONArray(0))
    } else {
        JSONObject().put("type", "MultiPolygon").put("coordinates", polygonCoordinates)
    }

    require(!huntingGround.has("facilities") || huntingGround.isNull("facilities") ||
        huntingGround.optJSONArray("facilities") != null) {
        "Die Einrichtungen haben ein ungueltiges Format."
    }
    val rawFacilities = huntingGround.optJSONArray("facilities") ?: JSONArray()
    val facilities = (0 until rawFacilities.length()).map { index ->
        val facility = rawFacilities.getJSONObject(index)
        val (latitude, longitude) = jwdCoordinate(facility.optJSONObject("coordinate") ?: JSONObject(),
            "Einrichtung ${index + 1}")
        val sourceType = facility.optString("type").trim()
        val metadata = facility.optJSONObject(JWD_MARKER_METADATA_KEY)
        val propertyTypeId = metadata?.takeIf {
            it.optInt("version", -1) == JWD_MARKER_METADATA_VERSION
        }?.optString("markerTypeId")?.takeIf { Regex("^[a-z0-9][a-z0-9_-]{0,39}$").matches(it) }
        val titleMetadata = jwdTitleMetadata(facility.optString("title"))
        val preservedTypeId = titleMetadata.markerTypeId ?: propertyTypeId
        val preservedType = HuntingMarkerType.entries.firstOrNull { it.id == preservedTypeId }
        val normalizedType = normalizedJwdType(sourceType)
        val sourceTypeDefinition = HuntingMarkerType.entries.firstOrNull {
            normalizedJwdType(it.id) == normalizedType || normalizedJwdType(it.label) == normalizedType
        }
        val type = preservedType ?: sourceTypeDefinition
        require(type?.temporary != true) {
            "Einrichtung ${index + 1}: temporaere Markierungen koennen nicht als dauerhafte Einrichtung importiert werden."
        }
        val originalTypeId = preservedTypeId ?: sourceType
        val originalTypeNote = "Importierter Originaltyp: ${originalTypeId.ifBlank { "nicht angegeben" }}"
        val sourceNotes = facility.optString("notes")
        val notes = if (type != null || sourceNotes.startsWith(originalTypeNote)) sourceNotes
            else listOf(originalTypeNote, sourceNotes).filter { it.isNotBlank() }.joinToString("\n")
        val title = titleMetadata.title.trim().ifBlank {
            "${type?.label ?: sourceType.ifBlank { "Einrichtung" }} ${index + 1}"
        }
        require(title.length <= 80 && notes.length <= 300) {
            "Einrichtungsname oder Importvermerk ueberschreitet die erlaubte Laenge."
        }
        JwdImportedFacility(preservedTypeId ?: type?.id ?: HuntingMarkerType.SONSTIGES.id,
            title, notes, latitude, longitude)
    }
    return JwdImportedProfile(name, boundary, facilities, pacifiedAreas.length(),
        outsideBoundaryFacilityCount = facilities.count {
            !locationInsideBoundary(boundary, it.latitude, it.longitude)
        },
        untitledFacilityCount = (0 until rawFacilities.length()).count {
            rawFacilities.getJSONObject(it).optString("title").trim().isEmpty()
        },
        unknownTypeCount = facilities.count { it.notes.startsWith("Importierter Originaltyp:") })
}

private fun normalizedGeoJsonKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace("_", "")
    .lowercase()

private fun normalizeGeoJsonRing(points: JSONArray, label: String): JSONArray {
    require(points.length() >= 3) { "$label: mindestens drei Punkte erforderlich." }
    val vertices = (0 until points.length()).map { index ->
        val coordinate = points.optJSONArray(index)
            ?: throw IllegalArgumentException("$label, Punkt ${index + 1}: ungueltige Koordinaten.")
        require(coordinate.length() >= 2) { "$label, Punkt ${index + 1}: ungueltige Koordinaten." }
        val longitude = coordinate.optDouble(0, Double.NaN)
        val latitude = coordinate.optDouble(1, Double.NaN)
        require(longitude.isFinite() && latitude.isFinite() && longitude in -180.0..180.0 && latitude in -90.0..90.0) {
            "$label, Punkt ${index + 1}: ungueltige Koordinaten."
        }
        longitude to latitude
    }
    require(vertices.toSet().size >= 3) { "$label: mindestens drei unterschiedliche Punkte erforderlich." }
    val closed = if (vertices.first() == vertices.last()) vertices else vertices + vertices.first()
    return JSONArray().also { ring -> closed.forEach { (longitude, latitude) ->
        ring.put(JSONArray().put(longitude).put(latitude))
    } }
}

internal fun normalizeGeoJsonGeometry(geometry: JSONObject, label: String): Pair<JSONObject, Int> {
    val normalized = when (geometry.optString("type")) {
        "Polygon" -> {
            val rings = geometry.optJSONArray("coordinates")
                ?: throw IllegalArgumentException("$label: ungueltige Polygonkoordinaten.")
            require(rings.length() > 0) { "$label: keine Polygonringe gefunden." }
            JSONObject().put("type", "Polygon").put("coordinates", JSONArray().also { output ->
                for (index in 0 until rings.length()) {
                    output.put(normalizeGeoJsonRing(rings.getJSONArray(index), "$label, Ring ${index + 1}"))
                }
            })
        }
        "MultiPolygon" -> {
            val polygons = geometry.optJSONArray("coordinates")
                ?: throw IllegalArgumentException("$label: ungueltige MultiPolygonkoordinaten.")
            require(polygons.length() > 0) { "$label: keine Polygonflaechen gefunden." }
            JSONObject().put("type", "MultiPolygon").put("coordinates", JSONArray().also { outputPolygons ->
                for (polygonIndex in 0 until polygons.length()) {
                    val rings = polygons.getJSONArray(polygonIndex)
                    require(rings.length() > 0) { "$label, Flaeche ${polygonIndex + 1}: kein Aussenring." }
                    outputPolygons.put(JSONArray().also { outputRings ->
                        for (ringIndex in 0 until rings.length()) {
                            outputRings.put(normalizeGeoJsonRing(rings.getJSONArray(ringIndex),
                                "$label, Flaeche ${polygonIndex + 1}, Ring ${ringIndex + 1}"))
                        }
                    })
                }
            })
        }
        else -> throw IllegalArgumentException("$label: GeoJSON braucht eine Polygon- oder MultiPolygon-Geometrie.")
    }
    val parsed = GeoJsonReader().read(normalized.toString())
    require(parsed.isValid && !parsed.isEmpty) { "$label: ungueltige Polygongeometrie." }
    val pacifiedCount = if (normalized.optString("type") == "Polygon") {
        normalized.getJSONArray("coordinates").length() - 1
    } else {
        val polygons = normalized.getJSONArray("coordinates")
        (0 until polygons.length()).sumOf { polygons.getJSONArray(it).length() - 1 }
    }
    return normalized to pacifiedCount
}

internal fun geoJsonFeatureName(properties: JSONObject?, fallback: String, preferredFields: List<String> = emptyList()): String {
    if (properties == null) return fallback
    preferredFields.forEach { field ->
        val value = properties.opt(field)
        if (value is String && value.trim().isNotEmpty()) return value.trim()
        if (value is Number) return value.toString()
    }
    for (key in properties.keys()) {
        val value = properties.opt(key)
        if (normalizedGeoJsonKey(key) in GEOJSON_NAME_KEYS && value is String && value.trim().isNotEmpty()) {
            return value.trim()
        }
    }
    return fallback
}

private fun geoJsonFileName(fileName: String): String {
    val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("\\.(geo)?json$", RegexOption.IGNORE_CASE), "").trim()
    return baseName.ifBlank { "GeoJSON-Revier" }
}

internal fun parseRevierImport(jsonText: String, fileName: String): List<JwdImportedProfile> {
    val root = try {
        JSONObject(jsonText.removePrefix("\uFEFF"))
    } catch (_: Exception) {
        throw IllegalArgumentException("Die Datei enthaelt kein gueltiges JSON.")
    }
    if (root.optString("type") !in GEOJSON_ROOT_TYPES) return listOf(parseJwdImport(jsonText))
    val fallbackName = geoJsonFileName(fileName)
    val sourceFeatures = when (root.optString("type")) {
        "FeatureCollection" -> root.optJSONArray("features")
            ?: throw IllegalArgumentException("Die GeoJSON-Datei enthaelt keine Features.")
        "Feature" -> JSONArray().put(root)
        else -> JSONArray().put(JSONObject().put("type", "Feature").put("geometry", root))
    }
    val candidates = (0 until sourceFeatures.length()).mapNotNull { index ->
        val feature = sourceFeatures.optJSONObject(index) ?: return@mapNotNull null
        val geometry = feature.optJSONObject("geometry") ?: return@mapNotNull null
        if (geometry.optString("type") !in setOf("Polygon", "MultiPolygon")) return@mapNotNull null
        val candidateName = geoJsonFeatureName(feature.optJSONObject("properties"),
            if (sourceFeatures.length() == 1) fallbackName else "Gemeinde ${index + 1}")
        require(candidateName.length <= 200) { "Der Reviername darf hoechstens 200 Zeichen enthalten." }
        val (normalizedGeometry, pacifiedCount) = normalizeGeoJsonGeometry(geometry, "Feature ${index + 1}")
        JwdImportedProfile(candidateName, normalizedGeometry, emptyList(), pacifiedCount)
    }
    require(candidates.isNotEmpty()) {
        "Die GeoJSON-Datei enthaelt keine Polygon- oder MultiPolygon-Features."
    }
    return candidates
}