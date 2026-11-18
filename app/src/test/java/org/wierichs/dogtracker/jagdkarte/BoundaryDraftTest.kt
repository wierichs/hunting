package org.wierichs.dogtracker.jagdkarte

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoundaryDraftTest {
    private val source = JSONObject("""{"type":"MultiPolygon","coordinates":[
        [[[6,50],[8,50],[8,52],[6,52],[6,50]],[[6.5,50.5],[6.5,51],[7,51],[7,50.5],[6.5,50.5]]],
        [[[9,50],[10,50],[10,51],[9,51],[9,50]]]
    ]}""")

    @Test fun roundTripPreservesPartsHolesAndCoordinateOrder() {
        val draft = BoundaryDraft.fromGeometry(source)
        assertEquals(2, draft.polygons.size)
        assertEquals(2, draft.polygons[0].size)
        assertEquals(4, draft.polygons[0][0].size)
        val result = draft.validatedGeometry()
        assertTrue(locationInsideBoundary(result, 51.5, 7.5))
        assertFalse(locationInsideBoundary(result, 50.75, 6.75))
        assertTrue(locationInsideBoundary(result, 50.5, 9.5))
        assertEquals(source.getJSONArray("coordinates").toString(), result.getJSONArray("coordinates").toString())
    }

    @Test fun editsAreImmutableAndClosingPointFollowsFirstVertex() {
        val draft = BoundaryDraft.fromGeometry(source)
        val moved = draft.move(0, 0, 0, MarkerCoordinates(49.9, 6.0))
        assertEquals(50.0, draft.polygons[0][0][0].latitude, 0.0)
        val ring = moved.validatedGeometry().getJSONArray("coordinates").getJSONArray(0).getJSONArray(0)
        assertEquals(ring.getJSONArray(0).toString(), ring.getJSONArray(ring.length() - 1).toString())
        assertEquals(draft.polygons[0][1], moved.polygons[0][1])
        assertEquals(draft.polygons[1], moved.polygons[1])
    }

    @Test fun insertionDeletionAndPartRemovalKeepValidGeometry() {
        val draft = BoundaryDraft.fromGeometry(source)
        val inserted = draft.insertAfter(0, 0, 0, MarkerCoordinates(50.0, 7.0))
        assertEquals(5, inserted.polygons[0][0].size)
        assertEquals(draft, inserted.removeVertex(0, 0, 1))
        val single = draft.removePolygon(1).removeRing(0, 1)
        assertEquals("Polygon", single.validatedGeometry().getString("type"))
        assertThrows(IllegalArgumentException::class.java) { single.removePolygon(0) }
        assertThrows(IllegalArgumentException::class.java) { single.removeRing(0, 0) }
    }

    @Test fun rejectsSelfIntersectionsOutsideHolesOverlapsAndBadCoordinates() {
        val draft = BoundaryDraft.fromGeometry(source)
        assertThrows(IllegalArgumentException::class.java) {
            draft.move(0, 0, 1, MarkerCoordinates(53.0, 5.0)).validatedGeometry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.move(0, 1, 0, MarkerCoordinates(49.0, 6.5)).validatedGeometry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.addPolygon(draft.polygons[0][0]).validatedGeometry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.move(0, 0, 0, MarkerCoordinates(Double.NaN, 6.0)).validatedGeometry()
        }
    }

    @Test fun supportsNewPartsAndHolesButNeverDegenerateRings() {
        val draft = BoundaryDraft.fromGeometry(source).removePolygon(1)
        val points = listOf(MarkerCoordinates(53.0, 9.0), MarkerCoordinates(53.0, 10.0), MarkerCoordinates(54.0, 9.0))
        assertEquals("MultiPolygon", draft.addPolygon(points).validatedGeometry().getString("type"))
        assertThrows(IllegalArgumentException::class.java) { draft.addHole(0, points).validatedGeometry() }
        assertThrows(IllegalArgumentException::class.java) { BoundaryDraft(listOf(listOf(points.take(2)))).validatedGeometry() }
        assertThrows(IllegalArgumentException::class.java) { BoundaryDraft(listOf(listOf(points))).removeVertex(0, 0, 0) }
    }
}