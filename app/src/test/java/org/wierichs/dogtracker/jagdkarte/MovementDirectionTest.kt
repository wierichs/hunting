package org.wierichs.dogtracker.jagdkarte

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementDirectionTest {
    private val start = Instant.parse("2026-11-11T10:00:00Z")

    @Test fun computesCardinalMovementDirections() {
        val origin = sample("person-1", 51.2, 7.0, start)
        val north = sample("person-1", 51.201, 7.0, start.plusSeconds(30))
        val east = sample("person-1", 51.2, 7.001, start.plusSeconds(30))

        assertEquals("N", compassDirectionLabel(movementBearingDegrees(origin, north)!!))
        assertEquals("O", compassDirectionLabel(movementBearingDegrees(origin, east)!!))
    }

    @Test fun suppressesNoiseStationaryAndOldOrMismatchedSamples() {
        val origin = sample("dog-1", 51.2, 7.0, start)

        assertNull(movementBearingDegrees(origin, sample("dog-1", 51.200001, 7.0, start.plusSeconds(30))))
        assertNull(movementBearingDegrees(origin, sample("dog-1", 51.201, 7.0, start.plusSeconds(181))))
        assertNull(movementBearingDegrees(origin, sample("another-dog", 51.201, 7.0, start.plusSeconds(30))))
        assertNull(movementBearingDegrees(origin.copy(accuracyMeters = 45.0),
            sample("dog-1", 51.2001, 7.0, start.plusSeconds(30), 40.0)))
    }

    @Test fun acceptsMovementAcrossTheDateLineAndWrapsCompass() {
        val west = sample("person-1", 0.0, 179.999, start)
        val eastAcrossDateLine = sample("person-1", 0.0, -179.999, start.plusSeconds(10))
        val bearing = movementBearingDegrees(west, eastAcrossDateLine)

        assertTrue(bearing != null && bearing in 80.0..100.0)
        assertEquals("N", compassDirectionLabel(359.0 + 2.0))
    }

    @Test fun trackerNeedsTwoFreshFixesAndExpiresDirection() {
        val tracker = MovementDirectionTracker()
        val first = point("dog-1", 51.2, 7.0, start)
        val second = point("dog-1", 51.201, 7.0, start.plusSeconds(30))

        assertTrue(tracker.update(listOf(first), start).isEmpty())
        val direction = tracker.update(listOf(second), start.plusSeconds(30)).getValue("dog-1")
        assertEquals("N", compassDirectionLabel(direction.bearingDegrees))
        assertTrue(isDirectionFresh(direction, start.plusSeconds(60)))
        assertFalse(isDirectionFresh(direction, start.plusSeconds(121)))
    }

    @Test fun trackerDropsDirectionWhenCurrentFixIsStationaryOrStale() {
        val tracker = MovementDirectionTracker()
        tracker.update(listOf(point("person-1", 51.2, 7.0, start)), start)
        tracker.update(listOf(point("person-1", 51.201, 7.0, start.plusSeconds(30))), start.plusSeconds(30))

        val stationary = point("person-1", 51.201, 7.0, start.plusSeconds(60))
        assertTrue(tracker.update(listOf(stationary), start.plusSeconds(60)).isEmpty())
        assertTrue(tracker.update(listOf(stationary), start.plusSeconds(121)).isEmpty())
    }

    private fun sample(
        id: String,
        latitude: Double,
        longitude: Double,
        timestamp: Instant,
        accuracyMeters: Double = 3.0
    ) = MovementSample(id, latitude, longitude, accuracyMeters, timestamp)

    private fun point(id: String, latitude: Double, longitude: Double, timestamp: Instant) =
        HuntingMapPoint(latitude, longitude, id, "", MapPointKind.DOG, subjectId = id,
            accuracyMeters = 3.0, timestamp = timestamp)
}