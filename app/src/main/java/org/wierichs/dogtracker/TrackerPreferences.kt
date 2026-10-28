package org.wierichs.dogtracker

import android.content.Context
import androidx.core.content.edit

internal object TrackerPreferences {
    private const val FILE_NAME = "tracker_settings"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    private const val KEY_IS_TRACKING = "is_tracking"
    private const val KEY_STATUS = "status"
    private const val KEY_RELAY_DEVICE_ID = "relay_device_id"
    private const val KEY_RELAY_ENCRYPTION_KEY_ID = "relay_encryption_key_id"
    private const val KEY_RELAY_ENCRYPTION_PUBLIC_KEY = "relay_encryption_public_key"
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    const val ENDPOINT = "https://qgtgeawsdxynnqsuanbl.supabase.co/functions/v1/dog-location"
    const val RELAY_ENDPOINT = "https://qgtgeawsdxynnqsuanbl.supabase.co/functions/v1/dog-location-relay"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun endpoint(@Suppress("UNUSED_PARAMETER") context: Context): String = ENDPOINT

    fun intervalSeconds(context: Context): Long =
        preferences(context).getLong(KEY_INTERVAL_SECONDS, 60L)

    fun isTracking(context: Context): Boolean =
        preferences(context).getBoolean(KEY_IS_TRACKING, false)

    fun status(context: Context): String =
        preferences(context).getString(KEY_STATUS, "Noch nicht gestartet.") ?: "Noch nicht gestartet."

    fun deviceToken(context: Context): String? = DeviceTokenStorage.read(context)

    fun saveDeviceToken(context: Context, token: String) {
        if (deviceToken(context) != token) {
            preferences(context).edit { remove(KEY_RELAY_DEVICE_ID) }
        }
        DeviceTokenStorage.write(context, token)
    }

    fun relayDeviceId(context: Context): String? =
        preferences(context).getString(KEY_RELAY_DEVICE_ID, null)

    fun relayEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RELAY_ENABLED, false)

    fun setRelayEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean(KEY_RELAY_ENABLED, enabled) }
    }

    fun saveRelayDeviceId(context: Context, deviceId: String) {
        preferences(context).edit { putString(KEY_RELAY_DEVICE_ID, deviceId) }
    }

    fun relayEncryptionKeyId(context: Context): String? =
        preferences(context).getString(KEY_RELAY_ENCRYPTION_KEY_ID, null)

    fun relayEncryptionPublicKey(context: Context): String? =
        preferences(context).getString(KEY_RELAY_ENCRYPTION_PUBLIC_KEY, null)

    fun saveRelayEncryptionKey(context: Context, keyId: String, publicKey: String) {
        preferences(context).edit {
            putString(KEY_RELAY_ENCRYPTION_KEY_ID, keyId)
            putString(KEY_RELAY_ENCRYPTION_PUBLIC_KEY, publicKey)
        }
    }

    fun saveConfiguration(context: Context, intervalSeconds: Long) {
        preferences(context).edit {
            putLong(KEY_INTERVAL_SECONDS, intervalSeconds)
        }
    }

    fun setTracking(context: Context, isTracking: Boolean) {
        preferences(context).edit { putBoolean(KEY_IS_TRACKING, isTracking) }
    }

    fun setStatus(context: Context, status: String) {
        preferences(context).edit { putString(KEY_STATUS, status) }
    }
}