package org.wierichs.dogtracker.jagdkarte

import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.operation.valid.IsValidOp

internal data class BoundaryDraft(val polygons: List<List<List<MarkerCoordinates>>>) {
    fun move(polygon: Int, ring: Int, vertex: Int, point: MarkerCoordinates): BoundaryDraft =
        changeRing(polygon, ring) { points -> points.toMutableList().apply { set(vertex, point) } }

    fun insertAfter(polygon: Int, ring: Int, vertex: Int, point: MarkerCoordinates): BoundaryDraft =
        changeRing(polygon, ring) { points -> points.toMutableList().apply { add(vertex + 1, point) } }

    fun removeVertex(polygon: Int, ring: Int, vertex: Int): BoundaryDraft = changeRing(polygon, ring) { points ->
        require(points.size > 3) { "Eine Grenze benoetigt mindestens drei Punkte." }
        points.filterIndexed { index, _ -> index != vertex }
    }

    fun removeRing(polygon: Int, ring: Int): BoundaryDraft {
        require(ring > 0) { "Die Aussengrenze kann nur als Teilflaeche geloescht werden." }
        return copy(polygons = polygons.mapIndexed { index, rings ->
            if (index == polygon) rings.filterIndexed { ringIndex, _ -> ringIndex != ring } else rings
        })
    }

    fun removePolygon(polygon: Int): BoundaryDraft {
        require(polygons.size > 1) { "Die letzte Teilflaeche darf nicht geloescht werden." }
        return copy(polygons = polygons.filterIndexed { index, _ -> index != polygon })
    }

    fun addPolygon(points: List<MarkerCoordinates>): BoundaryDraft = copy(polygons = polygons + listOf(listOf(points.toList())))

    fun addHole(polygon: Int, points: List<MarkerCoordinates>): BoundaryDraft = copy(polygons =
        polygons.mapIndexed { index, rings -> if (index == polygon) rings + listOf(points.toList()) else rings })

    private fun changeRing(polygon: Int, ring: Int, change: (List<MarkerCoordinates>) -> List<MarkerCoordinates>) =
        copy(polygons = polygons.mapIndexed { index, rings ->
            if (index == polygon) rings.mapIndexed { ringIndex, points ->
                if (ringIndex == ring) change(points) else points
            } else rings
        })

    fun geometry(): JSONObject {
        require(polygons.isNotEmpty()) { "Mindestens eine Teilflaeche ist erforderlich." }
        val coordinates = JSONArray()
        polygons.forEach { rings ->
            require(rings.isNotEmpty()) { "Eine Teilflaeche benoetigt eine Aussengrenze." }
            val polygon = JSONArray()
            rings.forEach { points ->
                require(points.distinct().size >= 3) { "Jeder Ring benoetigt mindestens drei unterschiedliche Punkte." }
                val ring = JSONArray()
                (points + points.first()).forEach { point ->
                    require(point.latitude.isFinite() && point.longitude.isFinite() &&
                        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) {
                        "Ungueltige Grenzkoordinaten."
                    }
                    ring.put(JSONArray().put(point.longitude).put(point.latitude))
                }
                polygon.put(ring)
            }
            coordinates.put(polygon)
        }
        return JSONObject().put("type", if (polygons.size == 1) "Polygon" else "MultiPolygon")
            .put("coordinates", if (polygons.size == 1) coordinates.getJSONArray(0) else coordinates)
    }

    fun validatedGeometry(): JSONObject {
        val json = geometry()
        val geometry = GeoJsonReader().read(json.toString())
        require(!geometry.isEmpty && IsValidOp(geometry).isValid) {
            "Ungueltige Grenze: Ueberschneidungen, sich kreuzende Kanten oder befriedete Bereiche ausserhalb der Teilflaeche."
        }
        return json
    }

    companion object {
        fun fromGeometry(geometry: JSONObject): BoundaryDraft {
            val coordinates = geometry.getJSONArray("coordinates")
            val polygons = when (geometry.getString("type")) {
                "Polygon" -> listOf(coordinates)
                "MultiPolygon" -> (0 until coordinates.length()).map(coordinates::getJSONArray)
                else -> error("Die Reviergrenze ist kein Polygon.")
            }
            return BoundaryDraft(polygons.map { polygon ->
                (0 until polygon.length()).map { ringIndex ->
                    val ring = polygon.getJSONArray(ringIndex)
                    val points = (0 until ring.length()).map { pointIndex ->
                        val point = ring.getJSONArray(pointIndex)
                        MarkerCoordinates(point.getDouble(1), point.getDouble(0))
                    }
                    if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points
                }
            })
        }
    }
}