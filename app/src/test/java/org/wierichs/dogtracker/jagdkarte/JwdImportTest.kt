package org.wierichs.dogtracker.jagdkarte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class JwdImportTest {
    private val validFile = """{
      "title":"JWD Revier",
      "huntingGround":{
        "borders":[[
          {"latitude":50.0,"longitude":6.0},
          {"latitude":50.0,"longitude":8.0},
          {"latitude":52.0,"longitude":8.0},
          {"latitude":52.0,"longitude":6.0}
        ]],
        "pacifiedAreas":[[
          {"latitude":50.5,"longitude":6.5},
          {"latitude":51.0,"longitude":6.5},
          {"latitude":51.0,"longitude":7.0},
          {"latitude":50.5,"longitude":7.0}
        ]],
        "facilities":[
          {"coordinate":{"latitude":51.5,"longitude":7.5},"type":"Hochsitz","title":"Nord","notes":"Notiz"},
          {"coordinate":{"latitude":51.6,"longitude":7.6},"type":"Neuer Typ","title":"Einrichtung","notes":""}
        ]
      }
    }"""

    @Test fun parserBuildsGeoJsonBoundaryWithHolesAndMapsFacilities() {
        val imported = parseJwdImport(validFile)
        assertEquals("JWD Revier", imported.name)
        assertEquals("Polygon", imported.boundary.getString("type"))
        assertEquals(2, imported.boundary.getJSONArray("coordinates").length())
        assertEquals(1, imported.pacifiedAreaCount)
        assertEquals(2, imported.facilities.size)
        assertEquals("hochsitz", imported.facilities[0].typeId)
        assertEquals("Nord", imported.facilities[0].title)
        assertEquals("Notiz", imported.facilities[0].notes)
        assertEquals("sonstiges", imported.facilities[1].typeId)
        assertTrue(imported.facilities[1].notes.startsWith("Importierter Originaltyp: Neuer Typ"))
        assertEquals(1, imported.unknownTypeCount)
        assertEquals(0, imported.outsideBoundaryFacilityCount)
        assertEquals(0, imported.untitledFacilityCount)
    }

    @Test fun parserClosesOpenRingsAndSupportsMultipleBorders() {
        val file = validFile.replace(
            "\"borders\":[[",
            "\"borders\":[["
        ).replace(
            "{\"latitude\":52.0,\"longitude\":6.0}\n        ]],",
            "{\"latitude\":52.0,\"longitude\":6.0}\n        ],[\n          {\"latitude\":53.0,\"longitude\":8.0},\n          {\"latitude\":53.0,\"longitude\":9.0},\n          {\"latitude\":54.0,\"longitude\":9.0},\n          {\"latitude\":54.0,\"longitude\":8.0}\n        ]],"
        )
        val imported = parseJwdImport(file)
        assertEquals("MultiPolygon", imported.boundary.getString("type"))
        assertEquals(2, imported.boundary.getJSONArray("coordinates").length())
        val ring = imported.boundary.getJSONArray("coordinates").getJSONArray(0).getJSONArray(0)
        assertEquals(ring.getJSONArray(0).toString(), ring.getJSONArray(ring.length() - 1).toString())
    }

      @Test fun mergeRevierBoundariesPreservesExistingHolesAndAddsAdjacentArea() {
        val existing = parseJwdImport(validFile).boundary
        val adjacent = JSONObject("""{"type":"Polygon","coordinates":[[
          [8,50],[9,50],[9,52],[8,52],[8,50]
        ]]}""")
        val merged = mergeRevierBoundaries(existing, adjacent)
        assertEquals("Polygon", merged.getString("type"))
        assertTrue(locationInsideBoundary(merged, 51.5, 8.5))
        assertFalse(locationInsideBoundary(merged, 50.75, 6.75))
        assertTrue(locationInsideBoundary(existing, 51.5, 8.5).not())
      }

      @Test fun mergeRevierBoundariesKeepsDisjointRegionsAsMultiPolygon() {
        val existing = parseJwdImport(validFile).boundary
        val disjoint = JSONObject("""{"type":"Polygon","coordinates":[[
          [10,50],[11,50],[11,51],[10,51],[10,50]
        ]]}""")
        val merged = mergeRevierBoundaries(existing, disjoint)
        assertEquals("MultiPolygon", merged.getString("type"))
        assertTrue(locationInsideBoundary(merged, 51.5, 7.5))
        assertTrue(locationInsideBoundary(merged, 50.5, 10.5))
      }

      @Test fun facilityMergeKeyIgnoresSubMicrodegreeCoordinateRounding() {
        val facility = JwdImportedFacility("hochsitz", "Nord", "Notiz", 51.1234564, 7.6543214)
        assertEquals(facility.mergeKey(), jwdFacilityMergeKey("hochsitz", " Nord ", "Notiz",
          51.12345649, 7.65432149))
        assertTrue(facility.mergeKey() != jwdFacilityMergeKey("hochsitz", "Nord", "Andere Notiz",
          51.1234564, 7.6543214))
      }

      @Test fun mergeTargetSuggestionMatchesUniqueOwnedProfileNameOnly() {
        val owned = HuntingProfile("owned", "Waldrevier", "me", false, null)
        val shared = HuntingProfile("shared", "Fremdes Revier", "other", false, null)
        assertEquals("owned", suggestedJwdMergeTargetId(" waldREVier ", listOf(owned, shared), "me"))
        assertEquals(null, suggestedJwdMergeTargetId("Fremdes Revier", listOf(shared), "me"))
        assertEquals(null, suggestedJwdMergeTargetId("Waldrevier", listOf(owned,
          owned.copy(id = "owned-2")), "me"))
      }

    @Test fun parserRejectsInvalidAndTemporaryFacilitiesAndOutOfBoundaryHoles() {
        assertThrows(IllegalArgumentException::class.java) { parseJwdImport("not-json") }
        assertThrows(IllegalArgumentException::class.java) { parseJwdImport(validFile.replace("JWD Revier", "  ")) }
        assertThrows(IllegalArgumentException::class.java) {
            parseJwdImport(validFile.replace("\"latitude\":51.0,\"longitude\":6.5", "\"latitude\":55.0,\"longitude\":6.5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseJwdImport(validFile.replace("\"type\":\"Hochsitz\"", "\"type\":\"Anschuss\""))
        }
        val invalidCoordinates = validFile.replace("\"latitude\":51.5", "\"latitude\":91.5")
        assertThrows(IllegalArgumentException::class.java) { parseJwdImport(invalidCoordinates) }
    }

      @Test fun parserCountsOutsideAndUntitledFacilitiesForConfirmationWarnings() {
        val file = validFile
          .replace("\"latitude\":51.5,\"longitude\":7.5", "\"latitude\":54.0,\"longitude\":10.0")
          .replace("\"title\":\"Nord\"", "\"title\":\"\"")
        val imported = parseJwdImport(file)
        assertEquals(1, imported.outsideBoundaryFacilityCount)
        assertEquals(1, imported.untitledFacilityCount)
        assertEquals(1, imported.unknownTypeCount)
      }

      @Test fun parserRejectsWrongOptionalArrayTypes() {
        val file = """{"title":"Test","huntingGround":{"borders":[[
          {"latitude":50,"longitude":6},{"latitude":50,"longitude":8},
          {"latitude":52,"longitude":8},{"latitude":52,"longitude":6}
        ]],"facilities":false}}"""
        assertThrows(IllegalArgumentException::class.java) { parseJwdImport(file) }
      }

      @Test fun geoJsonFeatureCollectionProducesNamedSelectablePolygonCandidates() {
        val geoJson = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"Gemeinde":"Nord"},"geometry":{"type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52]]]}},
          {"type":"Feature","properties":{"name":"Sued"},"geometry":{"type":"MultiPolygon","coordinates":[[[[8,52],[9,52],[9,53],[8,53]]]]}},
          {"type":"Feature","properties":{"name":"Punkt"},"geometry":{"type":"Point","coordinates":[7,51]}}
        ]}"""
        val candidates = parseRevierImport(geoJson, "test.geojson")
        assertEquals(2, candidates.size)
        assertEquals("Nord", candidates[0].name)
        assertEquals("Polygon", candidates[0].boundary.getString("type"))
        assertEquals("Sued", candidates[1].name)
        assertEquals("MultiPolygon", candidates[1].boundary.getString("type"))
      }

      @Test fun singleGeoJsonFeatureUsesItsNameOrFileNameFallback() {
        val unnamed = """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52]]]}}"""
        assertEquals("Revier Nord", parseRevierImport(unnamed, "Revier Nord.geojson").single().name)
        assertThrows(IllegalArgumentException::class.java) {
          parseRevierImport("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[7,51]}}]}""",
            "punkt.geojson")
        }
      }

    @Test fun createdProfileIdAcceptsPostgrestObjectOrArrayResponse() {
        val id = "c76274b7-e21a-440e-ad0d-c51c6f6345e0"
        assertEquals(id, createdProfileIdFromResponse("""{"id":"$id"}"""))
        assertEquals(id, createdProfileIdFromResponse("""[{"id":"$id"}]"""))
        assertEquals(null, createdProfileIdFromResponse("{}"))
        assertFalse(parseJwdImport(validFile).facilities.any { HuntingMarkerType.fromId(it.typeId).temporary })
    }
}