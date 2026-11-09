package org.wierichs.dogtracker.jagdkarte

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntingLocationTest {
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val boundary = JSONObject("""{
        "type":"Polygon","coordinates":[
            [[6,50],[8,50],[8,52],[6,52],[6,50]],
            [[6.5,50.5],[6.5,51],[7,51],[7,50.5],[6.5,50.5]]
        ]
    }""")

    @Test fun polygonUsesLongitudeLatitudeAndExcludesHoles() {
        assertTrue(locationInsideBoundary(boundary, 51.5, 7.5))
        assertTrue(locationInsideBoundary(boundary, 50.0, 6.0))
        assertFalse(locationInsideBoundary(boundary, 50.75, 6.75))
        assertFalse(locationInsideBoundary(boundary, 7.5, 51.5))
        assertFalse(locationInsideBoundary(boundary, 53.0, 7.0))
    }

    @Test fun multiPolygonChecksAllParts() {
        val multi = JSONObject("""{"type":"MultiPolygon","coordinates":[
            [[[6,50],[7,50],[7,51],[6,51],[6,50]]],
            [[[8,52],[9,52],[9,53],[8,53],[8,52]]]
        ]}""")
        assertTrue(locationInsideBoundary(multi, 52.5, 8.5))
        assertFalse(locationInsideBoundary(multi, 51.5, 7.5))
    }

    @Test fun missingMalformedOrNonPolygonBoundariesFailClosed() {
        assertFalse(locationInsideBoundary(null, 51.0, 7.0))
        assertFalse(locationInsideBoundary(JSONObject("""{"type":"Point","coordinates":[7,51]}"""), 51.0, 7.0))
        assertFalse(locationInsideBoundary(JSONObject("""{"type":"Polygon","coordinates":[]}"""), 51.0, 7.0))
        assertFalse(locationInsideBoundary(boundary, Double.NaN, 7.0))
    }

    @Test fun participantPositionsExpireAfterNinetySeconds() {
        val point = HuntingMapPoint(51.0, 7.0, "Gast", "", MapPointKind.PARTICIPANT, now.minusSeconds(89))
        assertTrue(point.visibleAt(now))
        assertFalse(point.copy(timestamp = now.minusSeconds(90)).visibleAt(now))
        assertFalse(point.copy(timestamp = null).visibleAt(now))
        assertFalse(point.copy(timestamp = now.plusSeconds(60)).visibleAt(now))
    }

    @Test fun dogPositionsOutsideBoundaryRemainVisibleButBecomeStale() {
        val point = HuntingMapPoint(54.0, 10.0, "Hund", "", MapPointKind.DOG, now.minusSeconds(121))
        assertTrue(point.visibleAt(now))
        assertTrue(point.staleAt(now))
        assertFalse(point.copy(timestamp = now.minusSeconds(119)).staleAt(now))
        assertTrue(point.copy(timestamp = null).staleAt(now))
    }

    @Test fun expiredMarkersAndInvalidCoordinatesAreHidden() {
        val point = HuntingMapPoint(51.0, 7.0, "Anschuss", "", MapPointKind.FACILITY, expiresAt = now)
        assertFalse(point.visibleAt(now))
        assertTrue(point.copy(expiresAt = now.plusSeconds(1)).visibleAt(now))
        assertFalse(point.copy(latitude = 91.0, expiresAt = null).visibleAt(now))
    }

    @Test fun participantRpcUsesItsActualColumnNamesAndSafeFallbacks() {
        val names = participantNames(JSONArray("""[
            {"participant_user_id":"owner","display_name":"Anna","participant_email":"anna@example.org"},
            {"participant_user_id":"guest","display_name":null,"participant_email":"gast@example.org"},
            {"participant_user_id":"editor","display_name":"","participant_email":null}
        ]"""))
        assertEquals("Anna", names["owner"])
        assertEquals("gast@example.org", names["guest"])
        assertEquals("Teilnehmer", names["editor"])
    }
}