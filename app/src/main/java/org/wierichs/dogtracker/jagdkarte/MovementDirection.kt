package org.wierichs.dogtracker.jagdkarte

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class MovementSample(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val timestamp: Instant
)

internal data class MovementDirection(val bearingDegrees: Double, val observedAt: Instant)

internal class MovementDirectionTracker {
    private val lastSamples = mutableMapOf<String, MovementSample>()
    private val directions = mutableMapOf<String, MovementDirection>()

    fun update(points: List<HuntingMapPoint>, now: Instant): Map<String, MovementDirection> {
        val samples = points.asSequence()
            .filter { it.kind == MapPointKind.PARTICIPANT || it.kind == MapPointKind.DOG }
            .mapNotNull { point ->
                val id = point.subjectId ?: return@mapNotNull null
                val timestamp = point.timestamp ?: return@mapNotNull null
                val accuracy = point.accuracyMeters ?: return@mapNotNull null
                val sample = MovementSample(id, point.latitude, point.longitude, accuracy, timestamp)
                sample.takeIf { isDirectionSampleFresh(it, now) }
            }
            .associateBy { it.id }

        lastSamples.keys.retainAll(samples.keys)
        directions.keys.retainAll(samples.keys)
        samples.forEach { (id, current) ->
            val previous = lastSamples[id]
            if (previous != null && current.timestamp != previous.timestamp) {
                val bearing = movementBearingDegrees(previous, current)
                if (bearing == null) directions.remove(id)
                else directions[id] = MovementDirection(bearing, current.timestamp)
            }
            lastSamples[id] = current
        }
        directions.entries.removeAll { !isDirectionFresh(it.value, now) }
        return directions.toMap()
    }

    fun clear() {
        lastSamples.clear()
        directions.clear()
    }
}

internal fun movementBearingDegrees(previous: MovementSample, current: MovementSample): Double? {
    if (previous.id != current.id || previous.id.isBlank()) return null
    val elapsed = Duration.between(previous.timestamp, current.timestamp).toMillis()
    if (elapsed !in 1_000..MAX_SAMPLE_GAP_MILLIS) return null
    if (!validCoordinate(previous.latitude, previous.longitude) ||
        !validCoordinate(current.latitude, current.longitude)) return null
    val previousAccuracy = previous.accuracyMeters?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val currentAccuracy = current.accuracyMeters?.takeIf { it.isFinite() && it >= 0 } ?: return null

    val latitude1 = Math.toRadians(previous.latitude)
    val latitude2 = Math.toRadians(current.latitude)
    val latitudeDelta = latitude2 - latitude1
    val longitudeDelta = Math.toRadians(current.longitude - previous.longitude)
    val haversine = sin(latitudeDelta / 2).pow(2) + cos(latitude1) * cos(latitude2) *
        sin(longitudeDelta / 2).pow(2)
    val distanceMeters = 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    val minimumMovement = max(MIN_MOVEMENT_METERS, (previousAccuracy + currentAccuracy) * ACCURACY_FACTOR)
    if (distanceMeters <= minimumMovement) return null

    val y = sin(longitudeDelta) * cos(latitude2)
    val x = cos(latitude1) * sin(latitude2) - sin(latitude1) * cos(latitude2) * cos(longitudeDelta)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

internal fun compassDirectionLabel(bearingDegrees: Double): String? {
    if (!bearingDegrees.isFinite()) return null
    val directions = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
    val index = (Math.floorMod(Math.round((bearingDegrees % 360.0) / 45.0).toInt(), directions.size))
    return directions[index]
}

internal fun isDirectionFresh(direction: MovementDirection, now: Instant): Boolean {
    val age = Duration.between(direction.observedAt, now).toMillis()
    return age in 0..MAX_DIRECTION_AGE_MILLIS
}

private fun isDirectionSampleFresh(sample: MovementSample, now: Instant): Boolean {
    val age = Duration.between(sample.timestamp, now).toMillis()
    return age in 0..MAX_DIRECTION_AGE_MILLIS
}

private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val MIN_MOVEMENT_METERS = 8.0
private const val ACCURACY_FACTOR = 0.75
private const val MAX_SAMPLE_GAP_MILLIS = 180_000L
private const val MAX_DIRECTION_AGE_MILLIS = 90_000L