package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JwdExportTest {
    private val polygonProfile = HuntingProfile("profile", "Waldrevier", "owner", false, JSONObject("""{
        "type":"Polygon","coordinates":[
            [[6,50],[8,50],[8,52],[6,52],[6,50]],
            [[6.5,50.5],[6.5,51],[7,51],[7,50.5],[6.5,50.5]]
        ]
    }"""))

    @Test fun exportMatchesPwaJwdShapeAndPreservesPacifiedAreas() {
        val exported = createJwdExport(polygonProfile, emptyList(), includeMarkers = false)
        assertEquals("Waldrevier", exported.getString("title"))
        assertEquals(51.0, exported.getDouble("lat"), 0.0)
        assertEquals(7.0, exported.getDouble("lng"), 0.0)
        val ground = exported.getJSONObject("huntingGround")
        val border = ground.getJSONArray("borders").getJSONArray(0).getJSONObject(0)
        assertEquals(50.0, border.getDouble("latitude"), 0.0)
        assertEquals(6.0, border.getDouble("longitude"), 0.0)
        val pacified = ground.getJSONArray("pacifiedAreas").getJSONArray(0).getJSONObject(0)
        assertEquals(50.5, pacified.getDouble("latitude"), 0.0)
        assertEquals(6.5, pacified.getDouble("longitude"), 0.0)
        assertEquals(0, ground.getJSONArray("facilities").length())
    }

    @Test fun exportIncludesOnlyPermanentFacilitiesWhenAuthorized() {
        val permanent = HuntingMapPoint(51.2, 7.2, "Hochsitz Nord", "Notiz", MapPointKind.FACILITY,
            markerTypeId = "hochsitz", markerNotes = "Notiz")
        val otherPermanent = HuntingMapPoint(51.3, 7.3, "Falle", "", MapPointKind.FACILITY,
            markerTypeId = "falle")
        val unknownPermanent = HuntingMapPoint(51.35, 7.35, "Unbekannte Anlage", "", MapPointKind.FACILITY,
            markerTypeId = "neuer-typ")
        val temporary = HuntingMapPoint(51.4, 7.4, "Anschuss", "", MapPointKind.FACILITY,
            expiresAt = java.time.Instant.parse("2026-11-10T00:00:00Z"), markerTypeId = "anschuss")
        val expiredPermanent = otherPermanent.copy(expiresAt = java.time.Instant.parse("2026-11-10T00:00:00Z"))
        val points = listOf(permanent, otherPermanent, unknownPermanent, temporary, expiredPermanent)

        val facilities = createJwdExport(polygonProfile, points, includeMarkers = true)
            .getJSONObject("huntingGround").getJSONArray("facilities")
        assertEquals(3, facilities.length())
        assertEquals("Hochsitz", facilities.getJSONObject(0).getString("type"))
        assertEquals("Hochsitz Nord", facilities.getJSONObject(0).getString("title"))
        assertEquals("Notiz", facilities.getJSONObject(0).getString("notes"))
        assertEquals("Hochsitz", facilities.getJSONObject(1).getString("type"))
        assertEquals("Falle\n[jagdkarte:v1:falle]", facilities.getJSONObject(1).getString("title"))
        assertEquals(false, facilities.getJSONObject(1).has(JWD_MARKER_METADATA_KEY))
        assertEquals("Hochsitz", facilities.getJSONObject(2).getString("type"))
        assertEquals("Unbekannte Anlage\n[jagdkarte:v1:neuer-typ]", facilities.getJSONObject(2).getString("title"))
        assertEquals(false, facilities.getJSONObject(2).has(JWD_MARKER_METADATA_KEY))

        assertEquals(0, createJwdExport(polygonProfile, points, includeMarkers = false)
            .getJSONObject("huntingGround").getJSONArray("facilities").length())
    }

    @Test fun unsupportedTypesUseConfigurableExportMappingAndRoundTripOriginalType() {
        val marker = HuntingMapPoint(51.2, 7.2, "Falle Nord", "Notiz", MapPointKind.FACILITY,
            markerTypeId = "falle", markerNotes = "Notiz")
        val mappings = defaultJwdMarkerTypeMapping() + ("falle" to "bock")
        val exported = createJwdExport(polygonProfile, listOf(marker), includeMarkers = true, mappings)
        val facility = exported.getJSONObject("huntingGround").getJSONArray("facilities").getJSONObject(0)
        assertEquals("Bock", facility.getString("type"))
        assertEquals("Falle Nord\n[jagdkarte:v1:falle]", facility.getString("title"))
        val imported = parseJwdImport(exported.toString()).facilities.single()
        assertEquals("falle", imported.typeId)
        assertEquals("Falle Nord", imported.title)
        assertEquals("Notiz", imported.notes)
    }

    @Test fun importStillRecognizesLegacyOriginalTypeProperty() {
        val facility = JSONObject().put("coordinate", JSONObject().put("latitude", 51.2).put("longitude", 7.2))
            .put("type", "Hochsitz").put("title", "Falle Nord").put("notes", "Notiz")
            .put(JWD_MARKER_METADATA_KEY, JSONObject().put("version", 1).put("markerTypeId", "falle"))
        val exported = createJwdExport(polygonProfile, emptyList(), includeMarkers = false)
        exported.getJSONObject("huntingGround").put("facilities", org.json.JSONArray().put(facility))
        val imported = parseJwdImport(exported.toString()).facilities.single()
        assertEquals("falle", imported.typeId)
        assertEquals("Falle Nord", imported.title)
    }

    @Test fun defaultUnsupportedTypeMappingMatchesCurrentFallback() {
        val defaults = defaultJwdMarkerTypeMapping()
        assertEquals("hochsitz", defaults["falle"])
        assertEquals("hochsitz", defaults["wildkamera"])
        assertEquals("bock", defaults["bock"])
    }

    @Test fun exportSupportsMultiPolygonAndRejectsMissingBoundary() {
        val multi = HuntingProfile("profile", "Zwei Flaechen", "owner", false, JSONObject("""{
            "type":"MultiPolygon","coordinates":[
                [[[6,50],[7,50],[7,51],[6,51],[6,50]]],
                [[[8,52],[9,52],[9,53],[8,53],[8,52]]]
            ]
        }"""))
        val ground = createJwdExport(multi, emptyList(), false).getJSONObject("huntingGround")
        assertEquals(2, ground.getJSONArray("borders").length())
        assertThrows(IllegalArgumentException::class.java) {
            createJwdExport(multi.copy(boundary = null), emptyList(), false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createJwdExport(multi.copy(boundary = JSONObject("""{"type":"Point","coordinates":[7,51]}""")), emptyList(), false)
        }
    }

    @Test fun fileNameIsSafeAndHasJwdExtension() {
        assertEquals("Wald_ Nord.jwd", jwdFileName("Wald: Nord"))
        assertEquals("jagdrevier.jwd", jwdFileName("...  "))
    }
}