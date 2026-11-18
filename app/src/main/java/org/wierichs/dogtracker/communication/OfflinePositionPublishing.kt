package org.wierichs.dogtracker.communication

import android.content.Context
import android.location.Location
import java.io.IOException

@Synchronized
internal fun nextRelaySequence(context: Context, deviceId: String): Long {
    val preferences = context.getSharedPreferences("relay_sequences", Context.MODE_PRIVATE)
    val sequence = Math.addExact(preferences.getLong(deviceId, 0), 1)
    if (!preferences.edit().putLong(deviceId, sequence).commit()) {
        throw IOException("Positionssequenz konnte nicht dauerhaft gespeichert werden.")
    }
    return sequence
}

internal fun VerifiedRelayDirectory.localPosition(location: Location, sequence: Long): OfflinePositionMessage? {
    val kind = deviceKind(localDeviceId) ?: return null
    val subject = subjectId(localDeviceId) ?: return null
    return OfflinePositionMessage.create(profileId, localDeviceId, kind, subject, sequence, location.time,
        location.latitude, location.longitude, location.accuracy.toDouble(), RelaySigningIdentity::sign)
}
