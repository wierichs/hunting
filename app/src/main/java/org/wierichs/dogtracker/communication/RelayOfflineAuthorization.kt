package org.wierichs.dogtracker.communication

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import java.time.DateTimeException
import org.json.JSONException
import org.json.JSONObject
import org.wierichs.dogtracker.DeviceTokenStorage

internal fun hasValidatedRelayInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

internal data class RelayOfflineLease(val bootCount: Int, val requestElapsed: Long, val verifiedAt: Long) {
    fun isCurrent(currentBoot: Int, elapsed: Long, lifetime: Long): Boolean =
        bootCount >= 0 && currentBoot == bootCount && requestElapsed >= 0 &&
            elapsed >= requestElapsed && lifetime in 1..86_400_000L &&
            elapsed - requestElapsed < lifetime

    fun toJson(): JSONObject = JSONObject().put("boot", bootCount)
        .put("request_elapsed", requestElapsed).put("verified_at", verifiedAt)

    companion object {
        fun requestStarted(context: Context): Pair<Int, Long> =
            bootCount(context) to SystemClock.elapsedRealtime()

        fun bootCount(context: Context): Int =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

        fun fromJson(json: JSONObject): RelayOfflineLease =
            RelayOfflineLease(json.getInt("boot"), json.getLong("request_elapsed"), json.getLong("verified_at"))
    }
}

internal data class OfflineRelayAuthorization(
    val directory: VerifiedRelayDirectory,
    val capabilities: RelayCapabilities
)

internal object RelayOfflineAuthorization {
    fun verify(
        context: Context,
        capabilities: RelayCapabilities,
        document: JSONObject,
        deviceId: String,
        profileId: String?,
        lease: RelayOfflineLease
    ): OfflineRelayAuthorization {
        val directory = VerifiedRelayDirectory.verifyOffline(document, capabilities, deviceId, profileId, lease.verifiedAt)
            .withOfflineLease(lease, RelayOfflineLease.bootCount(context), SystemClock::elapsedRealtime)
        require(directory.current() && directory.containsKey(deviceId, RelaySigningIdentity.publicKeyBase64Url())) {
            "Relay-Berechtigung ist abgelaufen oder passt nicht zum Geräteschlüssel."
        }
        return OfflineRelayAuthorization(directory, capabilities)
    }

    fun save(context: Context, name: String, owner: String, deviceId: String, capabilities: RelayCapabilities,
        document: JSONObject, lease: RelayOfflineLease) {
        val value = JSONObject().put("owner", owner).put("device_id", deviceId).put("capabilities", capabilities.toJson())
            .put("directory", document).put("lease", lease.toJson())
        DeviceTokenStorage.write(context, value.toString(), name, synchronous = true)
    }

    fun load(context: Context, name: String, owner: String, deviceId: String?,
        profileId: String? = null): OfflineRelayAuthorization? {
        val saved = DeviceTokenStorage.read(context, name) ?: return null
        return try {
            val value = JSONObject(saved)
            require(value.getString("owner") == owner)
            val savedDeviceId = value.getString("device_id")
            require(deviceId == null || savedDeviceId == deviceId)
            verify(context, RelayCapabilities.parse(value.getJSONObject("capabilities")),
                value.getJSONObject("directory"), savedDeviceId, profileId,
                RelayOfflineLease.fromJson(value.getJSONObject("lease")))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: DateTimeException) {
            null
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }
}
