package org.wierichs.dogtracker

import android.content.Context
import androidx.core.content.edit

internal object TrackerPreferences {
    private const val FILE_NAME = "tracker_settings"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    private const val KEY_IS_TRACKING = "is_tracking"
    private const val KEY_STATUS = "status"
    const val ENDPOINT = "https://qgtgeawsdxynnqsuanbl.supabase.co/functions/v1/dog-location"

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

    fun saveDeviceToken(context: Context, token: String) = DeviceTokenStorage.write(context, token)

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