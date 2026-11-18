package org.wierichs.dogtracker.communication

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.provider.Settings
import java.time.DateTimeException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONException
import org.json.JSONObject
import org.wierichs.dogtracker.DeviceTokenStorage

private fun NetworkCapabilities?.canAttemptInternet(): Boolean =
    this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

internal fun hasRelayInternetRoute(context: Context, requireValidation: Boolean = false): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
    return capabilities.canAttemptInternet() &&
        (!requireValidation || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
}

internal fun relayInternetRequest(): NetworkRequest = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .build()

internal fun internetRestorations(context: Context): Flow<Network> = callbackFlow<Network?> {
    val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
    val lock = Any()
    var network: Network? = null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(current: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) {
                network = current
                trySend(current.takeIf { capabilities.canAttemptInternet() })
            }
        }

        override fun onLost(lost: Network) {
            synchronized(lock) {
                if (network == lost) {
                    network = null
                    trySend(null)
                }
            }
        }
    }
    synchronized(lock) {
        manager.registerDefaultNetworkCallback(callback)
        network = manager.activeNetwork
        trySend(network?.takeIf { manager.getNetworkCapabilities(it).canAttemptInternet() })
    }
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.onInternetRestored()

internal fun <T : Any> Flow<T?>.onInternetRestored(): Flow<T> =
    distinctUntilChanged().drop(1).filterNotNull()

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
