package org.wierichs.dogtracker.jagdkarte

import java.time.Instant
import org.wierichs.dogtracker.communication.OfflinePositionMessage

internal fun HuntingSnapshot.withNearbyPositions(
    positions: List<OfflinePositionMessage>, now: Instant
): HuntingSnapshot {
    if (!profile.huntingMode) return copy(points = points.filter { it.kind == MapPointKind.FACILITY })
    val nearby = positions.asSequence()
        .filter { it.profileId == profile.id }
        .sortedByDescending { it.recordedAtEpochMillis }
        .map { position ->
            val kind = if (position.subjectType == "dog") MapPointKind.DOG else MapPointKind.PARTICIPANT
            val timestamp = Instant.ofEpochMilli(position.recordedAtEpochMillis)
            val label = positionLabels[kind to position.subjectId]
            HuntingMapPoint(
                position.latitude, position.longitude,
                label?.name ?: if (kind == MapPointKind.DOG) "Hund" else "Teilnehmer",
                "Per Nearby empfangen\nMesszeit: $timestamp\nGenauigkeit: ${position.accuracyMeters.toInt()} m",
                kind, timestamp = timestamp, subjectId = position.subjectId, accuracyMeters = position.accuracyMeters,
                participantRole = label?.role ?: HuntingParticipantRole.GUEST, isNearby = true,
                createdBy = label?.createdBy
            )
        }
        .distinctBy { it.kind to it.subjectId }
        .associateByTo(linkedMapOf()) { it.kind to it.subjectId }
    if (nearby.isEmpty()) return this

    val merged = points.map { server ->
        val key = server.kind to server.subjectId
        val local = nearby.remove(key) ?: return@map server
        val measuredAt = server.timestamp
        if (measuredAt != null && !measuredAt.isBefore(requireNotNull(local.timestamp)) &&
            !measuredAt.isAfter(now.plusSeconds(30)) && server.visibleAt(now)
        ) server else local
    }
    return copy(points = merged + nearby.values)
}
