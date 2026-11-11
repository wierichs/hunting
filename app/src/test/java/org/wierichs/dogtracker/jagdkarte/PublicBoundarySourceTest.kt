package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicBoundarySourceTest {
    private val source = PublicBoundarySource("test-source", "Testquelle", PublicBoundaryService.GEOJSON,
        "https://example.org/reviere.geojson", listOf("district_name", "number"), listOf("object_id"))

    @Test fun featureNamesAndIdsUseConfiguredFieldsThenGeoJsonFallback() {
        val feature = JSONObject("""{"type":"Feature","id":"feature-id","properties":{"number":27,"object_id":41},
            "geometry":{"type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]}}""")
        val parsed = publicBoundaryFeatureFromJson(feature, source, 0)!!
        assertEquals("27", parsed.name)
        assertEquals("41", parsed.id)
        assertEquals("Polygon", parsed.geometry.getString("type"))
        assertTrue(locationInsideBoundary(parsed.geometry, 51.0, 7.0))
        assertEquals("Testquelle", parsed.source.label)
    }

    @Test fun featureCollectionFiltersNonPolygonAndDeduplicatesStableIds() {
        val polygon = """{"type":"Feature","id":"same","properties":{"district_name":"Nord"},
            "geometry":{"type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]}}"""
        val collection = JSONObject("""{"type":"FeatureCollection","features":[$polygon,
            $polygon,{"type":"Feature","properties":{"name":"Punkt"},"geometry":{"type":"Point","coordinates":[7,51]}}]}""")
        val result = publicBoundaryFeaturesFromJson(collection, source)
        assertEquals(1, result.size)
        assertEquals("same", result.single().id)
        assertEquals("Nord", result.single().name)
    }

    @Test fun missingIdsAreStableForSameSourceAndGeometry() {
        val feature = JSONObject("""{"type":"Feature","properties":{"district_name":"Sued"},
            "geometry":{"type":"MultiPolygon","coordinates":[[[[6,50],[8,50],[8,52],[6,52],[6,50]]]]}}""")
        val first = publicBoundaryFeatureFromJson(feature, source, 0)!!
        val second = publicBoundaryFeatureFromJson(feature, source, 11)!!
        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("content-"))
    }

    @Test fun rejectsBrokenPolygonAndMalformedCollections() {
        val point = JSONObject("""{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[7,51]}}""")
        assertNull(publicBoundaryFeatureFromJson(point, source, 0))
        val bowtie = JSONObject("""{"type":"Feature","properties":{},"geometry":{"type":"Polygon",
            "coordinates":[[[6,50],[8,52],[8,50],[6,52],[6,50]]]}}""")
        assertThrows(IllegalArgumentException::class.java) { publicBoundaryFeatureFromJson(bowtie, source, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            publicBoundaryFeaturesFromJson(JSONObject("""{"type":"FeatureCollection"}"""), source)
        }
    }

    @Test fun customSourcesRequireSafeHttpsAndProtocolSpecificSettings() {
        assertEquals(source, validatePublicBoundarySource(source))
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicBoundarySource(source.copy(url = "http://example.org/reviere.geojson"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicBoundarySource(source.copy(url = "https://user:pass@example.org/reviere.geojson"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicBoundarySource(source.copy(url = "https://127.0.0.1/reviere.geojson"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicBoundarySource(source.copy(service = PublicBoundaryService.WFS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicBoundarySource(source.copy(id = "bad id"))
        }
    }

    @Test fun wpsIsNotAcceptedAsASearchServiceAndCustomIdsDoNotCollide() {
        assertThrows(IllegalArgumentException::class.java) { PublicBoundaryService.fromId("wps") }
        assertNotEquals(userBoundarySourceId(), userBoundarySourceId())
        assertTrue(userBoundarySourceId().matches(Regex("custom-[0-9a-f-]{36}")))
    }

    @Test fun sourceSpecificRequestsFollowThePwaWfsAndArcGisContracts() {
        val wfs = source.copy(service = PublicBoundaryService.WFS, url = "https://example.org/wfs?map=1",
            typeName = "districts:reviere", outputFormat = "application/json; subtype=geojson")
        val wfsUrl = PublicBoundaryRequests.wfs(wfs, 500)
        assertTrue(wfsUrl.startsWith("https://example.org/wfs?map=1&"))
        assertTrue(wfsUrl.contains("SERVICE=WFS"))
        assertTrue(wfsUrl.contains("VERSION=2.0.0"))
        assertTrue(wfsUrl.contains("TYPENAMES=districts%3Areviere"))
        assertTrue(wfsUrl.contains("STARTINDEX=500"))
        assertTrue(wfsUrl.contains("OUTPUTFORMAT=application%2Fjson%3B+subtype%3Dgeojson"))

        val arcGis = source.copy(service = PublicBoundaryService.ARCGIS,
            url = "https://example.org/MapServer", layerId = 5, pageSize = 250)
        val arcUrl = java.net.URI(PublicBoundaryRequests.arcGis(arcGis, 250))
        assertTrue(arcUrl.path.endsWith("/MapServer/5/query"))
        assertTrue(arcUrl.rawQuery.contains("f=geojson"))
        assertTrue(arcUrl.rawQuery.contains("outSR=4326"))
        assertTrue(arcUrl.rawQuery.contains("resultOffset=250"))
        assertTrue(arcUrl.rawQuery.contains("resultRecordCount=250"))
    }

    @Test fun ogcApiPaginationMustStayOnTheOriginalHttpsOrigin() {
        val origin = PublicBoundaryRequests.origin("https://example.org/items?limit=500")
        assertEquals("https://example.org/next?limit=500",
            PublicBoundaryRequests.paginationUrl("https://example.org/items", "/next?limit=500", origin))
        assertThrows(IllegalArgumentException::class.java) {
            PublicBoundaryRequests.paginationUrl("https://example.org/items", "https://evil.example/next", origin)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicBoundaryRequests.paginationUrl("https://example.org/items", "http://example.org/next", origin)
        }
    }

    @Test fun searchNormalizesGermanUmlautsAndScansConfiguredProperties() {
        val feature = JSONObject().put("type", "Feature").put("id", "id-1")
            .put("properties", JSONObject().put("district_name", "M\u00e4rkischer Kreis").put("county", "H\u00f6xter"))
            .put("geometry", JSONObject("""{"type":"Polygon","coordinates":[[[6,50],[8,50],[8,52],[6,52],[6,50]]]}"""))
        val parsed = publicBoundaryFeatureFromJson(feature, source, 0)!!
        assertEquals(listOf(parsed), filterPublicBoundaryFeatures(listOf(parsed), "maerkischer"))
        assertEquals(listOf(parsed), filterPublicBoundaryFeatures(listOf(parsed), "Hoxter"))
        assertTrue(filterPublicBoundaryFeatures(listOf(parsed), "coesfeld").isEmpty())
    }

    @Test fun searchBudgetCapsAggregateBytesAndElapsedTime() {
        var now = 1_000L
        val budget = PublicBoundaryRequestBudget(nowNanos = { now }, startedNanos = now)
        budget.consume(PublicBoundaryRequests.MAX_RESPONSE_BYTES)
        budget.consume(PublicBoundaryRequests.MAX_TOTAL_RESPONSE_BYTES - PublicBoundaryRequests.MAX_RESPONSE_BYTES)
        assertThrows(IllegalArgumentException::class.java) { budget.consume(1) }
        now += 90_001_000_000L
        assertThrows(IllegalArgumentException::class.java) { budget.checkTime() }
    }
}
