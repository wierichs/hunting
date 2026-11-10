package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OfflineMapCoverageTest {
    private fun box(west: Double, south: Double, east: Double, north: Double) = JSONObject(
        """{"type":"Polygon","coordinates":[[[$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]]]}""")

    private fun withPackage(coverage: JSONObject?, bounds: JSONObject = box(5.0, 49.0, 10.0, 54.0),
        size: Int = 0, test: (OfflineMapPackage) -> Unit) {
        val file = Files.createTempFile("offline-map", ".map").toFile()
        file.writeBytes(ByteArray(size))
        try { test(OfflineMapPackage("test", "Region", file, file.length(), bounds, coverage, "test")) }
        finally { file.delete() }
    }

    @Test fun offersOfflineOnlyForCompleteCoverageAndExistingFile() {
        withPackage(box(6.0, 50.0, 8.0, 52.0)) { entry ->
            assertEquals(listOf(entry), coveringOfflineMaps(listOf(entry), box(6.1, 50.1, 7.9, 51.9)))
            assertTrue(coveringOfflineMaps(listOf(entry), box(7.5, 51.5, 8.5, 52.5)).isEmpty())
            assertTrue(coveringOfflineMaps(listOf(entry), null).isEmpty())
            assertTrue(coveringOfflineMaps(listOf(entry.copy(coverage = null)), box(6.1, 50.1, 7.9, 51.9)).isEmpty())
            entry.file.delete()
            assertTrue(coveringOfflineMaps(listOf(entry), box(6.1, 50.1, 7.9, 51.9)).isEmpty())
        }
    }

    @Test fun clipsCoverageToMapBoundsAndCombinesAdjacentPackages() {
        withPackage(box(5.0, 49.0, 10.0, 54.0), box(6.0, 50.0, 7.0, 52.0)) { west ->
            withPackage(box(7.0, 50.0, 8.0, 52.0)) { east ->
                val district = box(6.5, 50.5, 7.5, 51.5)
                assertTrue(coveringOfflineMaps(listOf(west), district).isEmpty())
                assertEquals(2, coveringOfflineMaps(listOf(west, east.copy(id = "east")), district).size)
            }
        }
    }

    @Test fun extractHoleIsNotMistakenForCoverageDespiteBoundingBox() {
        val coverage = parseOfflineCoverage("""Region
            1
            6 50
            8 50
            8 52
            6 52
            END
            !1
            6.5 50.5
            7 50.5
            7 51
            6.5 51
            END
            END
        """.trimIndent(), "region.poly")
        withPackage(coverage) { entry ->
            assertTrue(coveringOfflineMaps(listOf(entry), box(6.6, 50.6, 6.9, 50.9)).isEmpty())
            assertFalse(coveringOfflineMaps(listOf(entry), box(7.1, 51.1, 7.9, 51.9)).isEmpty())
        }
    }

    @Test fun rejectsBrokenOrNonPolygonFootprints() {
        assertThrows(IllegalArgumentException::class.java) { parseOfflineCoverage("Region\n1\n6 50\n8 50\n8 52\nEND", "region.poly") }
        assertThrows(IllegalArgumentException::class.java) { parseOfflineCoverage("""{"type":"Point","coordinates":[6,50]}""", "region.geojson") }
    }

    @Test fun supportsGeoJsonFeaturesAndChoosesSmallestCompletePackage() {
        val boundary = box(6.5, 50.5, 7.5, 51.5)
        val coverage = parseOfflineCoverage(JSONObject().put("type", "Feature").put("geometry", box(6.0, 50.0, 8.0, 52.0)).toString(), "region.geojson")
        withPackage(coverage) { entry ->
            withPackage(coverage, size = 10) { larger ->
                assertEquals(entry, coveringOfflineMaps(listOf(larger, entry), boundary).single())
            }
        }
    }
}