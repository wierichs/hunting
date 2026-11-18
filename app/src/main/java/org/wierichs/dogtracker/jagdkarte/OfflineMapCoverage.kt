package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.io.geojson.GeoJsonWriter
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.io.File

internal enum class HuntingMapStyle(val label: String) {
    OPEN_STREET_MAP("OpenStreetMap"), AERIAL("Luftbild"), OFFLINE("Offline")
}

internal data class OfflineMapPackage(
    val id: String,
    val name: String,
    val file: File,
    val size: Long,
    val bounds: JSONObject,
    val coverage: JSONObject?,
    val checksum: String
)

internal fun validatedCoverage(json: JSONObject): Geometry {
    val geometry = GeoJsonReader().read(json.toString())
    require(geometry.geometryType in setOf("Polygon", "MultiPolygon") && !geometry.isEmpty && geometry.isValid &&
        geometry.coordinates.all { it.x.isFinite() && it.y.isFinite() && it.x in -180.0..180.0 && it.y in -85.05112878..85.05112878 }) {
        "Ungueltige Kartenabdeckung."
    }
    return geometry
}

internal fun coveringOfflineMaps(packages: List<OfflineMapPackage>, boundary: JSONObject?): List<OfflineMapPackage> {
    if (boundary == null) return emptyList()
    return runCatching {
        val district = validatedCoverage(boundary)
        val candidates = packages.mapNotNull { entry ->
            if (!entry.file.isFile || entry.file.length() != entry.size || entry.coverage == null) return@mapNotNull null
            runCatching {
                val coverage = validatedCoverage(entry.coverage).intersection(validatedCoverage(entry.bounds))
                if (coverage.intersects(district)) entry to coverage else null
            }.getOrNull()
        }
        candidates.filter { it.second.covers(district) }.minByOrNull { it.first.size }?.let { return@runCatching listOf(it.first) }
        if (candidates.isNotEmpty() && UnaryUnionOp.union(candidates.map { it.second }).covers(district))
            candidates.map { it.first } else emptyList()
    }.getOrDefault(emptyList())
}

internal fun parseOfflineCoverage(content: String, fileName: String): JSONObject {
    require(content.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Die Abdeckungsdatei ist zu gross." }
    if (!fileName.endsWith(".poly", ignoreCase = true)) {
        val json = JSONObject(content)
        val geometry = when (json.optString("type")) {
            "Feature" -> json.getJSONObject("geometry")
            "FeatureCollection" -> {
                val features = json.getJSONArray("features")
                val polygons = (0 until features.length()).map { validatedCoverage(features.getJSONObject(it).getJSONObject("geometry")) }
                JSONObject(GeoJsonWriter().write(UnaryUnionOp.union(polygons)))
            }
            else -> json
        }
        validatedCoverage(geometry)
        return geometry
    }
    val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    require(lines.size >= 7) { "Ungueltige POLY-Datei." }
    val factory = GeometryFactory()
    val outer = mutableListOf<Geometry>()
    val holes = mutableListOf<Geometry>()
    var cursor = 1
    while (cursor < lines.size && lines[cursor] != "END") {
        val hole = lines[cursor++].startsWith("!")
        val coordinates = mutableListOf<Coordinate>()
        while (cursor < lines.size && lines[cursor] != "END") {
            val components = lines[cursor++].split(Regex("\\s+"))
            require(components.size == 2) { "Ungueltige POLY-Koordinate." }
            coordinates.add(Coordinate(components[0].toDouble(), components[1].toDouble()))
        }
        require(cursor < lines.size && coordinates.size >= 3) { "Unvollstaendiger POLY-Ring." }
        cursor++
        if (!coordinates.first().equals2D(coordinates.last())) coordinates.add(Coordinate(coordinates.first()))
        val polygon = factory.createPolygon(coordinates.toTypedArray())
        validatedCoverage(JSONObject(GeoJsonWriter().write(polygon)))
        if (hole) holes.add(polygon) else outer.add(polygon)
    }
    require(cursor == lines.lastIndex && lines[cursor] == "END" && outer.isNotEmpty()) { "Unvollstaendige POLY-Datei." }
    val merged = UnaryUnionOp.union(outer)
    val excluded = if (holes.isEmpty()) null else UnaryUnionOp.union(holes)
    require(excluded == null || merged.covers(excluded)) { "POLY-Loch liegt ausserhalb der Abdeckung." }
    val result = JSONObject(GeoJsonWriter().write(if (excluded == null) merged else merged.difference(excluded)))
    validatedCoverage(result)
    return result
}