package org.wierichs.dogtracker

import java.security.SecureRandom

internal const val DOG_TRACKER_PAIRING_QR_PREFIX = "jagdkarte-dogtracker:pair:v1:"
private val DOG_DEVICE_TOKEN_PATTERN = Regex("^[0-9a-f]{64}$")

internal enum class DogTrackerPairingResolution {
    ACTIVATED,
    ALREADY_ACTIVE,
    CANCELLED,
    NOT_FOUND
}

internal fun createDogTrackerDeviceToken(random: SecureRandom = SecureRandom()): String =
    ByteArray(32).also(random::nextBytes).joinToString("") { byte -> "%02x".format(byte) }

internal fun dogTrackerPairingQr(token: String): String {
    require(DOG_DEVICE_TOKEN_PATTERN.matches(token)) { "Ungueltiger Geraeteschluessel." }
    return DOG_TRACKER_PAIRING_QR_PREFIX + token
}

internal fun dogTrackerDeviceTokenFromQr(value: String?): String? {
    val qr = value?.trim() ?: return null
    if (!qr.startsWith(DOG_TRACKER_PAIRING_QR_PREFIX)) return null
    return qr.removePrefix(DOG_TRACKER_PAIRING_QR_PREFIX).takeIf(DOG_DEVICE_TOKEN_PATTERN::matches)
}

internal suspend fun settleDogTrackerPairing(
    token: String,
    confirm: Boolean,
    completeOnServer: suspend (String, Boolean) -> String,
    saveActiveToken: (String) -> Unit,
    clearPendingToken: () -> Unit
): DogTrackerPairingResolution {
    require(DOG_DEVICE_TOKEN_PATTERN.matches(token)) { "Ungueltiger Geraeteschluessel." }
    val serverStatus = completeOnServer(token, confirm)
    return when (serverStatus) {
        "activated" -> {
            check(confirm) { "Unerwartete Bestaetigung des Geraeteschluessels." }
            saveActiveToken(token)
            clearPendingToken()
            DogTrackerPairingResolution.ACTIVATED
        }
        "already_active" -> {
            saveActiveToken(token)
            clearPendingToken()
            DogTrackerPairingResolution.ALREADY_ACTIVE
        }
        "cancelled" -> {
            check(!confirm) { "Unerwarteter Abbruch des Geraeteschluessels." }
            clearPendingToken()
            DogTrackerPairingResolution.CANCELLED
        }
        "not_found" -> {
            if (!confirm) clearPendingToken()
            DogTrackerPairingResolution.NOT_FOUND
        }
        else -> throw IllegalStateException("Unerwarteter Pairing-Status vom Server.")
    }
}