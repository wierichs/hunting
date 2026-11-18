package org.wierichs.dogtracker.communication

import android.content.Context
import android.os.SystemClock
import org.wierichs.dogtracker.DeviceTokenStorage

internal object DogRelayAuthorization {
    private const val PREFERENCES = "dog_relay_authorization_v2"
    private const val LEGACY_PREFERENCES = "dog_relay_authorization"
    private const val REFRESH_INTERVAL_MILLIS = 60_000L
    internal data class Authorization(val directory: VerifiedRelayDirectory, val capabilities: RelayCapabilities)
    @Volatile private var cached: Authorization? = null
    private var nextRefreshElapsed = 0L

    @Synchronized
    fun current(context: Context, deviceId: String): VerifiedRelayDirectory? = authorization(context, deviceId)?.directory

    @Synchronized
    fun authorization(context: Context, deviceId: String): Authorization? {
        cached?.let { if (it.directory.localDeviceId == deviceId && it.directory.current()) return it }
        val saved = RelayOfflineAuthorization.load(context, PREFERENCES, deviceId, deviceId) ?: return null
        return Authorization(saved.directory, saved.capabilities).also { cached = it }
    }

    @Synchronized
    fun refresh(context: Context, deviceId: String, deviceToken: String) {
        if (current(context, deviceId) != null && SystemClock.elapsedRealtime() < nextRefreshElapsed) return
        val api = RelayApiClient(org.wierichs.dogtracker.TrackerPreferences.RELAY_ENDPOINT, deviceToken)
        try {
            val capabilities = api.fetchOfflineCapabilities()
            val (boot, requestElapsed) = RelayOfflineLease.requestStarted(context)
            val document = api.fetchOfflineDirectory(deviceId)
            val lease = RelayOfflineLease(boot, requestElapsed, System.currentTimeMillis())
            val verified = RelayOfflineAuthorization.verify(context, capabilities, document, deviceId, null, lease)
            RelayOfflineAuthorization.save(context, PREFERENCES, deviceId, deviceId, capabilities, document, lease)
            cached = Authorization(verified.directory, capabilities)
            nextRefreshElapsed = SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MILLIS
        } catch (failure: RelayApiException) {
            if (failure.statusCode in setOf(401, 403, 409)) clear(context)
            throw failure
        }
    }

    @Synchronized
    fun clear(context: Context) {
        cached = null
        nextRefreshElapsed = 0
        DeviceTokenStorage.clear(context, PREFERENCES, synchronous = true)
        check(context.applicationContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Relay-Berechtigung konnte nicht gelöscht werden."
        }
    }
}
